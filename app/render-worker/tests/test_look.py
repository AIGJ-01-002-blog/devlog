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

    def test_long_stage_scenes_jump_cut(self):
        # 같은 화면이 6초 넘게 이어지지 않게, 긴 가운데 무대 장면은 구절마다 확대를 번갈아 끊는다
        self.assertEqual(look.hold_camera("pan-left", "stage", "character", 7.0), look.JUMP)
        self.assertEqual(look.hold_camera("zoom-in", "stage", "code", 6.5), look.JUMP)
        self.assertEqual(look.hold_camera("pan-left", "stage", "character", 5.0), "pan-left")
        self.assertEqual(look.hold_camera("still", "stage", "diagram", 9.0), "still")  # 도식은 칸이 스스로 나타난다
        self.assertEqual(look.hold_camera("punch", "split", "character", 9.0), "punch")  # 위 그림·아래 캐릭터는 2초마다 구도가 바뀐다

    def test_custom_scenes_stay_on_stage_and_cut_is_silent(self):
        lk = look.choose(1, 4, [True, False, True, False])
        self.assertEqual(lk.layouts[1], "stage")
        self.assertEqual(lk.layouts[3], "stage")
        lk.transitions = ["", "cut", "fade", "slide-right"]
        self.assertIsNone(lk.sound(1))
        self.assertIsNone(lk.sound(2))
        self.assertEqual(lk.sound(3), "whip")

    def test_recent_looks_and_combos_are_avoided(self):
        recent = ["night:1", "mint:2", "grape:0", "ocean:3", "paper:1"]
        for k in range(40):
            lk = look.choose(look.seed_of("r", k), 5, [True] * 5, avoid=recent)
            self.assertNotIn(lk.name, {"night", "mint", "grape"})
            self.assertNotIn(lk.tag, recent)
            self.assertNotEqual(lk.music, 1)  # 바로 앞 영상(night:1)의 배경음
        # 정해 둔 룩은 그대로 쓰고 배경음만 피한다. 알아볼 수 없는 값은 무시한다
        named = look.choose(3, 4, [True] * 4, "paper", avoid=["paper:0", "paper:1", "bad", "x:y"])
        self.assertEqual(named.name, "paper")
        self.assertIn(named.music, (2, 3))
        # 피할 게 없으면 예전과 같은 연출
        self.assertEqual(look.choose(9, 4, [True] * 4, avoid=[]), look.choose(9, 4, [True] * 4))

    def test_named_look_and_replay_use_fixed_look(self):
        self.assertEqual(look.choose(5, 3, [True] * 3, "night").name, "night")
        self.assertEqual(Script.from_job(job("t", look="mono")).look, "mono")
        self.assertIsNone(Script.from_job(job("t", look="모르는룩")).look)
        replay = Script("매매", [Scene("a", "b")], seed=9)
        self.assertEqual(Renderer._look(replay, True).name, "paper")

    def test_fast_style_puts_character_scenes_on_split(self):
        # 첫 장면(훅)은 커버가 되므로 빠른 스타일에서도 가운데 무대
        script = Script("t", [Scene("a", "b"), Scene("e", "f"), Scene("c", "d", custom={"kind": "outro", "data": None})], style="fast", seed=2)
        self.assertEqual(Renderer._look(script, False).layouts, ["stage", "split", "stage"])
        # 코드 창은 빠른 스타일에서도 가운데 무대
        script = Script("t", [Scene("a", "b"), Scene("e", "f"), Scene("c", "d", code="print(1)")], style="fast", seed=2)
        self.assertEqual(Renderer._look(script, False).layouts, ["stage", "split", "stage"])

    def test_hook_scene_is_always_on_stage(self):
        # 첫 장면 첫 화면이 릴스 커버·쇼츠 썸네일이 된다(조회수 분석 2026-10-20). 흰 카드(split)로 시작하지 않는다
        for k in range(60):
            for name in look.LOOKS:
                lk = look.choose(look.seed_of("hook", k), 6, [True] * 6, name)
                self.assertEqual(lk.layouts[0], "stage")

    def test_one_or_two_events_prefer_surprise_and_skip_hook(self):
        kinds = ["character"] * 6 + ["custom"]
        actions = ["shock", "explain", "explain", "panic", "explain", "explain", ""]
        for k in range(20):
            lk = look.choose(look.seed_of("e", k), 7, [True] * 7, kinds=kinds, actions=actions)
            picked = [i for i, ev in enumerate(lk.events) if ev]
            self.assertEqual(len(picked), 2)
            self.assertNotIn(0, picked)  # 첫 장면(훅)은 빼고
            self.assertNotIn(6, picked)  # 등록된 장면(끝 광고 등)도 빼고
            self.assertIn(3, picked)  # 당황 연기가 먼저
            self.assertTrue(all(lk.events[i] in look.EVENTS for i in picked))
        self.assertEqual(look.choose(1, 2, [True, True]).events, [None, None])

    def test_camera_follows_scene_kind(self):
        kinds = ["code", "diagram", "code", "diagram", "character"]
        for k in range(20):
            lk = look.choose(look.seed_of("c", k), 5, [False] * 5, kinds=kinds)
            self.assertIn(lk.cameras[0], look.CAMERA_BY_KIND["code"])
            self.assertIn(lk.cameras[1], look.CAMERA_BY_KIND["diagram"])
            self.assertIn(lk.cameras[2], look.CAMERA_BY_KIND["code"])


class MusicVariantTest(unittest.TestCase):
    def test_variants_differ_and_first_is_original(self):
        self.assertEqual(bgm.VARIANTS[0]["arp"], bgm.ARP_UP_DOWN)
        heads = {tuple(round(v, 4) for v in bgm.samples(k)[1000:1400:7]) for k in range(len(bgm.VARIANTS))}
        self.assertEqual(len(heads), len(bgm.VARIANTS))
        self.assertEqual(len(bgm.VARIANTS), look.MUSIC_VARIANTS)


if __name__ == "__main__":
    unittest.main()


class StagingTest(unittest.TestCase):
    """민서님 피드백(2026-10-18): 캐릭터 짜임·도식 모양도 장면마다 바뀐다"""

    def test_framings_do_not_repeat_across_character_scenes(self):
        kinds = ["character", "diagram", "character", "character", "code", "character"]
        for k in range(30):
            lk = look.choose(look.seed_of("x", k), 6, [True] * 6, kinds=kinds)
            chars = [lk.framings[i] for i, kind in enumerate(kinds) if kind == "character"]
            self.assertTrue(all(f in look.FRAMINGS for f in chars))
            for a, b in zip(chars, chars[1:]):
                self.assertNotEqual(a, b)

    def test_diagram_variant_and_side_flip_between_diagrams(self):
        kinds = ["diagram", "character", "diagram", "diagram"]
        lk = look.choose(look.seed_of("y", 1), 4, [False] * 4, kinds=kinds)
        self.assertNotEqual(lk.variants[0], lk.variants[2])
        self.assertNotEqual(lk.variants[2], lk.variants[3])
        self.assertEqual(lk.sides[0], -lk.sides[2])

    def test_staging_keeps_old_picks(self):
        # 짜임을 더해도 룩·전환·카메라는 같은 씨앗에서 예전과 같은 것을 고른다
        import random
        seed = look.seed_of("z", 2)
        lk = look.choose(seed, 5, [True] * 5)
        rng = random.Random(seed)
        self.assertEqual(lk.name, look._pick_look(rng, look._recent(None)))
