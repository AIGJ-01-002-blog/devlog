package com.team.blog.account.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;

/**
 * 블로그 소셜 정보 (spec 043): 이메일·GitHub·X·Facebook·홈페이지. (회원, 종류)당 한 행이고 빈 칸은 행이 없다.
 * GitHub·X·Facebook은 아이디만 저장해 주소 앞부분을 행마다 되풀이하지 않는다. 주소를 붙여 넣어도 아이디를 꺼낸다.
 */
@Service
public class SocialLinks {
    static final int MAX = 254;

    /** 화면과 API의 칸 이름은 소문자 종류 이름이다. 순서는 블로그 머리에 보이는 순서. */
    public enum Kind {
        EMAIL("이메일 주소 형식이 아니에요."),
        GITHUB("GitHub 아이디나 주소를 확인해 주세요."),
        X("X 아이디나 주소를 확인해 주세요."),
        FACEBOOK("Facebook 아이디나 주소를 확인해 주세요."),
        HOMEPAGE("http:// 또는 https://로 시작하는 주소를 넣어 주세요.");

        private final String message;

        Kind(String message) {
            this.message = message;
        }

        String field() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Links(String email, String github, String x, String facebook, String homepage) {
        public static final Links EMPTY = new Links(null, null, null, null, null);

        String get(Kind k) {
            return switch (k) {
                case EMAIL -> email;
                case GITHUB -> github;
                case X -> x;
                case FACEBOOK -> facebook;
                case HOMEPAGE -> homepage;
            };
        }

        static Links of(Map<Kind, String> m) {
            return new Links(m.get(Kind.EMAIL), m.get(Kind.GITHUB), m.get(Kind.X), m.get(Kind.FACEBOOK), m.get(Kind.HOMEPAGE));
        }
    }

    private static final Pattern GITHUB_ID = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}");
    private static final Pattern X_ID = Pattern.compile("\\w{1,15}");
    private static final Pattern FACEBOOK_ID = Pattern.compile("[A-Za-z0-9.]{5,50}");
    private static final Pattern GITHUB_URL = profileUrl("github\\.com");
    private static final Pattern X_URL = profileUrl("(?:x|twitter)\\.com");
    private static final Pattern FACEBOOK_URL = profileUrl("(?:facebook|fb)\\.com");

    private final JdbcTemplate jdbc;

    public SocialLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Links of(long memberId) {
        Map<Kind, String> m = new EnumMap<>(Kind.class);
        jdbc.query("SELECT kind, value FROM member_social_link WHERE member_id = ?",
                (RowCallbackHandler) rs -> m.put(Kind.valueOf(rs.getString("kind")), rs.getString("value")), memberId);
        return Links.of(m);
    }

    /** 모든 칸을 먼저 검사해 하나라도 틀리면 아무것도 바꾸지 않고 틀린 칸을 모두 알려준다. 빈 칸은 지운다. */
    @Transactional
    public Links save(long memberId, Links input) {
        Map<Kind, String> clean = new EnumMap<>(Kind.class);
        List<FieldErrorItem> errors = new ArrayList<>();
        for (Kind k : Kind.values()) {
            String raw = input.get(k) == null ? "" : input.get(k).strip();
            if (raw.isEmpty()) continue;
            String v = normalize(k, raw);
            if (v == null) errors.add(new FieldErrorItem(k.field(), "SOCIAL_" + k.name() + "_INVALID", k.message));
            else clean.put(k, v);
        }
        if (!errors.isEmpty()) throw ApiException.validation(errors);

        // 지우고 다시 넣는 사이에 같은 회원의 다른 저장이 끼면 기본 키가 겹친다. 회원 행을 잠가 차례로 처리한다
        jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, memberId);
        jdbc.update("DELETE FROM member_social_link WHERE member_id = ?", memberId);
        List<Object[]> rows = clean.entrySet().stream().map(e -> new Object[] {memberId, e.getKey().name(), e.getValue()}).toList();
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO member_social_link (member_id, kind, value) VALUES (?, ?, ?)", rows);
        return Links.of(clean);
    }

    /** @param raw 앞뒤 공백을 지운 빈 값이 아닌 입력 @return 정리한 값, 형식이 틀리면 null */
    static String normalize(Kind kind, String raw) {
        return switch (kind) {
            case EMAIL -> {
                String e = EmailAddress.normalize(raw);
                yield EmailAddress.isValid(e) ? e : null;
            }
            case GITHUB -> account(raw, GITHUB_URL, GITHUB_ID);
            case X -> account(raw, X_URL, X_ID);
            case FACEBOOK -> account(raw, FACEBOOK_URL, FACEBOOK_ID);
            case HOMEPAGE -> homepage(raw);
        };
    }

    /** 프로필 주소면 첫 경로를, 아니면 앞의 @를 뗀 입력을 아이디로 본다. */
    private static String account(String raw, Pattern url, Pattern id) {
        Matcher m = url.matcher(raw);
        String candidate;
        if (m.matches()) candidate = m.group(1);
        else if (raw.startsWith("@")) candidate = raw.substring(1);
        else candidate = raw;
        return id.matcher(candidate).matches() ? candidate : null;
    }

    private static Pattern profileUrl(String host) {
        return Pattern.compile("(?i)(?:https?://)?(?:www\\.|m\\.|mobile\\.)?" + host + "/@?([^/?#]+)/?(?:[?#].*)?");
    }

    /** http(s) 주소만 받는다(javascript: 등은 링크로 내보내지 않는다). 스킴이 없으면 https를 붙인다. 계정 정보가 든 주소는 받지 않는다. */
    private static String homepage(String raw) {
        String s = raw.matches("(?i)^https?://.*") ? raw : "https://" + raw;
        if (s.length() > MAX || s.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) return null;
        try {
            URI u = new URI(s);
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (u.getHost() == null || !u.getHost().contains(".") || u.getRawUserInfo() != null) return null;
            return scheme + s.substring(u.getScheme().length());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
