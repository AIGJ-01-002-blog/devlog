package com.team.blog.page;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.team.blog.shared.config.BlogProperties;

/**
 * 이용약관·개인정보 처리방침 본문 (005 FR-026, spec 085). 문안은 policy/*.html 한 곳에 두고, 서버가 /terms·/privacy 첫 화면에
 * 그대로 넣고 React도 같은 HTML을 받아 그린다. 애드센스 심사처럼 스크립트를 돌리지 않는 읽기 도구도 처리방침을 읽게 하려는 것이다.
 * 파일은 저장소에 든 신뢰할 수 있는 문안이라 정화하지 않는다(회원이 쓴 글이 아니다).
 */
@Component
public class PolicyDocuments {
    public enum Kind {
        TERMS("이용약관", "terms"), PRIVACY("개인정보 처리방침", "privacy");

        final String title;
        final String path;

        Kind(String title, String path) {
            this.title = title;
            this.path = path;
        }

        public String title() {
            return title;
        }

        public static Optional<Kind> of(String path) {
            for (Kind k : values()) if (k.path.equals(path)) return Optional.of(k);
            return Optional.empty();
        }
    }

    public record Document(String kind, String title, String effectiveDate, String html) {}

    private final String terms;
    private final String privacy;
    private final BlogProperties.Agreements agreements;

    public PolicyDocuments(BlogProperties props) {
        this.agreements = props.agreements();
        this.terms = load("policy/terms.html");
        this.privacy = load("policy/privacy.html");
    }

    public Document get(Kind kind) {
        return kind == Kind.TERMS
                ? new Document(kind.path, kind.title, agreements.termsEffectiveDate(), terms)
                : new Document(kind.path, kind.title, agreements.privacyEffectiveDate(), privacy);
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            // 맨 앞의 안내 주석은 화면에 필요 없다
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceFirst("(?s)^<!--.*?-->\\s*", "");
        } catch (IOException e) {
            throw new UncheckedIOException("약관 문안을 읽지 못했어요: " + path, e);
        }
    }
}
