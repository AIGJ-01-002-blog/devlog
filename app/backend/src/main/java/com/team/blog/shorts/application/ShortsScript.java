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

    /** image: 장면 배경으로 깔 글 그림 (선택, #277 렌더 워커). devlog에 올린 그림 주소나 "/"로 시작하는 사이트 경로만 받는다 */
    public record Scene(String caption, String narration, String code, String image) {
        public Scene {
            caption = TextCleaner.cleanLine(caption);
            narration = TextCleaner.cleanLine(narration);
            code = code == null || code.isBlank() ? null : TextCleaner.cleanMultiline(code);
            image = image == null || image.isBlank() ? null : image.strip();
        }

        public Scene(String caption, String narration, String code) {
            this(caption, narration, code, null);
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

    public static double estimateSeconds(List<Scene> scenes) {
        double total = 0;
        for (Scene s : scenes) total += s.narration().codePointCount(0, s.narration().length()) / CHARS_PER_SECOND + SCENE_GAP_SECONDS;
        return total;
    }
}
