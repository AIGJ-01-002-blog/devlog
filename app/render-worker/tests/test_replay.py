"""#265 매매 재현 영상: 공개 캔들 받기, 캔들·체결이 나오는 시각, 실제 렌더."""

import json
import shutil
import subprocess
import unittest
import wave
from unittest import mock
from pathlib import Path
from tempfile import TemporaryDirectory

from devlog_render import bgm, candles, remotion, replay, trade_bgm, trade_sfx
from devlog_render.candles import Candle
from devlog_render.render import FPS, TRANSITION, Renderer, Script, find_font

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
            candles.fetch("BINANCE", "BTCUSDT", "1m", T0, T0 + 60 * 1000, lambda u: b"[]")

    def test_upbit_pages_back_from_the_end(self):
        tos = []

        def get(url):
            q = candles.urllib.parse.parse_qs(candles.urllib.parse.urlparse(url).query)
            tos.append((q["to"][0], int(q["count"][0])))
            end = candles.parse_time(q["to"][0])
            return json.dumps([{"candle_date_time_utc": iso(end - 60 * (k + 1)).replace("Z", ""), "opening_price": 1, "high_price": 1,
                                "low_price": 1, "trade_price": 1} for k in range(int(q["count"][0]))]).encode()

        got = candles.fetch("UPBIT", "KRW-BTC", "1m", T0, T0 + 60 * 300, get)
        self.assertEqual([c for _, c in tos], [200, 100])
        self.assertEqual(len(got), 300)
        self.assertEqual(got[0].t, T0)


class TimelineTest(unittest.TestCase):
    def replay(self, fills, cs=None, interval="5m"):
        return replay.Replay("BINANCE", "BTCUSDT", "비트코인", "USDT", "PAPER", interval, fills, cs if cs is not None else series())

    def test_chart_flows_without_breaks_and_hits_each_fill_when_its_scene_starts(self):
        fills = [replay.Fill("BUY", 92_100, T0 + 20 * 300 + 150), replay.Fill("SELL", 92_400, T0 + 40 * 300, 3.0)]
        r = self.replay(fills)
        line = replay.timeline(r, [0.0, 5.0, 11.0, 16.0], 22.0)
        # 체결은 장면이 화면에 나오고 LEAD초 뒤, 그 캔들을 그리는 중에 찍힌다
        self.assertEqual(line.fills, [5.0 + replay.LEAD, 11.0 + replay.LEAD])
        self.assertAlmostEqual(line.head(5.0 + replay.LEAD), 20.5)
        self.assertAlmostEqual(line.head(11.0 + replay.LEAD), 40.1)
        # 제목 카드가 걷힐 때부터 결산 장면까지 앞으로만, 끊김 없이 흐른다
        heads = [line.head(t / 30) for t in range(22 * 30)]
        self.assertTrue(all(0 <= b - a < 1 for a, b in zip(heads, heads[1:])), "한 프레임에 한 캔들 넘게 튀지 않는다")
        self.assertEqual(line.head(30.0), line.head(16.0))  # 결산 장면부터 멈춘다

    def test_zooms_out_so_buy_and_sell_share_one_screen(self):
        cs = series(400, 60)
        r = self.replay([replay.Fill("BUY", 92_100, T0 + 100 * 60), replay.Fill("SELL", 92_400, T0 + 250 * 60, 3.0)], cs, "1m")
        line = replay.timeline(r, [0.0, 5.0, 11.0, 16.0], 22.0)
        self.assertGreaterEqual(line.visible, 150 * replay.SPAN_MARGIN)
        # 가격 눈금은 영상 내내 하나라 보이는 캔들이 모두 들어온다
        self.assertLess(line.lo, min(c.l for c in cs[100:260]))
        self.assertGreater(line.hi, max(c.h for c in cs[100:260]))

    def test_camera_keeps_the_current_price_near_the_middle(self):
        cs = series(400, 60)
        r = self.replay([replay.Fill("BUY", 92_100, T0 + 100 * 60), replay.Fill("SELL", 92_400, T0 + 250 * 60, 3.0)], cs, "1m")
        line = replay.timeline(r, [0.0, 5.0, 11.0, 16.0], 22.0)
        heads = [line.head(g / 30) for g in range(22 * 30)]
        span, centers = replay.camera(r, line, heads, 30)
        self.assertLess(span, line.hi - line.lo)  # 줌아웃 화면보다 확대한다
        for h, c in zip(heads, centers):
            self.assertLessEqual(abs(replay._price_at(cs, h) - c), span * 0.35 + 1e-6)
        self.assertTrue(all(abs(b - a) < span * 0.05 for a, b in zip(centers, centers[1:])), "카메라는 튀지 않고 부드럽게 따라간다")

    def test_more_fills_than_scenes_stay_in_order(self):
        fills = [replay.Fill("BUY", 92_000, T0 + k * 600) for k in range(5, 10)]
        r = self.replay(fills)
        times = replay.timeline(r, [0.0, 4.0, 8.0], 12.0).fills
        self.assertEqual(times, sorted(times))
        self.assertLessEqual(times[-1], 12.0)

    def test_subtitles_entry_take_profit_and_stop_loss(self):
        fills = [replay.Fill("BUY", 92_100, T0, None, 0.2052), replay.Fill("SELL", 92_400, T0 + 600, 25.5, 0.1),
                 replay.Fill("SELL", 91_000, T0 + 900, -40.0, 0.1052)]
        subs = replay.subtitles(self.replay(fills))
        self.assertEqual(subs[0], {"text": "0.2052 BTC 매수 체결", "detail": None, "tone": "entry"})
        self.assertEqual(subs[1]["text"], "0.1 BTC 매도 익절")
        self.assertEqual(subs[1]["detail"], "( +25.50 USDT 수익 | 누적 +25.50 USDT )")
        self.assertEqual((subs[2]["tone"], subs[2]["detail"]), ("loss", "( -40.00 USDT 손실 | 누적 -14.50 USDT )"))
        self.assertEqual(replay.base_asset(replay.Replay("UPBIT", "KRW-BTC", "", "KRW", "PAPER", "1m", [])), "BTC")

    def test_plays_the_window_at_one_minute_when_it_fits(self):
        self.assertEqual(replay.play_interval(T0, T0 + 6 * 3600), "1m")
        self.assertEqual(replay.play_interval(T0, T0 + 30 * 3600), "5m")

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
            sec = candles.INTERVAL_SECONDS[interval]
            return series((end - start) // sec, sec)

        r = Renderer(find_font(), find_font(mono=True), "silent", music="none", use_remotion=True, fetch_candles=fetch)
        with TemporaryDirectory() as tmp, mock.patch.object(remotion, "render", _fake_remotion):
            out = Path(tmp) / "r.mp4"
            ms = r.render(Script.from_job(self.job()), out)
            self.assertGreater(ms, 2000)
        # 서버가 5분봉으로 정한 다섯 시간을 1분봉으로, 화면 왼쪽을 채울 앞 HISTORY개와 함께 받는다
        self.assertEqual(asked, [("BINANCE", "BTCUSDT", "1m", T0 - replay.HISTORY * 60, T0 + 60 * 300)])
        scenes = _fake_remotion.props["scenes"]
        # 대본 장면은 모두 끊김 없이 이어지는 차트 장면(첫머리는 제목 카드), 맨 끝에 말 없는 고지 카드 한 장
        self.assertEqual([s["custom"]["kind"] for s in scenes], ["replay"] * 4 + ["replay-card", "outro"])
        self.assertEqual([s["cut"] for s in scenes], [True, True, True, True, False, False])
        self.assertEqual(scenes[0]["custom"]["data"]["title"]["title"], "왜 샀을까요?")
        self.assertEqual(scenes[0]["custom"]["data"]["titleUntil"], round(replay.TITLE * FPS))
        self.assertTrue(all(s["caption"] == "" for s in scenes[:4]), "큰 요점 자막 대신 차트 안 체결 자막을 쓴다")
        # 거래 하나는 말이 짧아도 MIN_TRADE초는 보여 준다
        self.assertGreaterEqual(scenes[1]["durationInFrames"], round((replay.MIN_TRADE + TRANSITION) * FPS))
        self.assertEqual(scenes[-2]["custom"]["data"], {"mode": "모의투자"})
        self.assertEqual(scenes[-2]["pages"], [])
        self.assertLess(scenes[-2]["durationInFrames"], 2 * FPS)
        chart = scenes[1]["custom"]["data"]
        self.assertEqual(len(chart["candles"]), 300 + replay.HISTORY)
        self.assertIn("1분봉", chart["header"])
        self.assertGreater(len(chart["head"]), chart["fills"][-1]["at"])  # 프레임마다 차트 위치
        self.assertEqual([f["side"] for f in chart["fills"]], ["BUY", "SELL"])
        self.assertEqual(chart["fills"][1]["tone"], "win")
        self.assertEqual(chart["summary"]["label"], "+3.10 USDT")
        self.assertGreater(scenes[2]["custom"]["data"]["startFrame"], chart["startFrame"])
        self.assertAlmostEqual(chart["summary"]["at"], scenes[3]["custom"]["data"]["startFrame"] + replay.RESULT_DELAY * FPS, delta=1)
        # 체결은 그 장면이 화면에 나온 뒤에 찍힌다
        self.assertGreaterEqual(chart["fills"][0]["at"], chart["startFrame"])

    def test_falls_back_to_server_interval(self):
        asked = []

        def fetch(exchange, symbol, interval, start, end):
            asked.append(interval)
            if interval == "1m":
                raise candles.CandleError("1분봉을 받지 못했어요")
            return series()

        r = Renderer(find_font(), find_font(mono=True), "silent", music="none", use_remotion=True, fetch_candles=fetch)
        with TemporaryDirectory() as tmp, mock.patch.object(remotion, "render", _fake_remotion):
            r.render(Script.from_job(self.job()), Path(tmp) / "r.mp4")
        self.assertEqual(asked, ["1m", "5m"])
        self.assertIn("5분봉", _fake_remotion.props["scenes"][1]["custom"]["data"]["header"])

    def test_replay_uses_the_fast_music(self):
        r = Renderer(find_font(), find_font(mono=True), "silent", use_remotion=True)
        with mock.patch.object(trade_bgm, "path", return_value=Path("/x/bgm-trade.wav")), \
                mock.patch.object(bgm, "path", return_value=Path("/x/bgm.wav")):
            self.assertEqual(r._music(True).name, "bgm-trade.wav")
            self.assertEqual(r._music(False).name, "bgm.wav")

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
    """체결 효과음 순서표 (#265): 렌더 방식이 바뀌어도 쓰는 데이터"""

    def test_cues_follow_fills_and_result(self):
        r = replay.Replay("BINANCE", "BTCUSDT", "비트코인", "USDT", "PAPER", "5m",
                          [replay.Fill("BUY", 100.0, 600), replay.Fill("SELL", 110.0, 1500, 10.0)],
                          [Candle(i * 300, 100, 101, 99, 100) for i in range(8)])
        starts = [0.0, 3.0, 6.0, 9.0, 12.0]
        cues = replay.cues(r, starts, 15.0)
        # 음악이 끌고 가므로 캔들 틱·바람 소리 없이 체결과 결산만
        self.assertEqual([c.kind for c in cues], ["buy", "sell", "win"])
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
