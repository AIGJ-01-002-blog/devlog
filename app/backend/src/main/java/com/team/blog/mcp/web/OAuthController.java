package com.team.blog.mcp.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.mcp.application.AccessTokens;
import com.team.blog.mcp.application.OAuthServer;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;

/**
 * MCP OAuth (052): 메타데이터(RFC 9728·8414), 동적 등록(RFC 7591), 동의 화면 API, 토큰 교환.
 * 동의 화면은 React(/oauth/authorize)가 그리고, 로그인 세션 + CSRF로 [허용]을 보낸다.
 * 등록·토큰 교환은 앱의 서버가 직접 부르므로 세션·CSRF 없는 MCP 보안 체인에 둔다.
 */
@RestController
public class OAuthController {
    private static final String AUTHORIZATION_CODE = "authorization_code";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String SLOW_DOWN = "slow_down";

    private final OAuthServer oauth;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver ips;

    public OAuthController(OAuthServer oauth, RateLimiter rateLimiter, ClientIpResolver ips) {
        this.oauth = oauth;
        this.rateLimiter = rateLimiter;
        this.ips = ips;
    }

    public record RegisterRequest(String client_name, List<String> redirect_uris) {}

    public record AuthorizeRequest(String clientId, String redirectUri, String responseType, String codeChallenge,
                                   String codeChallengeMethod, String scope, String state, String resource, boolean approve) {}

    /** 동의 화면에 보일 정보 */
    public record AuthorizeView(String clientName, String redirectHost, AccessTokens.Scope scope) {}

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/mcp"})
    public ResponseEntity<Map<String, Object>> protectedResource() {
        return metadata(Map.of("resource", oauth.resource(), "authorization_servers", List.of(oauth.issuer()),
                "scopes_supported", List.of(OAuthServer.SCOPE_READ, OAuthServer.SCOPE_WRITE),
                "bearer_methods_supported", List.of("header"), "resource_name", "devlog"));
    }

    @GetMapping({"/.well-known/oauth-authorization-server", "/.well-known/oauth-authorization-server/api/mcp"})
    public ResponseEntity<Map<String, Object>> authorizationServer() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", oauth.issuer());
        m.put("authorization_endpoint", oauth.issuer() + "/oauth/authorize");
        m.put("token_endpoint", oauth.issuer() + "/api/oauth/token");
        m.put("registration_endpoint", oauth.issuer() + "/api/oauth/register");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of(AUTHORIZATION_CODE, REFRESH_TOKEN));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none"));
        m.put("scopes_supported", List.of(OAuthServer.SCOPE_READ, OAuthServer.SCOPE_WRITE));
        m.put("authorization_response_iss_parameter_supported", true);
        return metadata(m);
    }

    @PostMapping(value = "/api/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest body, HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("oauth-register:" + ips.resolve(request), 20, Duration.ofHours(1))) {
            throw new OAuthServer.OAuthError(SLOW_DOWN, "등록 요청이 너무 많아요. 잠시 뒤 다시 시도해 주세요.", false);
        }
        OAuthServer.Client c = oauth.register(body == null ? null : body.client_name(), body == null ? null : body.redirect_uris());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", c.clientId());
        out.put("client_name", c.clientName());
        out.put("redirect_uris", c.redirectUris());
        out.put("grant_types", List.of(AUTHORIZATION_CODE, REFRESH_TOKEN));
        out.put("response_types", List.of("code"));
        out.put("token_endpoint_auth_method", "none");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(out);
    }

    /** 동의 화면이 먼저 부른다: 요청을 검사하고 앱 이름·돌아갈 곳·권한을 알려 준다 */
    @GetMapping("/api/oauth/authorize")
    public ResponseEntity<AuthorizeView> view(@RequestParam(name = "client_id", required = false) String clientId,
                                              @RequestParam(name = "redirect_uri", required = false) String redirectUri,
                                              @RequestParam(name = "response_type", required = false) String responseType,
                                              @RequestParam(name = "code_challenge", required = false) String challenge,
                                              @RequestParam(name = "code_challenge_method", required = false) String method,
                                              @RequestParam(required = false) String scope,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String resource) {
        OAuthServer.Request req = oauth.validate(
                new OAuthServer.Authorize(clientId, redirectUri, responseType, challenge, method, scope, state, resource));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthorizeView(req.client().clientName(), java.net.URI.create(req.redirectUri()).getHost(), req.scope()));
    }

    /** [허용]·[거부]. 로그인한 회원만. 앱으로 돌아갈 주소를 돌려주면 화면이 그리로 이동한다 */
    @PostMapping("/api/oauth/authorize")
    public Map<String, String> decide(@CurrentMember MemberPrincipal me, @RequestBody AuthorizeRequest body) {
        OAuthServer.Request req;
        try {
            req = oauth.validate(new OAuthServer.Authorize(body.clientId(), body.redirectUri(), body.responseType(), body.codeChallenge(),
                    body.codeChallengeMethod(), body.scope(), body.state(), body.resource()));
        } catch (OAuthServer.OAuthError e) {
            if (!e.redirectable) throw e;
            return Map.of("redirect", oauth.errorRedirect(body.redirectUri(), e.error, body.state()));
        }
        return Map.of("redirect", body.approve() ? oauth.approve(me.id(), req) : oauth.deny(req));
    }

    @PostMapping(value = "/api/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam(name = "grant_type", required = false) String grantType,
                                                     @RequestParam(required = false) String code,
                                                     @RequestParam(name = "redirect_uri", required = false) String redirectUri,
                                                     @RequestParam(name = "client_id", required = false) String clientId,
                                                     @RequestParam(name = "code_verifier", required = false) String verifier,
                                                     @RequestParam(name = REFRESH_TOKEN, required = false) String refreshToken,
                                                     HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("oauth-token:" + ips.resolve(request), 60, Duration.ofMinutes(1))) {
            throw new OAuthServer.OAuthError(SLOW_DOWN, "요청이 너무 많아요.", false);
        }
        // client_secret_basic으로 보내는 앱은 client_id를 본문 대신 Authorization: Basic에 담는다. 비밀값은 보지 않는다(공개 클라이언트)
        if (clientId == null || clientId.isBlank()) clientId = basicClientId(request.getHeader("Authorization"));
        AccessTokens.OAuthGrant g = switch (grantType == null ? "" : grantType) {
            case AUTHORIZATION_CODE -> oauth.exchange(code, clientId, redirectUri, verifier);
            case REFRESH_TOKEN -> oauth.refresh(refreshToken, clientId);
            default -> throw new OAuthServer.OAuthError("unsupported_grant_type", "authorization_code·refresh_token만 받아요.", false);
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", g.accessToken());
        out.put("token_type", "Bearer");
        out.put("expires_in", g.expiresInSeconds());
        out.put(REFRESH_TOKEN, g.refreshToken());
        out.put("scope", OAuthServer.scopeString(g.scope()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(out);
    }

    /** OAuth 형식 오류 응답 {error, error_description}. 화면(동의 API)도 같은 모양을 읽는다 */
    @ExceptionHandler(OAuthServer.OAuthError.class)
    public ResponseEntity<Map<String, String>> error(OAuthServer.OAuthError e) {
        // invalid_client도 400: 공개 클라이언트라 클라이언트 인증이 없고, 화면이 401을 "로그인 필요"로 읽지 않게
        HttpStatus status = SLOW_DOWN.equals(e.error) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("error", e.error, "error_description", e.getMessage(), "code", e.error, "message", e.getMessage()));
    }

    /** Authorization: Basic base64(client_id:secret)에서 client_id만 꺼낸다 (RFC 6749 2.3.1: 각 부분은 form 인코딩) */
    static String basicClientId(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(authorization.substring(6).trim()), java.nio.charset.StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String id = java.net.URLDecoder.decode(colon < 0 ? decoded : decoded.substring(0, colon), java.nio.charset.StandardCharsets.UTF_8);
            return id.isBlank() ? null : id;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> metadata(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }
}
