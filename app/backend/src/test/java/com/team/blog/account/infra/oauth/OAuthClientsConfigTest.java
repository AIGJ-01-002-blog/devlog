package com.team.blog.account.infra.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Map;

/** 프록시 뒤에서도 GitHub·Google 콜백 주소가 사이트 주소(https)로 나가야 한다. 요청의 http를 따라가면 GitHub이 거절한다. */
class OAuthClientsConfigTest {

    ClientRegistrationRepository repository(Map<String, Object> overrides) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", overrides));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        OAuth2ClientProperties props = Binder.get(env).bind("spring.security.oauth2.client", OAuth2ClientProperties.class).get();
        return new OAuthClientsConfig().clientRegistrationRepository(props, env);
    }

    @Test
    void githubCallbackUsesSiteBaseUrl() throws Exception {
        var repo = repository(Map.of("SITE_BASE_URL", "https://devlog.life"));
        assertThat(repo.findByRegistrationId("github").getRedirectUri())
                .isEqualTo("https://devlog.life/login/oauth2/code/{registrationId}");
    }

    @Test
    void kakaoIsOffWithoutClientId() throws Exception {
        assertThat(repository(Map.of("KAKAO_CLIENT_ID", "")).findByRegistrationId("kakao")).isNull();
    }

    @Test
    void kakaoSendsSecretInBodyAndAsksEmailByDefault() throws Exception {
        // 비밀 파일에 KAKAO_SCOPES= 처럼 빈 줄이 있어도 기본 동의항목을 쓴다
        var kakao = repository(Map.of("SITE_BASE_URL", "https://devlog.life", "KAKAO_CLIENT_ID", "k-id", "KAKAO_CLIENT_SECRET", "k-secret",
                "KAKAO_SCOPES", ""))
                .findByRegistrationId("kakao");
        assertThat(kakao.getRedirectUri()).isEqualTo("https://devlog.life/login/oauth2/code/{registrationId}");
        assertThat(kakao.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(kakao.getScopes()).containsExactlyInAnyOrder("profile_nickname", "profile_image", "account_email");
        assertThat(kakao.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
    }

    @Test
    void kakaoScopesCanDropEmailBeforeBizApp() throws Exception {
        var kakao = repository(Map.of("KAKAO_CLIENT_ID", "k-id", "KAKAO_SCOPES", "profile_nickname, profile_image"))
                .findByRegistrationId("kakao");
        assertThat(kakao.getScopes()).containsExactlyInAnyOrder("profile_nickname", "profile_image");
    }

    @Test
    void kakaoAuthorizeJoinsScopesWithCommaButGoogleKeepsSpaces() throws Exception {
        var repo = repository(Map.of("SITE_BASE_URL", "https://devlog.life", "KAKAO_CLIENT_ID", "k-id", "GOOGLE_CLIENT_ID", "g-id"));
        var resolver = new KakaoScopeResolver(repo);
        var kakao = resolver.resolve(new MockHttpServletRequest("GET", "/oauth2/authorization/kakao"));
        assertThat(kakao.getAuthorizationRequestUri()).startsWith("https://kauth.kakao.com/oauth/authorize?")
                .containsPattern("scope=[a-z_]+,[a-z_]+,[a-z_]+&");
        var google = resolver.resolve(new MockHttpServletRequest("GET", "/oauth2/authorization/google"));
        assertThat(google.getAuthorizationRequestUri()).contains("scope=openid%20profile%20email");
    }

    @Test
    void facebookIsOffWithoutClientId() throws Exception {
        assertThat(repository(Map.of("FACEBOOK_CLIENT_ID", "")).findByRegistrationId("facebook")).isNull();
    }

    @Test
    void facebookUsesVersionedGraphApiAndAsksEmail() throws Exception {
        var facebook = repository(Map.of("SITE_BASE_URL", "https://devlog.life", "FACEBOOK_CLIENT_ID", "f-id",
                "FACEBOOK_CLIENT_SECRET", "f-secret")).findByRegistrationId("facebook");
        assertThat(facebook.getRedirectUri()).isEqualTo("https://devlog.life/login/oauth2/code/{registrationId}");
        assertThat(facebook.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(facebook.getScopes()).containsExactlyInAnyOrder("public_profile", "email");
        var provider = facebook.getProviderDetails();
        assertThat(provider.getAuthorizationUri()).matches("https://www\\.facebook\\.com/v\\d+\\.0/dialog/oauth");
        assertThat(provider.getUserInfoEndpoint().getUri()).matches("https://graph\\.facebook\\.com/v\\d+\\.0/me\\?fields=id,name,email,picture.*");
        assertThat(provider.getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
    }

    @Test
    void naverIsOffWithoutClientId() throws Exception {
        assertThat(repository(Map.of("NAVER_CLIENT_ID", "")).findByRegistrationId("naver")).isNull();
    }

    @Test
    void naverPostsSecretAndReadsResponseWrapper() throws Exception {
        var naver = repository(Map.of("SITE_BASE_URL", "https://devlog.life", "NAVER_CLIENT_ID", "n-id",
                "NAVER_CLIENT_SECRET", "n-secret")).findByRegistrationId("naver");
        assertThat(naver.getRedirectUri()).isEqualTo("https://devlog.life/login/oauth2/code/{registrationId}");
        assertThat(naver.getClientSecret()).isEqualTo("n-secret");
        assertThat(naver.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        var provider = naver.getProviderDetails();
        assertThat(provider.getAuthorizationUri()).isEqualTo("https://nid.naver.com/oauth2.0/authorize");
        assertThat(provider.getTokenUri()).isEqualTo("https://nid.naver.com/oauth2.0/token");
        assertThat(provider.getUserInfoEndpoint().getUri()).isEqualTo("https://openapi.naver.com/v1/nid/me");
        assertThat(provider.getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("response");
    }

    @Test
    void googleCallbackUsesSiteBaseUrlWithoutDoubleSlash() throws Exception {
        var repo = repository(Map.of("SITE_BASE_URL", "https://devlog.life/", "GOOGLE_CLIENT_ID", "g-id"));
        assertThat(repo.findByRegistrationId("google").getRedirectUri())
                .isEqualTo("https://devlog.life/login/oauth2/code/{registrationId}");
    }
}
