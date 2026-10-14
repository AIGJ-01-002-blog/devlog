import unittest

from devlog_render import layout, tts


class LayoutTest(unittest.TestCase):
    def test_wraps_korean_by_words(self):
        lines = layout.wrap("도커 볼륨이 지워져서 데이터가 사라졌어요", 10)
        self.assertTrue(all(layout.text_width(l) <= 10 for l in lines))
        self.assertEqual(" ".join(lines), "도커 볼륨이 지워져서 데이터가 사라졌어요")

    def test_cuts_long_word_and_limits_lines(self):
        lines = layout.wrap("가" * 50, 10, 2)
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[-1].endswith("…"))
        self.assertLessEqual(layout.text_width(lines[-1]), 10)

    def test_code_keeps_line_breaks(self):
        lines = layout.code_lines("if (x) {\n\treturn y;\n}", 40)
        self.assertEqual(lines, ["if (x) {", "  return y;", "}"])

    def test_tts_pick_respects_explicit_engine(self):
        self.assertEqual(tts.pick("silent"), "silent")
        self.assertIn(tts.pick("auto"), ("say", "windows", "espeak", "silent"))

    def test_estimate_has_floor(self):
        self.assertEqual(tts.estimate_seconds(""), 1.5)
        self.assertAlmostEqual(tts.estimate_seconds("가" * 13), 2.0)


if __name__ == "__main__":
    unittest.main()
