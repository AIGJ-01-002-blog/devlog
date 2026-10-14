"""무료 한국어 음성 후보 비교(#277): 같은 문장을 후보마다 읽혀 out/voices/에 mp3로 남긴다.

후보 하나가 설치·다운로드에 실패해도 나머지는 계속 만든다.
CI에서 한 번 돌려 비교했다(PR #278). 다시 비교하려면: python tools/compare_voices.py edge|melo|supertonic|gtts out/voices
"""

import asyncio
import subprocess
import sys
import traceback
from pathlib import Path

TEXT = ("어제 서버를 정리하다가, 블로그 글이 전부 사라졌어요. 범인은 명령어 한 줄이었어요. "
        "compose down 뒤에 -v를 붙이면, 컨테이너뿐 아니라 데이터가 든 볼륨까지 함께 지워져요.")


def mp3(src: Path, dst: Path) -> None:
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(src), "-af", "loudnorm=I=-16", "-b:a", "128k", str(dst)], check=True)
    print("만듦:", dst)


def edge(out: Path) -> None:
    import edge_tts

    async def one(voice: str, rate: str) -> None:
        tmp = out / f"{voice}.raw.mp3"
        await edge_tts.Communicate(TEXT, voice, rate=rate).save(str(tmp))
        mp3(tmp, out / f"edge-{voice}{'' if rate == '+0%' else '-fast'}.mp3")
        tmp.unlink()

    for voice in ["ko-KR-SunHiNeural", "ko-KR-InJoonNeural", "ko-KR-HyunsuMultilingualNeural"]:
        for rate in ["+0%", "+10%"]:
            try:
                asyncio.run(one(voice, rate))
            except Exception:
                traceback.print_exc()


def melo(out: Path) -> None:
    from melo.api import TTS

    model = TTS(language="KR", device="cpu")
    for speed in (1.0, 1.15):
        tmp = out / "melo.wav"
        model.tts_to_file(TEXT, model.hps.data.spk2id["KR"], str(tmp), speed=speed)
        mp3(tmp, out / f"melo-KR{'' if speed == 1.0 else '-fast'}.mp3")
        tmp.unlink()


def supertonic(out: Path) -> None:
    import supertonic

    print("supertonic 모듈:", [n for n in dir(supertonic) if not n.startswith("_")])
    tts = supertonic.TTS(auto_download=True)
    for name in ["F1", "F2", "M1", "M2"]:
        try:
            style = tts.get_voice_style(voice_name=name)
            wav, _ = tts.synthesize(TEXT, voice_style=style, lang="ko")
            tmp = out / f"supertonic-{name}.wav"
            tts.save_audio(wav, str(tmp))
            mp3(tmp, out / f"supertonic-{name}.mp3")
            tmp.unlink()
        except Exception:
            traceback.print_exc()


def gtts(out: Path) -> None:
    from gtts import gTTS

    tmp = out / "gtts.raw.mp3"
    gTTS(TEXT, lang="ko").save(str(tmp))
    mp3(tmp, out / "gtts.mp3")
    tmp.unlink()


if __name__ == "__main__":
    engine, folder = sys.argv[1], Path(sys.argv[2])
    folder.mkdir(parents=True, exist_ok=True)
    {"edge": edge, "melo": melo, "supertonic": supertonic, "gtts": gtts}[engine](folder)
