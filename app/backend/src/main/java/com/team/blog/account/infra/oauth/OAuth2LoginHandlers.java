package com.team.blog.account.infra.oauth;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.team.blog.account.application.SocialProfile;
import com.team.blog.account.domain.AuthProvider;
import com.team.blog.account.web.LoginFlow;

/** 소셜(GitHub·Google·카카오·Facebook·네이버) OAuth2 성공·실패 처리. 성공하면 우리 로그인 흐름(LoginFlow)으로 넘긴다. */
@Component
public class OAuth2LoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final String EMAIL = "email";

    private final LoginFlow loginFlow;

    public OAuth2LoginHandlers(LoginFlow loginFlow) {
        this.loginFlow = loginFlow;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OAuth2User user = Objects.requireNonNull(token.getPrincipal(), "로그인한 사용자 정보가 없습니다");
        SocialProfile profile = switch (token.getAuthorizedClientRegistrationId()) {
            case "github" -> new SocialProfile(AuthProvider.GITHUB,
                    String.valueOf(user.getAttributes().get("id")),
                    str(user.getAttributes().get("login")),
                    str(user.getAttributes().get("name")),
                    str(user.getAttributes().get(GithubOAuth2UserService.VERIFIED_EMAIL)),
                    str(user.getAttributes().get("avatar_url")));
            case "google" -> new SocialProfile(AuthProvider.GOOGLE,
                    str(user.getAttributes().get("sub")),
                    null,
                    str(user.getAttributes().get("name")),
                    Boolean.TRUE.equals(user.getAttributes().get("email_verified")) ? str(user.getAttributes().get(EMAIL)) : null,
                    str(user.getAttributes().get("picture")));
            case "kakao" -> kakao(user.getAttributes());
            case "facebook" -> facebook(user.getAttributes());
            case "naver" -> naver(user.getAttributes());
            default -> throw new IllegalStateException("지원하지 않는 로그인 수단");
        };
        response.sendRedirect(loginFlow.complete(profile, request, response));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.sendRedirect("/login?error=SOCIAL_LOGIN_FAILED");
    }

    /**
     * 카카오 사용자 정보 (spec 080). 이메일은 유효하고(is_email_valid) 인증된(is_email_verified) 것만 쓴다.
     * 기본 프로필 사진(is_default_image)은 넘기지 않고, 예전 계정이 주는 http 사진 주소는 https로 바꾼다.
     */
    static SocialProfile kakao(Map<String, Object> attrs) {
        Map<?, ?> account = attrs.get("kakao_account") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> profile = account.get("profile") instanceof Map<?, ?> m ? m : Map.of();
        boolean emailOk = Boolean.TRUE.equals(account.get("is_email_valid")) && Boolean.TRUE.equals(account.get("is_email_verified"));
        String photo = Boolean.TRUE.equals(profile.get("is_default_image")) ? null : str(profile.get("profile_image_url"));
        if (photo != null && photo.startsWith("http://k.kakaocdn.net/")) photo = "https://" + photo.substring("http://".length());
        return new SocialProfile(AuthProvider.KAKAO,
                String.valueOf(attrs.get("id")),
                null,
                str(profile.get("nickname")),
                emailOk ? str(account.get(EMAIL)) : null,
                photo);
    }

    /**
     * Facebook 사용자 정보 (spec 083). 사용자 식별은 앱마다 다른 숫자 id다. 이메일은 Facebook이 확인을 마친 주소만 주므로 그대로 쓰고,
     * 휴대폰 번호로만 가입했거나 이메일 제공을 끄면 오지 않는다(가입 마무리 화면에서 받는다). 기본 실루엣 사진(is_silhouette)은 넘기지 않는다.
     */
    static SocialProfile facebook(Map<String, Object> attrs) {
        Map<?, ?> picture = attrs.get("picture") instanceof Map<?, ?> m && m.get("data") instanceof Map<?, ?> d ? d : Map.of();
        String photo = Boolean.TRUE.equals(picture.get("is_silhouette")) ? null : str(picture.get("url"));
        return new SocialProfile(AuthProvider.FACEBOOK,
                str(attrs.get("id")),
                null,
                str(attrs.get("name")),
                str(attrs.get(EMAIL)),
                photo);
    }

    /**
     * 네이버 사용자 정보 (spec 096). 값은 "response" 안에 온다. 사용자 식별은 앱마다 다른 id 문자열이다.
     * 이메일은 네이버가 본인 확인을 마친 연락처 이메일이라 그대로 쓰고, 동의 화면에서 이메일 제공을 끄면 오지 않는다(가입 마무리 화면에서 받는다).
     * 닉네임이 없으면 이름을 쓴다. 사진은 회원이 올린 사진 서버(phinf.pstatic.net) 주소만 넘기므로 네이버 기본 사진은 빠진다.
     */
    static SocialProfile naver(Map<String, Object> attrs) {
        Map<?, ?> r = attrs.get("response") instanceof Map<?, ?> m ? m : Map.of();
        String nickname = str(r.get("nickname"));
        return new SocialProfile(AuthProvider.NAVER,
                str(r.get("id")),
                null,
                nickname == null || nickname.isBlank() ? str(r.get("name")) : nickname,
                str(r.get(EMAIL)),
                str(r.get("profile_image")));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
