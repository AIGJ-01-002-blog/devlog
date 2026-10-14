"""#265 매매 재현 영상: 공개 캔들 받기, 캔들·체결이 나오는 시각, 실제 렌더."""

import json
import shutil
import subprocess
import unittest
import wave
from unittest import mock
from pathlib import Path
from tempfile import TemporaryDirectory

from devlog_render import candles, remotion, replay, trade_sfx
from devlog_render.candles import Candle
from devlog_render.render import FPS, Renderer, Script, find_font

T0 = 1_791_853_200  # 2026-10-13T01:00:00Z (5분 경계)


def series(n=60, sec=300):
    out, p = [], 92_000.0
    for i in range(n):
        c = p + (37 if i % 3 else -21)
        out.append(Candle(T0 + i * sec, p, max(p, c) + 15, min(p, c) - 15, c))
        p = c
    return out


def iso(t):
    from datetime import datetime, timezone
    return datetime.fromtimestamp(t, timezone.utc).isoformat().replace("+00:00", "Z")


class CandlesTest(unittest.TestCase):
    def test_binance_klines_and_fallback_host(self):
        urls = []

        def get(url):
            urls.append(url)
            if "api.binance.com" in url:
                raise candles.urllib.error.URLError("451 지역 제한")
            return json.dumps([[T0 * 1000, "1", "2", "0.5", "1.5", "9"], [(T0 + 600) * 1000, "1.5", "3", "1", "2", "9"]]).encode()

        got = candles.fetch("BINANCE", "btcusdt", "5m", T0, T0 + 900, get)
        self.assertIn("symbol=BTCUSDT", urls[0])
        self.assertIn("data-api.binance.vision", urls[1])
        # 빈 5분은 직전 종가로 채운다
        self.assertEqual([c.t for c in got], [T0, T0 + 300, T0 + 600])
        self.assertEqual(got[1], Candle(T0 + 300, 1.5, 1.5, 1.5, 1.5))

    def test_upbit_candles_come_newest_first(self):
        def get(url):
            self.assertIn("/v1/candles/minutes/240?", url)
            self.assertIn("market=KRW-BTC", url)
            return json.dumps([
                {"candle_date_time_utc": "2026-10-13T04:00:00", "opening_price": 2, "high_price": 3, "low_price": 1, "trade_price": 2.5},
                {"candle_date_time_utc": "2026-10-13T00:00:00", "opening_price": 1, "high_price": 2, "low_price": 1, "trade_price": 2},
            ]).encode()

        start = candles.parse_time("2026-10-13T00:00:00Z")
        got = candles.fetch("UPBIT", "KRW-BTC", "4h", start, start + 2 * 14_400, get)
        self.assertEqual([c.c for c in got], [2, 2.5])

    def test_rejects_unknown_exchange_and_too_many(self):
        with self.assertRaises(candles.CandleError):
            candles.fetch("KIS", "005930", "5m", T0, T0 + 600, lambda u: b"[]")
        with self.assertRaises(candles.CandleError):
            candles.fetch("BINANCE", "BTCUSDT", "1m", T0, T0 + 60 * 500, lambda u: b"[]")


class TimelineTest(unittest.TestCase):
    def test_fill_appears_when_its_scene_starts(self):
        cs = series()
        fills = [replay.Fill("BUY", 92_100, T0 + 20 * 300 + 30), replay.Fill("SELL", 92_400, T0 + 40 * 300)]
        starts, total = [0.0, 5.0, 11.0, 16.0], 22.0
        times, fill_times = replay.reveal_times(cs, fills, 300, starts, total)
        self.assertEqual(times[:8], [0.0] * 8)  # 첫 체결 전 흐름의 40%는 처음부터 보인다
        self.assertAlmostEqual(fill_times[0], 5.6)
        self.assertAlmostEqual(times[20], 5.6)
        self.assertAlmostEqual(fill_times[1], 11.6)
        self.assertTrue(all(a <= b for a, b in zip(times, times[1:])), "캔들은 순서대로 나온다")
        self.assertLess(times[-1], total)

    def test_more_fills_than_scenes_stay_in_order(self):
        cs = series()
        fills = [replay.Fill("BUY", 92_000, T0 + k * 600) for k in range(5, 10)]
        _, fill_times = replay.reveal_times(cs, fills, 300, [0.0, 4.0, 8.0], 12.0)
        self.assertEqual(fill_times, sorted(fill_times))
        self.assertLessEqual(fill_times[-1], 11.5)

    def test_money_matches_server(self):
        self.assertEqual(replay.money(92100.5, "USDT"), "92,100.50 USDT")
        self.assertEqual(replay.money(13.2, "USDT", sign=True), "+13.20 USDT")
        self.assertEqual(replay.money(-4200.4, "KRW"), "-4,200원")
        self.assertEqual(replay.money(0.00012345, "USDT"), "0.00012345 USDT")


def _fake_remotion(props: dict, public_dir: Path, out: Path, crf: int = 16) -> None:
    """Remotion 대신 props 길이만큼 소리 없는 화면을 만든다(크롬 없이 props와 소리 섞기를 확인)"""
    _fake_remotion.props = props
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"color=c=navy:s=1080x1920:r={FPS}",
                    "-frames:v", str(props["totalFrames"]), "-c:v", "libx264", "-pix_fmt", "yuv420p", str(out)], check=True)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg 없음")
class ReplayRenderTest(unittest.TestCase):
    def job(self):
        fills = [{"side": "BUY", "price": 92_100, "executedAt": iso(T0 + 20 * 300), "realizedPnl": None},
                 {"side": "SELL", "price": 92_400, "executedAt": iso(T0 + 40 * 300), "realizedPnl": 3.1}]
        return {"title": "비트코인 매매 다시 보기", "handle": "minseo", "scenes": [
            {"caption": "왜 샀을까요?", "narration": "그때 저는 왜 여기서 매수했을까요?"},
            {"caption": "10:40 매수", "narration": "지금 거래량이 붙었어요. 들어갑니다."},
            {"caption": "12:20 매도", "narration": "목표가에 닿아서 정리합니다."},
            {"caption": "결과 +3.10 USDT", "narration": "3달러 벌었어요."},
        ], "replay": {"exchange": "BINANCE", "symbol": "BTCUSDT", "symbolName": "비트코인", "currency": "USDT", "mode": "PAPER",
                      "interval": "5m", "from": iso(T0), "to": iso(T0 + 60 * 300), "fills": fills}}

    def test_fetches_public_candles_and_hands_chart_to_remotion(self):
        asked = []

        def fetch(exchange, symbol, interval, start, end):
            asked.append((exchange, symbol, interval, start, end))
            return series()

        r = Renderer(find_font(), find_font(mono=True), "silent", music="none", use_remotion=True, fetch_candles=fetch)
        with TemporaryDirectory() as tmp, mock.patch.object(remotion, "render", _fake_remotion):
            out = Path(tmp) / "r.mp4"
            ms = r.render(Script.from_job(self.job()), out)
            self.assertGreater(ms, 2000)
        self.assertEqual(asked, [("BINANCE", "BTCUSDT", "5m", T0, T0 + 60 * 300)])
        scenes = _fake_remotion.props["scenes"]
        # 대본 장면은 모두 차트 장면, 맨 끝에 말 없는 고지 카드 한 장
        self.assertEqual([s["custom"]["kind"] for s in scenes], ["replay"] * 4 + ["replay-card"])
        self.assertEqual(scenes[-1]["custom"]["data"], {"mode": "모의투자"})
        self.assertEqual(scenes[-1]["pages"], [])
        self.assertLess(scenes[-1]["durationInFrames"], 2 * FPS)
        chart = scenes[1]["custom"]["data"]
        self.assertEqual(len(chart["candles"]), 60)
        self.assertEqual([f["side"] for f in chart["fills"]], ["BUY", "SELL"])
        self.assertEqual(chart["result"]["label"], "+3.10 USDT")
        self.assertGreater(chart["startFrame"], scenes[0]["custom"]["data"]["startFrame"])
        # 체결은 그 장면이 화면에 나온 뒤에 찍힌다
        self.assertGreaterEqual(chart["fills"][0]["at"], chart["startFrame"])

    def test_needs_remotion(self):
        r = Renderer(find_font(), find_font(mono=True), "silent", use_remotion=False, fetch_candles=lambda *_: series())
        with TemporaryDirectory() as tmp, self.assertRaisesRegex(RuntimeError, "Remotion"):
            r.render(Script.from_job(self.job()), Path(tmp) / "x.mp4")

    def test_candle_error_fails_the_job(self):
        def fetch(*_):
            raise candles.CandleError("바이낸스 캔들을 받지 못했어요")

        r = Renderer(find_font(), find_font(mono=True), "silent", use_remotion=True, fetch_candles=fetch)
        with TemporaryDirectory() as tmp, self.assertRaisesRegex(candles.CandleError, "캔들"):
            r.render(Script.from_job(self.job()), Path(tmp) / "x.mp4")




class EffectsTest(unittest.TestCase):
    """체결 효과음·흔들림 순서표 (#265): 렌더 방식이 바뀌어도 쓰는 데이터"""

    def test_cues_follow_fills_and_result(self):
        r = replay.Replay("BINANCE", "BTCUSDT", "비트코인", "USDT", "PAPER", "5m",
                          [replay.Fill("BUY", 100.0, 600), replay.Fill("SELL", 110.0, 1500, 10.0)],
                          [Candle(i * 300, 100, 101, 99, 100) for i in range(8)])
        starts = [0.0, 3.0, 6.0, 9.0, 12.0]
        cues = replay.cues(r, starts, 15.0)
        kinds = [c.kind for c in cues if c.kind != "tick"]
        self.assertEqual(kinds, ["whoosh", "buy", "whoosh", "sell", "win"])
        self.assertTrue(any(c.kind == "tick" for c in cues))
        with TemporaryDirectory() as tmp:
            path = trade_sfx.render(cues, 15.0, Path(tmp) / "s.wav")
            with wave.open(str(path)) as w:
                self.assertEqual((w.getnchannels(), w.getframerate()), (2, 44_100))
                self.assertAlmostEqual(w.getnframes() / 44_100, 15.0, places=1)
            # 장면 시작 기준 훅용: (장면, 장면 안 시각, 종류, 크기)와 종류별 파일
            scene, at, kind, _ = next(e for e in trade_sfx.per_scene(cues, starts) if e[2] == "buy")
            self.assertEqual(scene, 1)
            self.assertGreaterEqual(at, 0.0)
            self.assertTrue(trade_sfx.sound_file("sell", Path(tmp)).stat().st_size > 1000)


if __name__ == "__main__":
    unittest.main()
