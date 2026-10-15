package com.team.blog.shorts.application;

import java.util.ArrayList;
import java.util.List;

import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.text.TextCleaner;

/**
 * 쇼츠 대본 (099 FR-002). 장면마다 화면 자막(caption), 읽을 문장(narration), 선택으로 짧은 코드 조각(code)을 둔다.
 * 저장할 때는 길이만 보고, 영상 만들기 전에는 장면 수와 예상 길이까지 본다(쓰는 중인 대본도 저장할 수 있게).
 */
public final class ShortsScript {
    public static final int TITLE_MAX = 60;
    public static final int SCENES_MAX = 10;
    public static final int SCENES_MIN_RENDER = 3;
    public static final int CAPTION_MAX = 40;
    public static final int NARRATION_MAX = 200;
    public static final int CODE_MAX = 300;
    public static final int SECONDS_MAX = 60;
    /** 한국어 TTS는 초당 6~7자쯤 읽는다. 장면이 바뀔 때 숨 쉬는 틈을 더한다 */
    static final double CHARS_PER_SECOND = 6.5;
    static final double SCENE_GAP_SECONDS = 0.6;

    public static final int IMAGE_MAX = 500;
    public static final int EFFECTS_MAX = 3;
    /** 렌더 워커(#277)가 아는 장면 연출. 비우면 워커가 자막·내레이션·코드에서 고른다 */
    public static final List<String> VISUALS = List.of("explain", "shock", "typing", "panic", "restore", "wait", "fast", "idea", "protect", "think", "celebrate");
    /** 워커에 내장된 효과음 이름. 이 밖의 이름은 받지 않는다 */
    public static final List<String> SOUNDS = List.of("pop", "ding", "whoosh", "boing", "tick", "typing");

    /** 장면 안 효과음. at은 장면이 시작하고 몇 초 뒤, volume은 0~1(비우면 워커 기본값) */
    public record Effect(String sound, double at, Double volume) {
        public Effect {
            sound = sound == null ? "" : sound.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * image: 장면 배경으로 깔 글 그림 (선택, #277 렌더 워커). devlog에 올린 그림 주소나 "/"로 시작하는 사이트 경로만 받는다.
     * visual: 장면 연출 이름 (선택, VISUALS). effects: 장면 안 효과음 (선택, EFFECTS_MAX개까지). 워커에는 작업 단위 effects[]로 펼쳐 보낸다.
     */
    /** 설명 도식(#277 렌더 워커 diagram.py): flow·stack·steps·compare. 칸 수 상한과 이름표 길이 */
    public static final List<String> DIAGRAM_TYPES = List.of("flow", "stack", "steps", "compare");
    static final java.util.Map<String, Integer> DIAGRAM_ITEMS_MAX = java.util.Map.of("flow", 4, "stack", 5, "steps", 5, "compare", 4);
    public static final int DIAGRAM_LABEL_MAX = 20;

    /**
     * diagram: CS 개념을 풀어 줄 설명 도식 (선택). 워커에 그대로 넘긴다. 예 {"type":"flow","items":["브라우저","서버",{"label":"DB","icon":"database"}]}
     */
    public record Scene(String caption, String narration, String code, String image, String visual, List<Effect> effects,
                        java.util.Map<String, Object> diagram) {
        public Scene {
            caption = TextCleaner.cleanLine(caption);
            narration = TextCleaner.cleanLine(narration);
            code = code == null || code.isBlank() ? null : TextCleaner.cleanMultiline(code);
            image = image == null || image.isBlank() ? null : image.strip();
            visual = visual == null || visual.isBlank() ? null : visual.strip().toLowerCase(java.util.Locale.ROOT);
            effects = effects == null ? List.of() : effects.stream().filter(java.util.Objects::nonNull).toList();
        }

        public Scene(String caption, String narration, String code) {
            this(caption, narration, code, null, null, null, null);
        }
    }

    private ShortsScript() {}

    public static String cleanTitle(String raw) {
        return TextCleaner.cleanLine(raw);
    }

    /** 저장할 수 있는가. 비어 있지 않은 항목의 길이와 장면 수 상한만 본다 */
    public static List<FieldErrorItem> checkSave(String title, List<Scene> scenes) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (title.isEmpty()) errors.add(new FieldErrorItem("title", "REQUIRED", "제목을 적어 주세요."));
        else if (title.length() > TITLE_MAX) errors.add(new FieldErrorItem("title", "TOO_LONG", "제목은 " + TITLE_MAX + "자까지 쓸 수 있어요."));
        if (scenes.size() > SCENES_MAX) errors.add(new FieldErrorItem("scenes", "TOO_MANY", "장면은 " + SCENES_MAX + "개까지예요."));
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            String at = "scenes[" + i + "].";
            if (s.caption().length() > CAPTION_MAX) errors.add(new FieldErrorItem(at + "caption", "TOO_LONG", (i + 1) + "번 장면 자막은 " + CAPTION_MAX + "자까지예요."));
            if (s.narration().length() > NARRATION_MAX) errors.add(new FieldErrorItem(at + "narration", "TOO_LONG", (i + 1) + "번 장면 내레이션은 " + NARRATION_MAX + "자까지예요."));
            if (s.code() != null && s.code().length() > CODE_MAX) errors.add(new FieldErrorItem(at + "code", "TOO_LONG", (i + 1) + "번 장면 코드는 " + CODE_MAX + "자까지예요."));
            if (s.visual() != null && !VISUALS.contains(s.visual())) {
                errors.add(new FieldErrorItem(at + "visual", "INVALID", (i + 1) + "번 장면 연출은 " + String.join("·", VISUALS) + " 중 하나예요."));
            }
            if (s.effects().size() > EFFECTS_MAX) errors.add(new FieldErrorItem(at + "effects", "TOO_MANY", (i + 1) + "번 장면 효과음은 " + EFFECTS_MAX + "개까지예요."));
            String diagramError = diagramError(s.diagram());
            if (diagramError != null) errors.add(new FieldErrorItem(at + "diagram", "INVALID", (i + 1) + "번 장면 설명 그림: " + diagramError));
            for (Effect e : s.effects()) {
                if (!SOUNDS.contains(e.sound()) || e.at() < 0 || e.at() > SECONDS_MAX || (e.volume() != null && (e.volume() < 0 || e.volume() > 1))) {
                    errors.add(new FieldErrorItem(at + "effects", "INVALID", (i + 1) + "번 장면 효과음은 " + String.join("·", SOUNDS)
                            + " 중 하나, 시각(at)은 0~" + SECONDS_MAX + "초, 크기(volume)는 0~1이에요."));
                    break;
                }
            }
        }
        return errors;
    }

    /** 영상으로 만들 수 있는가. 저장 규칙에 더해 장면 수·빈 칸·예상 길이를 본다 */
    public static List<FieldErrorItem> checkRender(String title, List<Scene> scenes) {
        List<FieldErrorItem> errors = new ArrayList<>(checkSave(title, scenes));
        if (scenes.size() < SCENES_MIN_RENDER) errors.add(new FieldErrorItem("scenes", "TOO_FEW", "장면이 " + SCENES_MIN_RENDER + "개 이상 있어야 영상으로 만들 수 있어요."));
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            if (s.caption().isEmpty()) errors.add(new FieldErrorItem("scenes[" + i + "].caption", "REQUIRED", (i + 1) + "번 장면에 자막이 없어요."));
            if (s.narration().isEmpty()) errors.add(new FieldErrorItem("scenes[" + i + "].narration", "REQUIRED", (i + 1) + "번 장면에 내레이션이 없어요."));
        }
        double seconds = estimateSeconds(scenes);
        if (seconds > SECONDS_MAX) {
            errors.add(new FieldErrorItem("scenes", "TOO_LONG", "예상 길이가 " + Math.round(seconds) + "초예요. " + SECONDS_MAX + "초 안으로 줄여 주세요."));
        }
        return errors;
    }

    /** 워커가 그릴 수 있는 모양인지만 본다(type·칸 수·이름표). 아이콘·메모 같은 나머지는 워커가 다듬는다. 맞으면 null */
    static String diagramError(java.util.Map<String, Object> d) {
        if (d == null) return null;
        Object type = d.get("type");
        if (!(type instanceof String t) || !DIAGRAM_TYPES.contains(t)) return "type은 " + String.join("·", DIAGRAM_TYPES) + " 중 하나예요.";
        int max = DIAGRAM_ITEMS_MAX.get(t);
        if (t.equals("compare")) {
            for (String side : List.of("left", "right")) {
                if (!(d.get(side) instanceof java.util.Map<?, ?> m) || !validLabel(m.get("title"))) return "compare는 left·right에 title과 items가 있어야 해요.";
                String e = itemsError(m.get("items"), 1, max);
                if (e != null) return e;
            }
            return null;
        }
        String e = itemsError(d.get("items"), 2, max);
        if (e != null) return e;
        if (t.equals("stack") && d.get("highlight") != null) {
            int n = ((List<?>) d.get("items")).size();
            if (!(d.get("highlight") instanceof Integer h) || h < 0 || h >= n) return "highlight는 0부터 센 칸 번호예요.";
        }
        return null;
    }

    private static String itemsError(Object raw, int min, int max) {
        if (!(raw instanceof List<?> items) || items.size() < min || items.size() > max) return "칸은 " + min + "~" + max + "개예요.";
        for (Object it : items) {
            Object label = it instanceof java.util.Map<?, ?> m ? m.get("label") : it;
            if (!validLabel(label)) return "이름표는 1~" + DIAGRAM_LABEL_MAX + "자예요.";
        }
        return null;
    }

    private static boolean validLabel(Object label) {
        return label instanceof String l && !l.isBlank() && l.strip().length() <= DIAGRAM_LABEL_MAX;
    }

    public static double estimateSeconds(List<Scene> scenes) {
        double total = 0;
        for (Scene s : scenes) total += s.narration().codePointCount(0, s.narration().length()) / CHARS_PER_SECOND + SCENE_GAP_SECONDS;
        return total;
    }
}
