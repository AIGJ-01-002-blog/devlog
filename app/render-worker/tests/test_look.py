"""영상마다 다른 연출(look.py): 같은 대본이면 같은 연출, 대본이 다르면 영상끼리 달라 보이게 고르는지 확인한다."""

import unittest

from devlog_render import bgm, look
from devlog_render.render import Renderer, Scene, Script


def job(title, narration="백업으로 다 되돌렸어요.", **extra):
    return {"id": 3, "title": title, "scenes": [{"caption": "요점", "narration": f"{narration} {k}"} for k in range(5)], **extra}


class LookTest(unittest.TestCase):
    def test_same_script_same_look_and_edit_changes_seed(self):
        a, b = Script.from_job(job("도커 볼륨")), Script.from_job(job("도커 볼륨"))
        self.assertEqual(a.seed, b.seed)
        self.assertEqual(look.choose(a.seed, 6, [True] * 6), look.choose(b.seed, 6, [True] * 6))
        self.assertNotEqual(a.seed, Script.from_job(job("도커 볼륨", "백업으로 되돌렸어요.")).seed)

    def test_many_videos_use_many_looks_music_and_transitions(self):
        looks = [look.choose(look.seed_of(k, "제목"), 6, [True] * 6) for k in range(60)]
        self.assertEqual({lk.name for lk in looks}, set(look.LOOKS))
        self.assertEqual({lk.music for lk in looks}, set(range(look.MUSIC_VARIANTS)))
        self.assertGreater(len({tuple(lk.transitions) for lk in looks}), 40)

    def test_scenes_do_not_repeat_transition_or_camera_back_to_back(self):
        for k in range(30):
            lk = look.choose(look.seed_of("x", k), 8, [True] * 8)
            self.assertEqual(lk.transitions[0], "")
            for i in range(2, 8):
                self.assertNotEqual(lk.transitions[i], lk.transitions[i - 1])
            for i in range(1, 8):
                self.assertNotEqual(lk.cameras[i], lk.cameras[i - 1])
            self.assertTrue(all(t in look.TRANSITIONS for t in lk.transitions[1:]))
            # 같은 배치가 세 번 이어지지 않는다
            for i in range(2, 8):
                self.assertFalse(lk.layouts[i] == lk.layouts[i - 1] == lk.layouts[i - 2] == "split")

    def test_custom_scenes_stay_on_stage_and_cut_is_silent(self):
        lk = look.choose(1, 4, [True, False, True, False])
        self.assertEqual(lk.layouts[1], "stage")
        self.assertEqual(lk.layouts[3], "stage")
        lk.transitions = ["", "cut", "fade", "slide-right"]
        self.assertIsNone(lk.sound(1))
        self.assertIsNone(lk.sound(2))
        self.assertEqual(lk.sound(3), "whip")

    def test_named_look_and_replay_use_fixed_look(self):
        self.assertEqual(look.choose(5, 3, [True] * 3, "night").name, "night")
        self.assertEqual(Script.from_job(job("t", look="mono")).look, "mono")
        self.assertIsNone(Script.from_job(job("t", look="모르는룩")).look)
        replay = Script("매매", [Scene("a", "b")], seed=9)
        self.assertEqual(Renderer._look(replay, True).name, "paper")

    def test_fast_style_puts_character_scenes_on_split(self):
        script = Script("t", [Scene("a", "b"), Scene("c", "d", custom={"kind": "outro", "data": None})], style="fast", seed=2)
        self.assertEqual(Renderer._look(script, False).layouts, ["split", "stage"])


class MusicVariantTest(unittest.TestCase):
    def test_variants_differ_and_first_is_original(self):
        self.assertEqual(bgm.VARIANTS[0]["arp"], bgm.ARP_UP_DOWN)
        heads = {tuple(round(v, 4) for v in bgm.samples(k)[1000:1400:7]) for k in range(len(bgm.VARIANTS))}
        self.assertEqual(len(heads), len(bgm.VARIANTS))
        self.assertEqual(len(bgm.VARIANTS), look.MUSIC_VARIANTS)


if __name__ == "__main__":
    unittest.main()
