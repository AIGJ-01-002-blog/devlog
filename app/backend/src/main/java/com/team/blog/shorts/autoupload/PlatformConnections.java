package com.team.blog.shorts.autoupload;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.shared.time.Times;

/**
 * 플랫폼 연결 저장소 (spec 108). 토큰은 TokenCipher로 암호화해서만 쓰고 읽는다. 평문 토큰은 메모리에서만 쓰고 로그·응답에 내보내지 않는다.
 */
@Component
public class PlatformConnections {
    private static final Logger log = LoggerFactory.getLogger(PlatformConnections.class);
    /** YouTube 접근 토큰이 이보다 덜 남았으면 새로 받는다 */
    static final Duration REFRESH_BEFORE = Duration.ofMinutes(2);
    /** Instagram 장기 토큰이 이보다 덜 남았으면 늘린다(발급 24시간 뒤부터 늘릴 수 있다) */
    static final Duration INSTAGRAM_REFRESH_BEFORE = Duration.ofDays(20);

    /** 행 하나. 토큰 칸은 암호문 그대로다 */
    public record Connection(long memberId, AutoPlatform platform, String accountId, String accountName, String accessEnc, String refreshEnc,
                             Instant tokenExpiresAt, boolean enabled, Instant enabledAt, Instant testedAt, String testUrl, String testError,
                             Instant updatedAt) {
        @Override
        public String toString() {
            return "Connection[" + memberId + ", " + platform + ", enabled=" + enabled + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final AutoUploadProperties props;
    private final YouTubeApi youtube;
    private final InstagramApi instagram;
    private final Clock clock;
    private final TokenCipher cipher;

    PlatformConnections(JdbcTemplate jdbc, AutoUploadProperties props, YouTubeApi youtube, InstagramApi instagram, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.youtube = youtube;
        this.instagram = instagram;
        this.clock = clock;
        TokenCipher c = null;
        if (props.hasKey()) {
            try {
                c = new TokenCipher(props.tokenKey());
            } catch (IllegalStateException e) {
                log.error("쇼츠 자동 올리기를 끕니다: {}", e.getMessage());
            }
        }
        this.cipher = c;
    }

    /** 열쇠가 맞게 들어 있고 그 플랫폼 앱 값이 있는지 */
    public boolean configured(AutoPlatform platform) {
        return cipher != null && props.configured(platform);
    }

    public boolean anyConfigured() {
        return configured(AutoPlatform.YOUTUBE) || configured(AutoPlatform.INSTAGRAM);
    }

    public List<Connection> list(long memberId) {
        return jdbc.query("SELECT * FROM shorts_platform_connection WHERE member_id = ? ORDER BY platform", PlatformConnections::map, memberId);
    }

    public Optional<Connection> find(long memberId, AutoPlatform platform) {
        return jdbc.query("SELECT * FROM shorts_platform_connection WHERE member_id = ? AND platform = ?", PlatformConnections::map,
                memberId, platform.name()).stream().findFirst();
    }

    /** 켜진 연결 모두 (작업이 돈다) */
    public List<Connection> enabled() {
        return jdbc.query("SELECT * FROM shorts_platform_connection WHERE enabled ORDER BY member_id, platform", PlatformConnections::map);
    }

    /** 새로 잇거나 다시 이었다. 계정이 바뀌었을 수 있으니 시험·켜짐을 처음으로 돌린다 */
    void save(long memberId, AutoPlatform platform, String accountId, String accountName, String access, String refresh, Instant expiresAt) {
        Instant now = Times.now(clock);
        jdbc.update("""
                INSERT INTO shorts_platform_connection (member_id, platform, account_id, account_name, access_token_enc, refresh_token_enc,
                    token_expires_at, enabled, enabled_at, tested_at, test_url, test_error, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, false, NULL, NULL, NULL, NULL, ?, ?)
                ON CONFLICT (member_id, platform) DO UPDATE SET account_id = EXCLUDED.account_id, account_name = EXCLUDED.account_name,
                    access_token_enc = EXCLUDED.access_token_enc, refresh_token_enc = EXCLUDED.refresh_token_enc,
                    token_expires_at = EXCLUDED.token_expires_at, enabled = false, enabled_at = NULL, tested_at = NULL, test_url = NULL,
                    test_error = NULL, updated_at = EXCLUDED.updated_at
                """, memberId, platform.name(), accountId, cut(accountName, 120), enc(memberId, platform, "access", access),
                enc(memberId, platform, "refresh", refresh), ts(expiresAt), Timestamp.from(now), Timestamp.from(now));
    }

    void delete(long memberId, AutoPlatform platform) {
        jdbc.update("DELETE FROM shorts_platform_connection WHERE member_id = ? AND platform = ?", memberId, platform.name());
    }

    void setEnabled(long memberId, AutoPlatform platform, boolean enabled) {
        Instant now = Times.now(clock);
        jdbc.update("""
                UPDATE shorts_platform_connection SET enabled = ?, enabled_at = CASE WHEN ? THEN ? ELSE enabled_at END, updated_at = ?
                WHERE member_id = ? AND platform = ?
                """, enabled, enabled, Timestamp.from(now), Timestamp.from(now), memberId, platform.name());
    }

    void recordTest(long memberId, AutoPlatform platform, boolean passed, String url, String error) {
        Instant now = Times.now(clock);
        jdbc.update("""
                UPDATE shorts_platform_connection SET tested_at = CASE WHEN ? THEN ? ELSE tested_at END, test_url = CASE WHEN ? THEN ? ELSE test_url END,
                    test_error = ?, updated_at = ? WHERE member_id = ? AND platform = ?
                """, passed, Timestamp.from(now), passed, cut(url, 300), passed ? null : cut(error, 500), Timestamp.from(now), memberId, platform.name());
    }

    /**
     * 쓸 수 있는 접근 토큰. YouTube는 곧 끝나면 갱신 토큰으로 새로 받아 저장한다.
     * @throws PlatformException AUTH: 토큰을 풀 수 없거나(열쇠가 바뀜) 갱신이 거절됐다
     */
    public String accessToken(Connection c) {
        if (cipher == null) throw new PlatformException(PlatformException.Kind.RETRYABLE, "서버의 토큰 열쇠(SHORTS_TOKEN_KEY) 설정이 없어요.");
        String access;
        try {
            access = cipher.decrypt(c.accessEnc(), TokenCipher.context(c.memberId(), c.platform(), "access"));
        } catch (IllegalStateException e) {
            throw new PlatformException(PlatformException.Kind.AUTH, "저장한 토큰을 풀지 못했어요(서버 열쇠가 바뀌었을 수 있어요). 다시 연결해 주세요.");
        }
        Instant now = Times.now(clock);
        if (c.platform() == AutoPlatform.YOUTUBE && (access == null || c.tokenExpiresAt() == null || c.tokenExpiresAt().isBefore(now.plus(REFRESH_BEFORE)))) {
            String refresh;
            try {
                refresh = cipher.decrypt(c.refreshEnc(), TokenCipher.context(c.memberId(), c.platform(), "refresh"));
            } catch (IllegalStateException e) {
                throw new PlatformException(PlatformException.Kind.AUTH, "저장한 토큰을 풀지 못했어요(서버 열쇠가 바뀌었을 수 있어요). 다시 연결해 주세요.");
            }
            if (refresh == null) throw PlatformException.auth(HttpYouTubeApi.NAME);
            YouTubeApi.Tokens t = youtube.refresh(refresh);
            storeTokens(c, t.accessToken(), t.refreshToken(), t.expiresAt());
            return t.accessToken();
        }
        return access;
    }

    /** Instagram 장기 토큰을 만료 전에 늘린다. @return 늘린 연결 수 */
    public int refreshInstagram() {
        if (!configured(AutoPlatform.INSTAGRAM)) return 0;
        Instant before = Times.now(clock).plus(INSTAGRAM_REFRESH_BEFORE);
        List<Connection> due = jdbc.query("""
                SELECT * FROM shorts_platform_connection WHERE platform = 'INSTAGRAM' AND (token_expires_at IS NULL OR token_expires_at < ?)
                    AND updated_at < ?
                """, PlatformConnections::map, Timestamp.from(before), Timestamp.from(Times.now(clock).minus(Duration.ofDays(1))));
        int n = 0;
        for (Connection c : due) {
            try {
                InstagramApi.Tokens t = instagram.refresh(accessToken(c));
                storeTokens(c, t.accessToken(), null, t.expiresAt());
                n++;
            } catch (PlatformException e) {
                log.warn("Instagram 토큰을 늘리지 못했습니다 (회원 {}): {}", c.memberId(), e.getMessage());
            }
        }
        return n;
    }

    private void storeTokens(Connection c, String access, String refresh, Instant expiresAt) {
        jdbc.update("""
                UPDATE shorts_platform_connection SET access_token_enc = ?, refresh_token_enc = COALESCE(?, refresh_token_enc), token_expires_at = ?,
                    updated_at = ? WHERE member_id = ? AND platform = ?
                """, enc(c.memberId(), c.platform(), "access", access), enc(c.memberId(), c.platform(), "refresh", refresh), ts(expiresAt),
                Timestamp.from(Times.now(clock)), c.memberId(), c.platform().name());
    }

    private String enc(long memberId, AutoPlatform platform, String column, String plain) {
        if (plain == null) return null;
        if (cipher == null) throw new IllegalStateException("토큰 열쇠가 없습니다");
        return cipher.encrypt(plain, TokenCipher.context(memberId, platform, column));
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Connection map(ResultSet rs, int row) throws SQLException {
        return new Connection(rs.getLong("member_id"), AutoPlatform.valueOf(rs.getString("platform")), rs.getString("account_id"),
                rs.getString("account_name"), rs.getString("access_token_enc"), rs.getString("refresh_token_enc"), instant(rs, "token_expires_at"),
                rs.getBoolean("enabled"), instant(rs, "enabled_at"), instant(rs, "tested_at"), rs.getString("test_url"), rs.getString("test_error"),
                instant(rs, "updated_at"));
    }
}
