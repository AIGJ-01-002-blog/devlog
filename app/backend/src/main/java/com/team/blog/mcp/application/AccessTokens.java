package com.team.blog.mcp.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * MCP 연결용 개인 접근 토큰 (052). 원문은 만들 때 한 번만 돌려주고, DB에는 SHA-256만 둔다.
 * 토큰은 무작위 32바이트라 느린 해시가 필요 없고, 해시가 같으면 같은 토큰이다.
 */
@Service
public class AccessTokens {
    public static final String PREFIX = "dvl_";
    static final String REFRESH_PREFIX = "dvr_";
    static final Duration OAUTH_ACCESS_TTL = Duration.ofHours(1);
    static final Duration OAUTH_REFRESH_TTL = Duration.ofDays(90);
    static final int MAX_ACTIVE = 10;
    static final int NAME_MAX = 40;
    static final Set<Integer> EXPIRY_DAYS = Set.of(30, 90, 365);
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Scope { READ, WRITE }

    /**
     * @param oauth OAuth로 연결한 앱(ChatGPT 등)이면 true. 이때 expiresAt은 연결(갱신 토큰) 만료
     * @param adminTools 관리자가 이 토큰에만 따로 켠 문의 관리 도구 (#378)
     */
    public record TokenView(long id, String name, String prefix, Scope scope, Instant createdAt, Instant expiresAt,
                            Instant lastUsedAt, boolean expired, boolean oauth, boolean adminTools) {}

    /** OAuth 토큰 응답 재료. accessToken은 MCP 요청에, refreshToken은 갱신에 쓴다 */
    public record OAuthGrant(String accessToken, String refreshToken, long expiresInSeconds, Scope scope) {}

    /** @param secret 원문. 이 응답에서만 보인다 */
    public record Issued(TokenView token, String secret) {}

    /**
     * 토큰으로 들어온 요청의 주인. status는 member.status(ACTIVE·SUSPENDED·WITHDRAWN).
     * aiPublishAllowed는 회원이 웹 설정에서 켠 "AI가 발행·삭제하도록 허용"(053). 요청마다 DB에서 새로 읽는다.
     * admin은 관리자 회원이 이 토큰에 관리자 도구를 켰을 때만 true(054 문의 관리 도구, #378). 역할도 요청마다 새로 읽는다. tokenName은 토큰 이름이나 OAuth 앱 이름(버그 신고에 남긴다).
     * aiDiaryEnabled는 회원이 켠 "AI 일기 쓰기"(061). 켜져 있을 때만 add_note가 보인다.
     */
    public record Caller(long memberId, String handle, Scope scope, String status, boolean emailVerified, boolean aiPublishAllowed,
                         boolean admin, String tokenName, boolean aiDiaryEnabled) {
        public boolean canWrite() {
            return scope == Scope.WRITE;
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccessTokens(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** adminTools는 관리자 회원만 켤 수 있다. 다른 회원이 켜면 403 */
    public Issued create(long memberId, String rawName, String rawScope, Integer expiresInDays, boolean adminTools) {
        String name = rawName == null ? "" : rawName.strip();
        Scope scope = parseScope(rawScope);
        int days = expiresInDays == null ? 90 : expiresInDays;
        if (name.isEmpty() || name.codePointCount(0, name.length()) > NAME_MAX) {
            throw ApiException.validation(List.of(new FieldErrorItem("name", "INVALID_NAME", "이름은 1~" + NAME_MAX + "자로 적어 주세요.")));
        }
        if (!EXPIRY_DAYS.contains(days)) {
            throw ApiException.validation(List.of(new FieldErrorItem("expiresInDays", "INVALID_EXPIRY", "만료 기간을 골라 주세요.")));
        }
        Instant now = Times.now(clock);
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM personal_access_token
                WHERE member_id = ? AND oauth_client_id IS NULL AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
                """, Integer.class, memberId, Timestamp.from(now));
        if (active != null && active >= MAX_ACTIVE) {
            throw ApiException.conflict("TOKEN_LIMIT", "토큰은 " + MAX_ACTIVE + "개까지 만들 수 있어요. 안 쓰는 토큰을 폐기해 주세요.");
        }
        if (adminTools && !isAdmin(memberId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "관리자 도구는 관리자만 켤 수 있어요.");
        }
        String secret = newSecret();
        String prefix = secret.substring(0, PREFIX.length() + 4);
        Instant expires = now.plus(Duration.ofDays(days));
        Long id = jdbc.queryForObject("""
                INSERT INTO personal_access_token (member_id, name, token_hash, token_prefix, scope, created_at, expires_at, admin_tools)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, memberId, name, hash(secret), prefix, scope.name(), Timestamp.from(now), Timestamp.from(expires), adminTools);
        return new Issued(new TokenView(id, name, prefix, scope, now, expires, null, false, false, adminTools), secret);
    }

    /** 폐기하지 않은 토큰과 OAuth 연결. 만료된 것도 보여 주어 다시 만들 수 있게 한다. */
    public List<TokenView> list(long memberId) {
        Instant now = Times.now(clock);
        return jdbc.query("""
                SELECT id, name, token_prefix, scope, created_at, expires_at, last_used_at, oauth_client_id, refresh_expires_at, admin_tools
                FROM personal_access_token WHERE member_id = ? AND revoked_at IS NULL ORDER BY created_at DESC, id DESC
                """, (rs, i) -> {
                    boolean oauth = rs.getString("oauth_client_id") != null;
                    Instant expires = instant(rs.getTimestamp(oauth ? "refresh_expires_at" : "expires_at"));
                    return new TokenView(rs.getLong("id"), rs.getString("name"), rs.getString("token_prefix"),
                            Scope.valueOf(rs.getString("scope")), instant(rs.getTimestamp("created_at")), expires,
                            instant(rs.getTimestamp("last_used_at")), expires != null && !expires.isAfter(now), oauth,
                            rs.getBoolean("admin_tools"));
                }, memberId);
    }

    /**
     * OAuth 동의 뒤 토큰 발급 (코드 교환). 연결 하나가 한 행이고 설정 화면에서 폐기할 수 있다.
     * adminTools는 동의 화면에서 관리자가 따로 켰을 때만. 그새 관리자가 아니게 됐으면 끈 채로 발급한다
     */
    public OAuthGrant issueOAuth(long memberId, String clientId, String clientName, Scope scope, boolean adminTools) {
        boolean admin = adminTools && isAdmin(memberId);
        Instant now = Times.now(clock);
        String access = newSecret();
        String refresh = REFRESH_PREFIX + newSecret().substring(PREFIX.length());
        String name = clientName.codePointCount(0, clientName.length()) > NAME_MAX
                ? clientName.substring(0, clientName.offsetByCodePoints(0, NAME_MAX)) : clientName;
        jdbc.update("""
                INSERT INTO personal_access_token (member_id, name, token_hash, token_prefix, scope, created_at, expires_at,
                                                   oauth_client_id, refresh_hash, refresh_expires_at, admin_tools)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, memberId, name, hash(access), access.substring(0, PREFIX.length() + 4), scope.name(), Timestamp.from(now),
                Timestamp.from(now.plus(OAUTH_ACCESS_TTL)), clientId, hash(refresh), Timestamp.from(now.plus(OAUTH_REFRESH_TTL)), admin);
        return new OAuthGrant(access, refresh, OAUTH_ACCESS_TTL.toSeconds(), scope);
    }

    /** 갱신 토큰으로 새 토큰 한 쌍을 받는다. 쓴 갱신 토큰은 바로 못 쓰게 바꾼다(회전). 맞지 않으면 비어 있다. */
    public Optional<OAuthGrant> refreshOAuth(String refreshToken, String clientId) {
        if (refreshToken == null || !refreshToken.startsWith(REFRESH_PREFIX) || refreshToken.length() > 100 || clientId == null) {
            return Optional.empty();
        }
        Instant now = Times.now(clock);
        String access = newSecret();
        String refresh = REFRESH_PREFIX + newSecret().substring(PREFIX.length());
        List<Scope> scopes = jdbc.query("""
                UPDATE personal_access_token SET token_hash = ?, token_prefix = ?, expires_at = ?, refresh_hash = ?
                WHERE refresh_hash = ? AND oauth_client_id = ? AND revoked_at IS NULL AND refresh_expires_at > ?
                  AND EXISTS (SELECT 1 FROM member m WHERE m.id = member_id AND m.deleted_at IS NULL)
                RETURNING scope
                """, (rs, i) -> Scope.valueOf(rs.getString(1)), hash(access), access.substring(0, PREFIX.length() + 4),
                Timestamp.from(now.plus(OAUTH_ACCESS_TTL)), hash(refresh), hash(refreshToken), clientId, Timestamp.from(now));
        if (scopes.isEmpty()) return Optional.empty();
        return Optional.of(new OAuthGrant(access, refresh, OAUTH_ACCESS_TTL.toSeconds(), scopes.getFirst()));
    }

    /** 폐기. 이미 폐기했어도 같은 결과다. 남의 토큰·없는 토큰은 404. */
    public void revoke(long memberId, long tokenId) {
        int changed = jdbc.update("""
                UPDATE personal_access_token SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ? AND member_id = ?
                """, Timestamp.from(Times.now(clock)), tokenId, memberId);
        if (changed == 0) throw new NotFoundException();
    }

    /** "AI가 발행·삭제하도록 허용" (053). 웹 설정 화면(로그인 세션)에서만 읽고 바꾼다. */
    public boolean aiPublishAllowed(long memberId) {
        List<Boolean> rows = jdbc.query("SELECT ai_publish_allowed FROM member WHERE id = ?", (rs, i) -> rs.getObject(1, Boolean.class), memberId);
        if (rows.isEmpty()) throw new NotFoundException();
        return Boolean.TRUE.equals(rows.getFirst());
    }

    public boolean setAiPublishAllowed(long memberId, boolean allowed) {
        if (jdbc.update("UPDATE member SET ai_publish_allowed = ? WHERE id = ?", allowed, memberId) == 0) throw new NotFoundException();
        return allowed;
    }

    /** 관리자 회원인지. 관리자 도구를 켤 때 쓴다 */
    public boolean isAdmin(long memberId) {
        Boolean admin = jdbc.query("SELECT role = 'ADMIN' FROM member WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getBoolean(1), memberId).stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(admin);
    }

    /** 탈퇴 정리 (020): 토큰을 모두 지운다. */
    public void deleteAll(long memberId) {
        jdbc.update("DELETE FROM personal_access_token WHERE member_id = ?", memberId);
    }

    /** Authorization 헤더 값(Bearer ...)으로 주인을 찾는다. 형식이 틀리거나 폐기·만료된 토큰이면 비어 있다. */
    public Optional<Caller> authenticate(String authorization) {
        String secret = bearer(authorization);
        if (secret == null) return Optional.empty();
        Instant now = Times.now(clock);
        List<Row> rows = jdbc.query("""
                SELECT t.id, t.member_id, t.scope, t.last_used_at, t.name AS token_name, m.handle, m.status, m.ai_publish_allowed, m.ai_diary_enabled,
                       m.role = 'ADMIN' AND t.admin_tools AS admin,
                       EXISTS (SELECT 1 FROM auth_identity a WHERE a.member_id = m.id AND a.email_verified_at IS NOT NULL) AS verified
                FROM personal_access_token t JOIN member m ON m.id = t.member_id
                WHERE t.token_hash = ? AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > ?) AND m.deleted_at IS NULL
                """, (rs, i) -> new Row(rs.getLong("id"), instant(rs.getTimestamp("last_used_at")),
                new Caller(rs.getLong("member_id"), rs.getString("handle"), Scope.valueOf(rs.getString("scope")),
                        rs.getString("status"), rs.getBoolean("verified"), rs.getBoolean("ai_publish_allowed"), rs.getBoolean("admin"),
                        rs.getString("token_name"), rs.getBoolean("ai_diary_enabled"))),
                hash(secret), Timestamp.from(now));
        if (rows.isEmpty()) return Optional.empty();
        Row row = rows.getFirst();
        if (row.lastUsedAt() == null || row.lastUsedAt().plus(TOUCH_EVERY).isBefore(now)) {
            jdbc.update("UPDATE personal_access_token SET last_used_at = ? WHERE id = ?", Timestamp.from(now), row.id());
        }
        return Optional.of(row.caller());
    }

    private record Row(long id, Instant lastUsedAt, Caller caller) {}

    static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String secret = authorization.substring(7).strip();
        if (!secret.startsWith(PREFIX) || secret.length() > 100) return null;
        return secret;
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Scope parseScope(String raw) {
        if (raw == null) return Scope.WRITE;
        try {
            return Scope.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "권한을 골라 주세요.");
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
