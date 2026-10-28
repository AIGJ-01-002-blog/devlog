package com.team.blog.shorts.autoupload;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.team.blog.shorts.application.ShortsScript.Scene;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.shorts.application.UploadLink.Platform;

/**
 * 올릴 때 쓰는 제목·설명(캡션)·재생목록 이름 (spec 108 FR-010). 화면 app/frontend/src/lib/shorts.ts의 uploadGuide()·playlistName()을
 * 한국어 그대로 옮겼다. 두 쪽이 같은지는 같은 견본 파일(app/frontend/src/lib/shortsCaptionParity.json)을 양쪽 테스트가 읽어 본다.
 * 한쪽 문구를 바꾸면 견본과 다른 쪽도 함께 바꾼다.
 */
public final class ShortsCaption {
    /** 화면 SHORTS_TITLE_PREFIX */
    public static final String TITLE_PREFIX = "[백엔드 면접]";

    /** 화면 PLATFORMS의 한도 중 문구에 쓰는 것. titleMax가 null이면 제목 칸이 없어 캡션 첫 줄이 제목이 된다 */
    public record Spec(Integer titleMax, int captionMax, int hashtagMax, boolean linksClickable) {}

    public static Spec spec(Platform platform) {
        return switch (platform) {
            case YOUTUBE -> new Spec(100, 5000, 3, true);
            case INSTAGRAM -> new Spec(null, 2200, 5, false);
            case TIKTOK -> new Spec(null, 2200, 5, false);
        };
    }

    /** 문구를 만드는 데 쓰는 쇼츠 칸. replay는 매매 재현 영상인지 */
    public record Input(String title, Long postId, String postUrl, boolean replay, List<String> narrations, List<String> tags, String playlist) {
        public static Input of(ShortsService.Item item) {
            return new Input(item.title(), item.postId(), item.postUrl(), item.replay() != null,
                    item.scenes().stream().map(Scene::narration).toList(), item.postTags(),
                    item.playlist() == null ? null : item.playlist().label);
        }
    }

    /** @param title 제목 칸이 없는 플랫폼이면 null. playlistName은 글 쇼츠에 묶음을 골랐을 때만 */
    public record Text(String title, String caption, List<String> tags, String playlistName) {}

    private ShortsCaption() {}

    /** @param publishTime 공개 시각(한국 시간, blog.shorts.auto-upload.publish-time). 설명의 "매일 저녁 8시"가 예약 시각과 같게 한다 */
    public static Text build(Input in, Platform platform, String origin, LocalTime publishTime) {
        Spec spec = spec(platform);
        String link = link(origin, in.postUrl());
        List<String> tags = hashtags(in.tags(), spec.hashtagMax());
        String tagLine = String.join(" ", tags);
        String linkLine = spec.linksClickable() ? "전체 글: " + link
                : platform == Platform.INSTAGRAM ? "전체 코드와 과정은 프로필 링크의 블로그에 있어요."
                : "전체 글은 고정 댓글의 주소에서 볼 수 있어요.";
        String followLine = (platform == Platform.YOUTUBE ? "구독하면" : "팔로우하면") + " 매일 " + clock(publishTime) + "에 하나씩 받아 봐요.";
        String series = seriesTitle(in);
        List<String> parts = spec.titleMax() == null
                ? List.of(series, hook(in), linkLine, followLine, tagLine)
                : List.of(linkLine, hook(in), followLine, tagLine);
        String caption = String.join("\n\n", parts.stream().filter(s -> !s.isEmpty()).toList());
        String title = spec.titleMax() == null ? null : series;
        return new Text(title, caption, tags, fromPost(in) && in.playlist() != null ? playlistName(in.playlist()) : null);
    }

    /** 20:00 → "저녁 8시"(화면 문구와 같다), 19:30 → "저녁 7시 30분", 9:00 → "오전 9시" */
    static String clock(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return (t.getHour() < 12 ? "오전 " : "저녁 ") + h + "시" + (t.getMinute() > 0 ? " " + t.getMinute() + "분" : "");
    }

    /** 화면 playlistName(): 묶음 한글 이름(배포·DB·…)에 제목과 같은 앞머리 */
    public static String playlistName(String label) {
        return TITLE_PREFIX + " " + label;
    }

    /** 화면 hashtags(): 해시태그에 못 쓰는 글자를 빼고, 겹치면 하나만, max개까지 */
    public static List<String> hashtags(List<String> raw, int max) {
        List<String> out = new ArrayList<>();
        for (String tag : raw == null ? List.<String>of() : raw) {
            String word = tag.replaceAll("[^\\p{L}\\p{N}_]", "");
            if (!word.isEmpty() && !out.contains("#" + word)) out.add("#" + word);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 코드 포인트 수(화면 [...s].length)로 자른다. 플랫폼 한도를 넘기면 API가 거절한다 */
    public static String limit(String s, int max) {
        if (s == null || s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max));
    }

    private static boolean fromPost(Input in) {
        return !in.replay() && in.postId() != null;
    }

    private static String seriesTitle(Input in) {
        return fromPost(in) ? TITLE_PREFIX + " " + in.title() : in.title();
    }

    private static String hook(Input in) {
        return Stream.ofNullable(in.narrations()).flatMap(List::stream).filter(Objects::nonNull).map(ShortsCaption::jsTrim)
                .filter(s -> !s.isEmpty()).findFirst().orElse("");
    }

    /** new URL(postUrl ?? '/', origin).href. 글 주소는 사이트 안 경로(/@handle/posts/7)다 */
    static String link(String origin, String postUrl) {
        String base = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return base + (postUrl == null ? "/" : postUrl);
    }

    /** JS String.prototype.trim과 같은 공백(줄바꿈·NBSP·BOM 포함)을 뗀다 */
    static String jsTrim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && jsSpace(s.charAt(a))) a++;
        while (b > a && jsSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean jsSpace(char c) {
        return c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == ' ' || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000 || c == 0xFEFF;
    }
}
