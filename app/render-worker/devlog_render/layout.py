"""화면 배치와 글자 줄바꿈. 글꼴 크기를 재지 않고 글자 폭을 어림한다(한글·한자는 1칸, 영문·숫자는 0.55칸)."""

from __future__ import annotations

import unicodedata

WIDTH = 1080
HEIGHT = 1920


def char_width(ch: str) -> float:
    if ch == "\t":
        return 2.0
    if unicodedata.east_asian_width(ch) in ("W", "F"):
        return 1.0
    return 0.55


def text_width(text: str) -> float:
    return sum(char_width(c) for c in text)


def wrap(text: str, max_units: float, max_lines: int | None = None) -> list[str]:
    """단어(띄어쓰기) 단위로 줄을 나누고, 한 단어가 한 줄보다 길면 글자 단위로 자른다.
    max_lines를 넘으면 마지막 줄 끝을 …로 줄인다."""
    lines: list[str] = []
    for paragraph in text.split("\n"):
        line = ""
        for word in paragraph.split(" "):
            candidate = word if not line else line + " " + word
            if text_width(candidate) <= max_units:
                line = candidate
                continue
            if line:
                lines.append(line)
                line = ""
            while text_width(word) > max_units:
                cut = ""
                for ch in word:
                    if text_width(cut + ch) > max_units:
                        break
                    cut += ch
                lines.append(cut)
                word = word[len(cut):]
            line = word
        lines.append(line)
    while lines and not lines[-1]:
        lines.pop()
    if max_lines is not None and len(lines) > max_lines:
        lines = lines[:max_lines]
        last = lines[-1]
        while last and text_width(last + "…") > max_units:
            last = last[:-1]
        lines[-1] = last + "…"
    return lines


def code_lines(code: str, max_units: float = 40, max_lines: int = 10) -> list[str]:
    """코드는 줄바꿈을 지키고, 긴 줄은 자르기만 한다(코드 모양을 바꾸지 않게)."""
    out = []
    for raw in code.replace("\t", "  ").split("\n")[:max_lines]:
        line = raw.rstrip()
        while text_width(line) > max_units:
            line = line[:-1]
        out.append(line)
    return out
