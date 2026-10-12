package com.team.blog.mcp.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import com.team.blog.shared.config.BlogProperties;

/**
 * MCP용 OAuth 2.1 인가 서버 (052). ChatGPT 커넥터처럼 토큰을 붙여 넣을 수 없는 앱이 쓴다.
 * 공개 클라이언트 + PKCE(S256)만 받는다(비밀값 없음). 앱은 동적 등록(RFC 7591)으로 스스로 등록하고,
 * 회원이 devlog 동의 화면에서 [허용]을 눌러야 코드가 나온다. 코드는 5분, 한 번만 쓴다.
 * 발급하는 토큰은 개인 토큰과 같은 표에 두어 같은 권한 검사·요청 제한·폐기를 따른다.
 */
@Service
public class OAuthServer {
    public static final String SCOPE_READ = "devlog.read";
    public static final String SCOPE_WRITE = "devlog.write";
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final String CODE_KEY = "oauth:code:";
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String INVALID_GRANT = "invalid_grant";
    private static final int MAX_REDIRECTS = 10;
    /** RFC 7636: code_verifier와 code_challenge 모두 unreserved 문자 43~128자 */
    private static final String PKCE_PATTERN = "[A-Za-z0-9._~-]{43,128}";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Client(String clientId, String clientName, List<String> redirectUris) {}

    /** 동의 화면에 보일 요청. 검사를 통과한 값만 담는다 */
    public record Request(Client client, String redirectUri, String codeChallenge, AccessTokens.Scope scope, String state) {}

    /** 잘못된 요청. redirectable이면 앱으로 돌려보내 알리고, 아니면(앱·주소가 틀림) 화면에서 알린다 */
    public static class OAuthError extends RuntimeException {
        public final String error;
        public final boolean redirectable;

        public OAuthError(String error, String description, boolean redirectable) {
            super(description);
            this.error = error;
            this.redirectable = redirectable;
        }
    }

    private record CodeData(long memberId, String clientId, String redirectUri, String codeChallenge, String scope) {}

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AccessTokens tokens;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String baseUrl;

    public OAuthServer(JdbcTemplate jdbc, StringRedisTemplate redis, AccessTokens tokens, BlogProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.tokens = tokens;
        String b = props.site().baseUrl() == null ? "" : props.site().baseUrl();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** 발급자(issuer). 메타데이터·authorization_servers·응답의 iss가 모두 이 값이다 */
    public String issuer() {
        return baseUrl;
    }

    /** MCP 서버의 자원 식별자. 앱이 resource 매개변수로 보낸다 */
    public String resource() {
        return baseUrl + "/api/mcp";
    }

    /** 동적 등록. 돌아갈 주소는 https 또는 내 컴퓨터(localhost) http만 받는다. */
    public Client register(String rawName, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECTS) {
            throw new OAuthError("invalid_redirect_uri", "redirect_uris가 필요해요.", false);
        }
        Set<String> uris = new LinkedHashSet<>();
        for (String u : redirectUris) {
            if (!allowedRedirect(u)) throw new OAuthError("invalid_redirect_uri", "https 주소만 쓸 수 있어요: " + u, false);
            uris.add(u);
        }
        String name = rawName == null || rawName.isBlank() ? "AI 앱" : rawName.strip();
        if (name.length() > 100) name = name.substring(0, 100);
        String clientId = "dvc_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO oauth_client (client_id, client_name, redirect_uris) VALUES (?, ?, ?)",
                clientId, name, String.join("\n", uris));
        return new Client(clientId, name, List.copyOf(uris));
    }

    public Optional<Client> client(String clientId) {
        if (clientId == null || clientId.length() > 64) return Optional.empty();
        return jdbc.query("SELECT client_id, client_name, redirect_uris FROM oauth_client WHERE client_id = ?",
                (rs, i) -> new Client(rs.getString(1), rs.getString(2), Arrays.asList(rs.getString(3).split("\n"))), clientId)
                .stream().findFirst();
    }

    /** 인가 요청 검사. 앱과 돌아갈 주소가 맞아야 그 뒤 오류를 앱으로 돌려보낼 수 있다. */
    public Request validate(String clientId, String redirectUri, String responseType, String codeChallenge,
                            String challengeMethod, String scope, String state, String resource) {
        Client client = client(clientId).orElseThrow(() -> new OAuthError("invalid_client", "등록되지 않은 앱이에요.", false));
        if (redirectUri == null || !client.redirectUris().contains(redirectUri)) {
            throw new OAuthError(INVALID_REQUEST, "등록되지 않은 돌아갈 주소예요.", false);
        }
        if (!"code".equals(responseType)) throw new OAuthError("unsupported_response_type", "response_type=code만 받아요.", true);
        if (codeChallenge == null || !codeChallenge.matches(PKCE_PATTERN) || !"S256".equals(challengeMethod)) {
            throw new OAuthError(INVALID_REQUEST, "PKCE(S256)가 필요해요.", true);
        }
        if (resource != null && !resource.isBlank() && !resource.equals(resource())) {
            throw new OAuthError("invalid_target", "이 서버의 자원이 아니에요.", true);
        }
        return new Request(client, redirectUri, codeChallenge, parseScope(scope), state);
    }

    /** 회원이 [허용]을 누르면 코드를 만들어 돌아갈 주소를 돌려준다. */
    public String approve(long memberId, Request req) {
        String code = "dvo_" + random(32);
        CodeData data = new CodeData(memberId, req.client().clientId(), req.redirectUri(), req.codeChallenge(), req.scope().name());
        redis.opsForValue().set(CODE_KEY + AccessTokens.hash(code), json.writeValueAsString(data), CODE_TTL);
        return redirect(req.redirectUri(), "code", code, req.state());
    }

    public String deny(Request req) {
        return redirect(req.redirectUri(), "error", "access_denied", req.state());
    }

    /** 오류를 앱으로 돌려보내는 주소 */
    public String errorRedirect(String redirectUri, String error, String state) {
        return redirect(redirectUri, "error", error, state);
    }

    /** 코드 교환 (grant_type=authorization_code). 코드는 맞든 틀리든 한 번 꺼내면 사라진다. */
    public AccessTokens.OAuthGrant exchange(String code, String clientId, String redirectUri, String verifier) {
        if (code == null || verifier == null) throw new OAuthError(INVALID_REQUEST, "code와 code_verifier가 필요해요.", false);
        if (!verifier.matches(PKCE_PATTERN)) throw new OAuthError(INVALID_REQUEST, "code_verifier는 43~128자예요 (RFC 7636).", false);
        String raw = redis.opsForValue().getAndDelete(CODE_KEY + AccessTokens.hash(code));
        if (raw == null) throw new OAuthError(INVALID_GRANT, "코드가 없거나 이미 썼거나 만료됐어요.", false);
        CodeData data = json.readValue(raw, CodeData.class);
        if (!data.clientId().equals(clientId) || !data.redirectUri().equals(redirectUri)) {
            throw new OAuthError(INVALID_GRANT, "코드를 받은 앱·주소와 달라요.", false);
        }
        if (!MessageDigest.isEqual(s256(verifier).getBytes(StandardCharsets.US_ASCII), data.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
            throw new OAuthError(INVALID_GRANT, "code_verifier가 맞지 않아요.", false);
        }
        Client client = client(clientId).orElseThrow(() -> new OAuthError("invalid_client", "등록되지 않은 앱이에요.", false));
        return tokens.issueOAuth(data.memberId(), clientId, client.clientName(), AccessTokens.Scope.valueOf(data.scope()));
    }

    public AccessTokens.OAuthGrant refresh(String refreshToken, String clientId) {
        return tokens.refreshOAuth(refreshToken, clientId)
                .orElseThrow(() -> new OAuthError(INVALID_GRANT, "갱신 토큰이 없거나 폐기·만료됐어요.", false));
    }

    public static String scopeString(AccessTokens.Scope scope) {
        return scope == AccessTokens.Scope.WRITE ? SCOPE_READ + " " + SCOPE_WRITE : SCOPE_READ;
    }

    /** 쓰기는 devlog.write를 요청했을 때만 준다. 범위가 없거나 모르는 값이면 읽기만 (가장 좁은 권한) */
    static AccessTokens.Scope parseScope(String scope) {
        if (scope == null || scope.isBlank()) return AccessTokens.Scope.READ;
        List<String> parts = Arrays.asList(scope.trim().split("\\s+"));
        return parts.contains(SCOPE_WRITE) ? AccessTokens.Scope.WRITE : AccessTokens.Scope.READ;
    }

    static boolean allowedRedirect(String uri) {
        if (uri == null || uri.length() > 500) return false;
        try {
            URI u = new URI(uri);
            if (u.getFragment() != null || u.getHost() == null) return false;
            if ("https".equals(u.getScheme())) return true;
            return "http".equals(u.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(u.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String redirect(String redirectUri, String key, String value, String state) {
        StringBuilder out = new StringBuilder(redirectUri).append(redirectUri.contains("?") ? '&' : '?')
                .append(key).append('=').append(enc(value));
        if (state != null && !state.isEmpty()) out.append("&state=").append(enc(state));
        // 발급자 표시 (RFC 9207): 앱이 다른 서버의 응답과 헷갈리지 않게
        out.append("&iss=").append(enc(issuer()));
        return out.toString();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
