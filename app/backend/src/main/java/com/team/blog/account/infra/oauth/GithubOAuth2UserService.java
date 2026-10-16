package com.team.blog.account.infra.oauth;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * GitHub 사용자 정보 + 인증된 대표 이메일 (docs/07 §5: user:email 권한으로 primary·verified 이메일만 쓴다).
 */
@Component
public class GithubOAuth2UserService extends DefaultOAuth2UserService {
    public static final String VERIFIED_EMAIL = "blog_verified_email";
    private final RestClient rest;

    @Autowired
    public GithubOAuth2UserService() {
        this("https://api.github.com");
    }

    /** 테스트가 가짜 GitHub API 주소를 넣는다 */
    GithubOAuth2UserService(String apiBaseUrl) {
        // 시간 제한이 없으면 GitHub가 느릴 때 로그인 요청이 끝없이 묶인다
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        // 부모의 /user 요청도 같은 시간 제한을 쓴다. 오류 처리기는 부모 기본값과 같게 둔다
        RestTemplate userInfo = new RestTemplate(factory);
        userInfo.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        setRestOperations(userInfo);
        this.rest = RestClient.builder().baseUrl(apiBaseUrl).requestFactory(factory).build();
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User user = Objects.requireNonNull(super.loadUser(userRequest), "GitHub 사용자 정보가 없습니다");
        if (!"github".equals(userRequest.getClientRegistration().getRegistrationId())) return user;
        Map<String, Object> attrs = new HashMap<>(user.getAttributes());
        attrs.put(VERIFIED_EMAIL, primaryVerifiedEmail(userRequest.getAccessToken().getTokenValue()));
        return new DefaultOAuth2User(user.getAuthorities(), attrs, "id");
    }

    private String primaryVerifiedEmail(String token) {
        try {
            List<Map<String, Object>> emails = rest.get().uri("/user/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (emails == null) return null;
            for (Map<String, Object> e : emails) {
                if (Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified"))) {
                    Object email = e.get("email");
                    return email == null ? null : email.toString();
                }
            }
            return null;
        } catch (RestClientException e) {
            return null;
        }
    }
}
