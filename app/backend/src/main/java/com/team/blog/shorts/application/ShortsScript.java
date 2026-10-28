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
    /** 장면 16개까지. 권하는 길이는 SECONDS_TARGET_MIN~SECONDS_TARGET_MAX(#431) */
    public static final int SCENES_MAX = 16;
    public static final int SCENES_MIN_RENDER = 3;
    public static final int CAPTION_MAX = 40;
    public static final int NARRATION_MAX = 200;
    public static final int CODE_MAX = 300;
    public static final int SECONDS_MAX = 90;
    /**
     * 대본을 쓰는 AI에게 권하는 길이(초). 벗어나도 만들 수는 있다.
     * #431(2026-10-20 블로그 주인 결정): 평균 시청이 20~44초이고 4~7초에 절반이 넘겨서 1분~1분 30초(#277)에서 30~45초로 줄였다.
     */
    public static final int SECONDS_TARGET_MIN = 30;
    public static final int SECONDS_TARGET_MAX = 45;
    /**
     * AI가 MCP로 쓰는 대본의 상한(초). 권장 길이를 안내만 했더니 AI 대본이 모두 77~90초로 나왔고,
     * 2026-10-28 스튜디오 수치(피드 계속 시청 7.8~9.8%, 평균 시청 0:20~0:22)를 보고 블로그 주인이 바로 줄이기로 했다.
     * 사람이 스튜디오에서 쓰는 대본은 SECONDS_MAX까지 그대로 둔다.
     */
    public static final int AI_SECONDS_MAX = 50;
    /** 서버가 직접 짓는 대본(매매 재현·매매 일지)은 예전처럼 60초 안으로 줄인다 */
    public static final int AUTO_SECONDS_MAX = 60;
    /** 한국어 TTS는 초당 6~7자쯤 읽는다. 장면이 바뀔 때 숨 쉬는 틈을 더한다 */
    static final double CHARS_PER_SECOND = 6.5;
    static final double SCENE_GAP_SECONDS = 0.6;

    public static final int IMAGE_MAX = 500;
    public static final int EFFECTS_MAX = 3;
    /** 렌더 워커(#277)가 아는 장면 연출. 비우면 워커가 자막·내레이션·코드에서 고른다 */
    public static final List<String> VISUALS = List.of("explain", "shock", "typing", "panic", "restore", "wait", "fast", "idea", "protect", "think", "celebrate");
    /** 워커에 내장된 효과음 이름. 이 밖의 이름은 받지 않는다 */
    public static final List<String> SOUNDS = List.of("pop", "ding", "whoosh", "boing", "tick", "typing");
    /** 연기할 캐릭터(#304). 렌더 워커 remotion/src/character/ 폴더 이름과 Noto 고양이(cat). 비우면 워커 기본(로그냥) */
    public static final List<String> CHARACTERS = List.of("lognyang", "logduck", "dubu", "cat");

    /** 검사 결과에 쓰는 칸 이름·코드 */
    private static final String TITLE = "title";
    private static final String SCENES = "scenes";
    private static final String SCENE_AT = "scenes[";
    private static final String REQUIRED = "REQUIRED";
    private static final String TOO_LONG = "TOO_LONG";
    private static final String INVALID = "INVALID";
    private static final String UP_TO = "자까지예요.";

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
    private static final String STACK = "stack";
    private static final String COMPARE = "compare";
    private static final String DEFINE = "define";
    private static final String NUMBER = "number";
    private static final String ITEMS = "items";
    public static final List<String> DIAGRAM_TYPES = List.of("flow", STACK, "steps", COMPARE, "timeline", DEFINE, NUMBER);
    static final java.util.Map<String, Integer> DIAGRAM_ITEMS_MAX = java.util.Map.of("flow", 4, STACK, 5, "steps", 5, COMPARE, 4, "timeline", 5, DEFINE, 3);
    public static final int DIAGRAM_LABEL_MAX = 20;
    /** number 도식: 단위 글자 수와 숫자 크기(워커 diagram.py UNIT_MAX·NUMBER_MAX와 같다) */
    static final int DIAGRAM_UNIT_MAX = 8;
    static final double DIAGRAM_NUMBER_MAX = 1e9;

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

    /** 캐릭터 이름을 소문자로 다듬는다. 비어 있으면 null(워커 기본 캐릭터) */
    public static String cleanCharacter(String raw) {
        String c = raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
        return c.isEmpty() ? null : c;
    }

    /** 모르는 캐릭터면 고칠 점 하나, 아니면 빈 목록 */
    public static List<FieldErrorItem> checkCharacter(String character) {
        if (character == null || CHARACTERS.contains(character)) return List.of();
        return List.of(new FieldErrorItem("character", INVALID, "캐릭터는 " + String.join("·", CHARACTERS) + " 가운데 하나예요."));
    }

    /** 저장할 수 있는가. 비어 있지 않은 항목의 길이와 장면 수 상한만 본다 */
    public static List<FieldErrorItem> checkSave(String title, List<Scene> scenes) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (title.isEmpty()) errors.add(new FieldErrorItem(TITLE, REQUIRED, "제목을 적어 주세요."));
        else if (title.length() > TITLE_MAX) errors.add(new FieldErrorItem(TITLE, TOO_LONG, "제목은 " + TITLE_MAX + "자까지 쓸 수 있어요."));
        if (scenes.size() > SCENES_MAX) errors.add(new FieldErrorItem(SCENES, "TOO_MANY", "장면은 " + SCENES_MAX + "개까지예요."));
        for (int i = 0; i < scenes.size(); i++) addSceneErrors(i, scenes.get(i), errors);
        return errors;
    }

    private static void addSceneErrors(int i, Scene s, List<FieldErrorItem> errors) {
        String at = SCENE_AT + i + "].";
        if (s.caption().length() > CAPTION_MAX) errors.add(new FieldErrorItem(at + "caption", TOO_LONG, (i + 1) + "번 장면 자막은 " + CAPTION_MAX + UP_TO));
        if (s.narration().length() > NARRATION_MAX) errors.add(new FieldErrorItem(at + "narration", TOO_LONG, (i + 1) + "번 장면 내레이션은 " + NARRATION_MAX + UP_TO));
        if (s.code() != null && s.code().length() > CODE_MAX) errors.add(new FieldErrorItem(at + "code", TOO_LONG, (i + 1) + "번 장면 코드는 " + CODE_MAX + UP_TO));
        if (s.visual() != null && !VISUALS.contains(s.visual())) {
            errors.add(new FieldErrorItem(at + "visual", INVALID, (i + 1) + "번 장면 연출은 " + String.join("·", VISUALS) + " 중 하나예요."));
        }
        if (s.effects().size() > EFFECTS_MAX) errors.add(new FieldErrorItem(at + "effects", "TOO_MANY", (i + 1) + "번 장면 효과음은 " + EFFECTS_MAX + "개까지예요."));
        String diagramError = diagramError(s.diagram());
        if (diagramError != null) errors.add(new FieldErrorItem(at + "diagram", INVALID, (i + 1) + "번 장면 설명 그림: " + diagramError));
        if (s.effects().stream().anyMatch(ShortsScript::invalidEffect)) {
            errors.add(new FieldErrorItem(at + "effects", INVALID, (i + 1) + "번 장면 효과음은 " + String.join("·", SOUNDS)
                    + " 중 하나, 시각(at)은 0~" + SECONDS_MAX + "초, 크기(volume)는 0~1이에요."));
        }
    }

    private static boolean invalidEffect(Effect e) {
        return !SOUNDS.contains(e.sound()) || e.at() < 0 || e.at() > SECONDS_MAX || (e.volume() != null && (e.volume() < 0 || e.volume() > 1));
    }

    /** 영상으로 만들 수 있는가. 저장 규칙에 더해 장면 수·빈 칸·예상 길이를 본다 */
    public static List<FieldErrorItem> checkRender(String title, List<Scene> scenes) {
        List<FieldErrorItem> errors = new ArrayList<>(checkSave(title, scenes));
        if (scenes.size() < SCENES_MIN_RENDER) errors.add(new FieldErrorItem(SCENES, "TOO_FEW", "장면이 " + SCENES_MIN_RENDER + "개 이상 있어야 영상으로 만들 수 있어요."));
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            if (s.caption().isEmpty()) errors.add(new FieldErrorItem(SCENE_AT + i + "].caption", REQUIRED, (i + 1) + "번 장면에 자막이 없어요."));
            if (s.narration().isEmpty()) errors.add(new FieldErrorItem(SCENE_AT + i + "].narration", REQUIRED, (i + 1) + "번 장면에 내레이션이 없어요."));
        }
        double seconds = estimateSeconds(scenes);
        if (seconds > SECONDS_MAX) {
            errors.add(new FieldErrorItem(SCENES, TOO_LONG, "예상 길이가 " + Math.round(seconds) + "초예요. " + SECONDS_MAX + "초 안으로 줄여 주세요."));
        }
        return errors;
    }

    /** 워커가 그릴 수 있는 모양인지만 본다(type·칸 수·이름표). 아이콘·메모 같은 나머지는 워커가 다듬는다. 맞으면 null */
    static String diagramError(java.util.Map<String, Object> d) {
        if (d == null) return null;
        Object type = d.get("type");
        if (!(type instanceof String t) || !DIAGRAM_TYPES.contains(t)) return "type은 " + String.join("·", DIAGRAM_TYPES) + " 중 하나예요.";
        if (t.equals(NUMBER)) return numberError(d);
        int max = DIAGRAM_ITEMS_MAX.get(t);
        if (t.equals(COMPARE)) return compareError(d, max);
        if (t.equals(DEFINE) && !validLabel(d.get("term"))) return "define은 term(정의할 말)이 1~" + DIAGRAM_LABEL_MAX + "자로 있어야 해요.";
        String e = itemsError(d.get(ITEMS), t.equals(DEFINE) ? 1 : 2, max);
        if (e != null) return e;
        if (t.equals(STACK)) return highlightError(d);
        return null;
    }

    private static String compareError(java.util.Map<String, Object> d, int max) {
        for (String side : List.of("left", "right")) {
            if (!(d.get(side) instanceof java.util.Map<?, ?> m) || !validLabel(m.get(TITLE))) return "compare는 left·right에 title과 items가 있어야 해요.";
            String e = itemsError(m.get(ITEMS), 1, max);
            if (e != null) return e;
        }
        return null;
    }

    /** number: 이름표(label)를 말할 때 from에서 to로 숫자가 바뀐다. 블로그 글에 있는 실제 숫자여야 한다 */
    private static String numberError(java.util.Map<String, Object> d) {
        if (!validLabel(d.get("label"))) return "number는 label(무슨 숫자인지)이 1~" + DIAGRAM_LABEL_MAX + "자로 있어야 해요.";
        for (String key : List.of("from", "to")) {
            if (!(d.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || Math.abs(n.doubleValue()) > DIAGRAM_NUMBER_MAX) {
                return "number의 from·to는 숫자예요(절댓값 10억 이하).";
            }
        }
        Object unit = d.get("unit");
        if (unit != null && !(unit instanceof String u && u.strip().length() <= DIAGRAM_UNIT_MAX)) return "unit은 " + DIAGRAM_UNIT_MAX + "자 이하 글자예요.";
        return null;
    }

    /** stack의 강조 칸(선택). 칸 수는 itemsError가 먼저 확인했다 */
    private static String highlightError(java.util.Map<String, Object> d) {
        Object highlight = d.get("highlight");
        if (highlight == null) return null;
        int n = ((List<?>) d.get(ITEMS)).size();
        if (!(highlight instanceof Integer h) || h < 0 || h >= n) return "highlight는 0부터 센 칸 번호예요.";
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
