import unittest

from devlog_render import captions, diagram
from devlog_render.captions import Word
from devlog_render.render import Script


class ParseTest(unittest.TestCase):
    def test_strings_and_dicts_become_items(self):
        d = diagram.parse({"type": "flow", "items": ["브라우저", {"label": "DB", "icon": "database", "note": "디스크"}, 3]})
        self.assertEqual(d["items"], [{"label": "브라우저", "icon": None, "note": None}, {"label": "DB", "icon": "database", "note": "디스크"}])

    def test_bad_shapes_are_dropped(self):
        self.assertIsNone(diagram.parse({"type": "pie", "items": ["a", "b"]}))
        self.assertIsNone(diagram.parse({"type": "flow", "items": ["하나"]}))
        self.assertIsNone(diagram.parse({"type": "compare", "left": {"title": "전", "items": ["a"]}}))
        self.assertIsNone(diagram.parse("flow"))

    def test_limits_and_highlight(self):
        d = diagram.parse({"type": "stack", "items": list("abcdefg"), "highlight": 9})
        self.assertEqual(len(d["items"]), 5)
        self.assertIsNone(d["highlight"])
        self.assertEqual(diagram.parse({"type": "stack", "items": ["a", "b"], "highlight": 1})["highlight"], 1)

    def test_job_reads_diagram(self):
        script = Script.from_job({"title": "t", "scenes": [
            {"caption": "길", "narration": "서버로 가요.", "diagram": {"type": "steps", "items": ["하나", "둘"]}},
            {"caption": "없음", "narration": "그냥요."},
        ]})
        self.assertEqual(script.scenes[0].diagram["type"], "steps")
        self.assertIsNone(script.scenes[1].diagram)


class TimingTest(unittest.TestCase):
    def test_items_appear_when_their_name_is_spoken(self):
        words = [Word(0.0, 0.5, "브라우저가"), Word(0.5, 1.0, "보내면"), Word(1.2, 1.6, "서버가"), Word(2.0, 2.4, "DB에서"), Word(2.4, 3.0, "꺼내요")]
        text = "브라우저가 보내면 서버가 DB에서 꺼내요"
        d = diagram.timed(diagram.parse({"type": "flow", "items": ["브라우저", "서버", "DB"]}), captions.pages(text, 3.0, words), 3.0)
        self.assertEqual([x["atMs"] for x in d["items"]], [0, 1200, 2000])

    def test_unspoken_items_are_spread_in_order(self):
        d = diagram.timed(diagram.parse({"type": "steps", "items": ["가", "나", "다"]}), captions.pages("아무 말이나 해요", 3.0), 3.0)
        times = [x["atMs"] for x in d["items"]]
        self.assertEqual(times, sorted(times))
        self.assertLessEqual(times[-1], 2100)

    def test_compare_sides_are_timed_in_reading_order(self):
        text = "캐시가 없으면 매번 DB를 읽지만, 캐시가 있으면 메모리에서 바로 꺼내요."
        d = diagram.parse({"type": "compare", "left": {"title": "캐시 없음", "items": ["매번 DB"]}, "right": {"title": "캐시 있음", "items": ["메모리"]}})
        t = diagram.timed(d, captions.pages(text, 5.0), 5.0)
        order = diagram.reveal_times(t)
        self.assertLess(t["left"]["atMs"], t["right"]["atMs"])
        self.assertLess(t["right"]["atMs"], t["right"]["items"][0]["atMs"])
        self.assertEqual(len(order), 4)


if __name__ == "__main__":
    unittest.main()
