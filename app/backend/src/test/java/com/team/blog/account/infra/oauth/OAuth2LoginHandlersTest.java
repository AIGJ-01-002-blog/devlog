package com.team.blog.account.infra.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.team.blog.account.application.SocialProfile;
import com.team.blog.account.domain.AuthProvider;
import com.team.blog.account.web.LoginFlow;

/** spec 038: 소셜 인증 결과를 프로필로 옮기기. 이메일 정리는 SocialProfile 한 곳에서 한다. */
class OAuth2LoginHandlersTest {
    final LoginFlow flow = mock(LoginFlow.class);
    final OAuth2LoginHandlers handlers = new OAuth2LoginHandlers(flow);

    SocialProfile succeed(String registrationId, String nameKey, Map<String, Object> attrs) throws Exception {
        when(flow.complete(any(), any(), any())).thenReturn("/next");
        var token = new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attrs, nameKey), List.of(), registrationId);
        MockHttpServletResponse res = new MockHttpServletResponse();
        handlers.onAuthenticationSuccess(new MockHttpServletRequest(), res, token);
        assertThat(res.getRedirectedUrl()).isEqualTo("/next");
        ArgumentCaptor<SocialProfile> profile = ArgumentCaptor.forClass(SocialProfile.class);
        verify(flow).complete(profile.capture(), any(), any());
        return profile.getValue();
    }

    @Test
    void githubProfileUsesNumericIdAndVerifiedEmail() throws Exception {
        SocialProfile p = succeed("github", "id", Map.of("id", 583231, "login", "Octo", "name", "Mona",
                GithubOAuth2UserService.VERIFIED_EMAIL, " Mona@Example.COM ", "avatar_url", "https://avatars.githubusercontent.com/u/1"));
        assertThat(p).isEqualTo(new SocialProfile(AuthProvider.GITHUB, "583231", "Octo", "Mona", "mona@example.com",
                "https://avatars.githubusercontent.com/u/1"));
    }

    @Test
    void googleEmailCountsOnlyWhenVerified() throws Exception {
        Map<String, Object> attrs = new HashMap<>(Map.of("sub", "g-1", "name", "민서", "email", " MinSeo@Gmail.com",
                "email_verified", true, "picture", "https://lh3.googleusercontent.com/a"));
        assertThat(succeed("google", "sub", attrs).verifiedEmail()).isEqualTo("minseo@gmail.com");
    }

    @Test
    void googleUnverifiedOrMissingEmailIsNull() throws Exception {
        assertThat(succeed("google", "sub", Map.of("sub", "g-2", "email", "a@b.com", "email_verified", false)).verifiedEmail()).isNull();
    }

    @Test
    void googleVerifiedWithoutEmailDoesNotFail() throws Exception {
        // 예전에는 email 없이 email_verified만 오면 NPE로 로그인 전체가 500이었다
        assertThat(succeed("google", "sub", Map.of("sub", "g-3", "email_verified", true)).verifiedEmail()).isNull();
    }

    @Test
    void kakaoUsesNumericIdAndOnlyValidVerifiedEmail() throws Exception {
        Map<String, Object> attrs = Map.of("id", 4012345678L, "kakao_account", Map.of(
                "email", "MinSeo@Kakao.com", "is_email_valid", true, "is_email_verified", true,
                "profile", Map.of("nickname", "민서", "profile_image_url", "http://k.kakaocdn.net/dn/abc/img_640x640.jpg",
                        "is_default_image", false)));
        assertThat(succeed("kakao", "id", attrs)).isEqualTo(new SocialProfile(AuthProvider.KAKAO, "4012345678", null, "민서",
                "minseo@kakao.com", "https://k.kakaocdn.net/dn/abc/img_640x640.jpg"));
    }

    @Test
    void kakaoUnverifiedEmailAndDefaultPhotoAreDropped() throws Exception {
        Map<String, Object> attrs = Map.of("id", 7L, "kakao_account", Map.of(
                "email", "a@b.com", "is_email_valid", true, "is_email_verified", false,
                "profile", Map.of("nickname", "n", "profile_image_url", "https://k.kakaocdn.net/dn/default.jpg", "is_default_image", true)));
        SocialProfile p = succeed("kakao", "id", attrs);
        assertThat(p.verifiedEmail()).isNull();
        assertThat(p.avatarUrl()).isNull();
    }

    @Test
    void kakaoWithoutAccountConsentStillLogsIn() throws Exception {
        // 이메일·프로필 동의를 하나도 안 하면 kakao_account가 비어 온다. 그래도 가입 마무리 화면으로 넘어가야 한다
        SocialProfile p = succeed("kakao", "id", Map.of("id", 8L));
        assertThat(p.providerUserId()).isEqualTo("8");
        assertThat(p.verifiedEmail()).isNull();
        assertThat(p.name()).isNull();
    }

    @Test
    void facebookUsesAppScopedIdEmailAndRealPhoto() throws Exception {
        Map<String, Object> attrs = Map.of("id", "10229876543210", "name", "Kim Minseo", "email", "MinSeo@Example.com",
                "picture", Map.of("data", Map.of("url", "https://platform-lookaside.fbsbx.com/platform/profilepic/?asid=1&height=512",
                        "is_silhouette", false)));
        assertThat(succeed("facebook", "id", attrs)).isEqualTo(new SocialProfile(AuthProvider.FACEBOOK, "10229876543210", null,
                "Kim Minseo", "minseo@example.com", "https://platform-lookaside.fbsbx.com/platform/profilepic/?asid=1&height=512"));
    }

    @Test
    void facebookWithoutEmailAndSilhouetteStillLogsIn() throws Exception {
        // 휴대폰 번호로만 가입했거나 이메일 제공을 끄면 email이 오지 않는다. 기본 실루엣 사진은 넘기지 않는다
        SocialProfile p = succeed("facebook", "id", Map.of("id", "42", "name", "n",
                "picture", Map.of("data", Map.of("url", "https://platform-lookaside.fbsbx.com/x", "is_silhouette", true))));
        assertThat(p.providerUserId()).isEqualTo("42");
        assertThat(p.verifiedEmail()).isNull();
        assertThat(p.avatarUrl()).isNull();
    }

    @Test
    void naverReadsResponseWrapperAndUsesEmailAndUploadedPhoto() throws Exception {
        Map<String, Object> attrs = Map.of("resultcode", "00", "message", "success", "response", Map.of(
                "id", "32742776", "nickname", "민서", "name", "김민서", "email", "MinSeo@naver.com",
                "profile_image", "https://phinf.pstatic.net/contact/20231001_1/abc.png"));
        assertThat(succeed("naver", "response", attrs)).isEqualTo(new SocialProfile(AuthProvider.NAVER, "32742776", null,
                "민서", "minseo@naver.com", "https://phinf.pstatic.net/contact/20231001_1/abc.png"));
    }

    @Test
    void naverWithoutNicknameOrEmailUsesNameAndStillLogsIn() throws Exception {
        // 동의 화면에서 별명·이메일 제공을 끄면 오지 않는다. 이름을 닉네임 자리에 쓰고 이메일은 가입 때 받는다
        SocialProfile p = succeed("naver", "response", Map.of("response", Map.of("id", "n-1", "name", "김민서")));
        assertThat(p.providerUserId()).isEqualTo("n-1");
        assertThat(p.name()).isEqualTo("김민서");
        assertThat(p.verifiedEmail()).isNull();
    }

    @Test
    void failureGoesBackToLoginWithCode() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        handlers.onAuthenticationFailure(new MockHttpServletRequest(), res, new BadCredentialsException("x"));
        assertThat(res.getRedirectedUrl()).isEqualTo("/login?error=SOCIAL_LOGIN_FAILED");
    }
}
