package com.team.blog.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.team.blog.account.infra.AuthIdentityRepository;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.account.infra.oauth.GithubOAuth2UserService;
import com.team.blog.account.infra.oauth.KakaoScopeResolver;
import com.team.blog.account.infra.oauth.OAuth2LoginHandlers;
import com.team.blog.account.web.AccountStateFilter;
import com.team.blog.account.web.LoginGuardFilter;
import com.team.blog.mcp.web.McpController;
import com.team.blog.mcp.web.McpUploadController;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.web.ClientIpResolver;
import com.team.blog.shared.web.RateLimiter;

/**
 * 세션 쿠키 + CSRF 토큰 (docs/02 §5, 2026-10-07 Q2·H7). 권한의 최종 판정은 Service 계층이 하고(헌법 II),
 * 여기서는 "로그인이 필요한가"(docs/42 §3 ①)와 계정 상태(②)만 거른다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(BlogProperties.class)
public class SecurityConfig {
    private static final String API_PATHS = "/api/**";

    /** 로그인하지 않아도 보낼 수 있는 쓰기 요청 (가입 대기·로그인·공개 기록 API). */
    static final String[] PUBLIC_WRITES = {
            "/api/auth/signup", "/api/auth/redirect", "/api/dev/login",
            "/api/auth/login", "/api/auth/signup/email", "/api/auth/signup/email-code", "/api/auth/signup/email-code/verify",
            "/api/auth/email/verify", "/api/auth/email/resend",
            "/api/auth/password/reset-request", "/api/auth/password/reset", "/api/auth/password/reset-check",
            "/api/markdown/preview-public", "/api/posts/*/views", "/api/visits"
    };

    /** 화면 빌드 결과와 이름이 고정된 정적 파일. 로그인·세션·CSRF가 필요 없다 */
    static final String[] STATIC_FILES = {"/assets/**", "/theme.js", "/favicon.svg", "/apple-touch-icon.png", "/og-default.png"};

    /**
     * 정적 파일은 세션도 CSRF 토큰도 보지 않는다. 기본 사슬은 응답마다 XSRF-TOKEN 쿠키를 새로 심는데,
     * 쿠키를 심는 응답은 Cloudflare가 캐시하지 않아(BYPASS) 처음 온 방문자의 JS·CSS·글꼴이 모두 학교 서버까지 갔다.
     * 캐시 머리글은 WebConfig가 파일마다 정하므로 Spring Security 기본값(no-store)은 끈다.
     */
    @Bean
    @Order(-1)
    SecurityFilterChain staticFilterChain(HttpSecurity http, ContentSecurityPolicy csp) throws Exception {
        http
                .securityMatcher(STATIC_FILES)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(h -> h.cacheControl(c -> c.disable()).contentTypeOptions(c -> {}).frameOptions(f -> f.deny()).addHeaderWriter(csp));
        return http.build();
    }

    /**
     * MCP 서버와 OAuth 토큰·등록 (052): 세션 쿠키도 CSRF도 쓰지 않는다. MCP는 접근 토큰(Bearer)만 보고, 토큰 확인은 컨트롤러가 한다.
     * AI용 사진 올리기 주소(060)는 한 번 쓰는 표가 곧 권한이다.
     * 쿠키를 보지 않으니 다른 사이트가 로그인한 브라우저로 보내는 요청(CSRF)은 통하지 않는다.
     */
    @Bean
    @Order(0)
    SecurityFilterChain mcpFilterChain(HttpSecurity http, ContentSecurityPolicy csp) {
        http
                .securityMatcher(McpController.PATH, McpUploadController.PATH, "/api/oauth/token", "/api/oauth/register")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(h -> h.contentTypeOptions(c -> {}).frameOptions(f -> f.deny()).addHeaderWriter(csp));
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(HttpSecurity http, MemberRepository members, AuthIdentityRepository identities,
                                            GithubOAuth2UserService githubUsers, KakaoScopeResolver authorizationRequests,
                                            OAuth2LoginHandlers loginHandlers, RateLimiter rateLimiter,
                                            ClientIpResolver ipResolver, StringRedisTemplate redis, BlogProperties props,
                                            ContentSecurityPolicy csp) {
        RequestMatcher api = request -> request.getRequestURI().startsWith("/api/");
        http
                .csrf(csrf -> csrf.spa())
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/me/**").authenticated()
                        .requestMatchers(HttpMethod.POST, PUBLIC_WRITES).permitAll()
                        .requestMatchers(HttpMethod.GET, API_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, API_PATHS).permitAll()
                        .requestMatchers(API_PATHS).authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(SecurityConfig::unauthorized, api)
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), request -> true)
                        // 관리자·매니저 전용 주소에 일반 회원이 오면 404로 숨긴다 (docs/42 P-10, 062)
                        .accessDeniedHandler((req, res, ex) -> {
                            if (req.getRequestURI().startsWith("/api/admin/")) notFound(res);
                            else forbidden(res);
                        }))
                .oauth2Login(o -> o
                        .loginPage("/login")
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(authorizationRequests))
                        .userInfoEndpoint(u -> u.userService(githubUsers))
                        .successHandler(loginHandlers)
                        .failureHandler(loginHandlers))
                .logout(l -> l
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("BLOGSESSION")
                        .invalidateHttpSession(true))
                .headers(h -> h
                        .contentTypeOptions(c -> {})
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(csp))
                .addFilterBefore(new LoginGuardFilter(rateLimiter, ipResolver, redis, props), OAuth2AuthorizationRequestRedirectFilter.class)
                .addFilterBefore(new AccountStateFilter(members, identities), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** 비밀번호는 되돌릴 수 없는 BCrypt로만 저장한다 (004 FR-017). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
                                     org.springframework.security.core.AuthenticationException e) throws IOException {
        write(response, 401, "LOGIN_REQUIRED", "로그인이 필요해요.");
    }

    private static void notFound(HttpServletResponse response) throws IOException {
        write(response, 404, "NOT_FOUND", "볼 수 없는 페이지예요.");
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        write(response, 403, "FORBIDDEN", "요청을 처리할 수 없어요. 새로 고친 뒤 다시 시도해 주세요.");
    }

    private static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"errors\":[],\"details\":null}");
    }
}
