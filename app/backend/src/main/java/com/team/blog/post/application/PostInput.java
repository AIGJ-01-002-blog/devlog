package com.team.blog.post.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.text.TextCleaner;

/**
 * 제목·본문 정리와 검증 (docs/05 §4, docs/12 §7-4). 저장은 길이만, 발행은 필수 항목까지 본다.
 * 실패한 항목은 모두 모아 한 번에 돌려준다.
 */
public record PostInput(String title, String contentMd) {
    /** 짧은 소개 길이 상한. DB 열(post.summary varchar(150))과 같다. */
    public static final int MAX_SUMMARY = 150;

    /** 업로드가 끝나지 않은 사진(브라우저 임시 주소)이 본문에 남았는지 (docs/04 §4-3). */
    private static final Pattern PENDING_IMAGE = Pattern.compile("\\]\\(\\s*local:");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String CONTENT_FIELD = "contentMd";
    private static final String MAX_LENGTH_SUFFIX = "자까지 쓸 수 있어요.";

    public static PostInput forSave(String rawTitle, String rawContent, BlogProperties.Post rules) {
        PostInput in = clean(rawTitle, rawContent);
        List<FieldErrorItem> errors = new ArrayList<>();
        in.checkLengths(rules, errors);
        if (!errors.isEmpty()) throw ApiException.validation(errors);
        return in;
    }

    public static PostInput forPublish(String rawTitle, String rawContent, BlogProperties.Post rules,
                                       List<FieldErrorItem> errors) {
        PostInput in = clean(rawTitle, rawContent);
        if (in.title.isEmpty()) errors.add(new FieldErrorItem("title", "TITLE_REQUIRED", "제목을 입력해 주세요."));
        if (in.contentMd.isBlank()) errors.add(new FieldErrorItem(CONTENT_FIELD, "CONTENT_REQUIRED", "본문을 입력해 주세요."));
        in.checkLengths(rules, errors);
        if (PENDING_IMAGE.matcher(in.contentMd).find()) {
            errors.add(new FieldErrorItem(CONTENT_FIELD, "PENDING_IMAGES", "아직 올라가지 않은 사진이 있어요. 업로드가 끝난 뒤 발행해 주세요."));
        }
        return in;
    }

    /**
     * 짧은 소개 (spec 045). 목록에 한 문단으로 보이므로 줄바꿈·연속 공백을 한 칸으로 줄인다.
     * @return 정리한 소개, 비었으면 null(본문으로 자동 요약)
     */
    public static String cleanSummary(String raw, List<FieldErrorItem> errors) {
        String s = raw == null ? "" : TextCleaner.cleanLine(WHITESPACE.matcher(raw).replaceAll(" "));
        if (s.isEmpty()) return null;
        if (s.codePointCount(0, s.length()) > MAX_SUMMARY) {
            errors.add(new FieldErrorItem("summary", "SUMMARY_TOO_LONG", "짧은 소개는 " + MAX_SUMMARY + MAX_LENGTH_SUFFIX));
        }
        return s;
    }

    private static PostInput clean(String rawTitle, String rawContent) {
        String title = TextCleaner.cleanLine(rawTitle);
        // 본문은 Markdown 원문 그대로 둔다. PostgreSQL text가 받지 못하는 NUL만 지우고 줄바꿈을 맞춘다
        String content = rawContent == null ? "" : rawContent.replace("\u0000", "").replace("\r\n", "\n");
        return new PostInput(title, content);
    }

    private void checkLengths(BlogProperties.Post rules, List<FieldErrorItem> errors) {
        if (title.codePointCount(0, title.length()) > rules.maxTitleLength()) {
            errors.add(new FieldErrorItem("title", "TITLE_TOO_LONG", "제목은 " + rules.maxTitleLength() + MAX_LENGTH_SUFFIX));
        }
        // DB CHECK(char_length)와 같은 기준(코드 포인트)으로 센다
        if (contentMd.codePointCount(0, contentMd.length()) > rules.maxContentLength()) {
            errors.add(new FieldErrorItem(CONTENT_FIELD, "CONTENT_TOO_LONG",
                    "본문은 " + String.format("%,d", rules.maxContentLength()) + MAX_LENGTH_SUFFIX));
        }
    }
}
