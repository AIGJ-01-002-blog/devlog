"""구절마다 캐릭터 동작(acting.beats): 말 내용에 맞는 표정을 고르고, 같은 동작이 셋 넘게 이어지지 않는다."""

import unittest

from devlog_render import acting


class ActingBeatsTest(unittest.TestCase):
    def test_mood_follows_words(self):
        self.assertEqual(acting.mood("데이터가 다 사라졌어요"), "shock")
        self.assertEqual(acting.mood("왜 그럴까요?"), "think")
        self.assertIsNone(acting.mood("평범한 문장"))

    def test_beats_vary_and_cover_every_page(self):
        texts = ["왜 느릴까요?", "디스크를", "읽어서", "그래요", "드디어 빨라졌어요", "평범", "평범", "평범"]
        beats = acting.beats(texts)
        self.assertEqual(len(beats), len(texts))
        self.assertEqual(beats[0], "think")
        self.assertTrue(all(b in acting.CLIPS for b in beats))
        for i in range(2, len(beats)):
            self.assertFalse(beats[i] == beats[i - 1] == beats[i - 2])

    def test_empty(self):
        self.assertEqual(acting.beats([]), [])
