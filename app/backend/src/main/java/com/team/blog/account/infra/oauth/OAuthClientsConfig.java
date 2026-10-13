package com.team.blog.account.infra.oauth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * 소셜 로그인 앱 등록. GitHub은 application.yml, Google은 GOOGLE_CLIENT_ID, 카카오는 KAKAO_CLIENT_ID, Facebook은 FACEBOOK_CLIENT_ID,
 * 네이버는 NAVER_CLIENT_ID에 값이 있을 때만 켠다 (004, 080, 083, 096).
 * 비밀 파일에 값이 빈 채로 있어도 앱이 뜨고, 화면은 /api/auth/providers를 보고 그 버튼을 숨긴다.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OAuthClientsConfig {
    private static final String REDIRECT_PATH = "/login/oauth2/code/{registrationId}";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties, Environment env) {
        List<ClientRegistration> all = new ArrayList<>(new OAuth2ClientPropertiesMapper(properties).asClientRegistrations().values());
        String googleId = env.getProperty("GOOGLE_CLIENT_ID");
        if (StringUtils.hasText(googleId) && all.stream().noneMatch(r -> r.getRegistrationId().equals("google"))) {
            all.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(googleId)
                    .clientSecret(env.getProperty("GOOGLE_CLIENT_SECRET", ""))
                    .scope("openid", "profile", "email")
                    .redirectUri(siteBaseUrl(env) + REDIRECT_PATH)
                    .build());
        }
        String kakaoId = env.getProperty("KAKAO_CLIENT_ID");
        if (StringUtils.hasText(kakaoId) && all.stream().noneMatch(r -> r.getRegistrationId().equals("kakao"))) {
            all.add(kakao(kakaoId, env));
        }
        String facebookId = env.getProperty("FACEBOOK_CLIENT_ID");
        if (StringUtils.hasText(facebookId) && all.stream().noneMatch(r -> r.getRegistrationId().equals("facebook"))) {
            all.add(facebook(facebookId, env));
        }
        String naverId = env.getProperty("NAVER_CLIENT_ID");
        if (StringUtils.hasText(naverId) && all.stream().noneMatch(r -> r.getRegistrationId().equals("naver"))) {
            all.add(naver(naverId, env));
        }
        return new InMemoryClientRegistrationRepository(all);
    }

    /**
     * 카카오 로그인 (spec 080). Spring에 기본 등록이 없어 주소를 직접 적는다. 카카오는 Client Secret을 요청 본문으로 받는다.
     * 이메일(account_email)은 비즈 앱에서만 받을 수 있어 KAKAO_SCOPES로 줄일 수 있게 둔다. 없으면 가입 마무리 화면에서 이메일을 받는다.
     */
    static ClientRegistration kakao(String clientId, Environment env) {
        String scopes = env.getProperty("KAKAO_SCOPES", "");
        if (!StringUtils.hasText(scopes)) scopes = "profile_nickname,profile_image,account_email";
        return ClientRegistration.withRegistrationId("kakao")
                .clientId(clientId)
                .clientSecret(env.getProperty("KAKAO_CLIENT_SECRET", ""))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(siteBaseUrl(env) + REDIRECT_PATH)
                .scope(StringUtils.commaDelimitedListToSet(scopes.replace(" ", "")))
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id")
                .clientName("Kakao")
                .build();
    }

    /**
     * Facebook 로그인 (spec 083). Spring 기본 등록(CommonOAuth2Provider.FACEBOOK)은 2016년 Graph API 버전을 적어 두어 쓰지 않는다.
     * 버전을 빼면 앱의 가장 오래된 버전으로 처리되므로 버전을 적고, 만료되기 전에 올린다(버전은 다음 버전이 나오고 2년 뒤 만료).
     * email·public_profile은 앱 검수 없이 쓸 수 있는 권한이다. 사진은 512px로 받는다.
     */
    static final String FACEBOOK_GRAPH = "https://graph.facebook.com/v26.0";

    static ClientRegistration facebook(String clientId, Environment env) {
        return ClientRegistration.withRegistrationId("facebook")
                .clientId(clientId)
                .clientSecret(env.getProperty("FACEBOOK_CLIENT_SECRET", ""))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(siteBaseUrl(env) + REDIRECT_PATH)
                .scope("public_profile", "email")
                .authorizationUri("https://www.facebook.com/v26.0/dialog/oauth")
                .tokenUri(FACEBOOK_GRAPH + "/oauth/access_token")
                .userInfoUri(FACEBOOK_GRAPH + "/me?fields=id,name,email,picture.width(512).height(512)")
                .userNameAttributeName("id")
                .clientName("Facebook")
                .build();
    }

    /**
     * 네이버 로그인 (spec 096). Spring에 기본 등록이 없어 주소를 직접 적는다. 네이버는 Client Secret을 요청 본문으로 받는다.
     * 네이버는 scope를 받지 않고 개발자센터의 "제공 정보 선택"(이름·이메일·별명·프로필 사진)으로 정한다.
     * 사용자 정보는 "response" 안에 오므로 그 이름을 사용자 이름 속성으로 둔다.
     */
    static ClientRegistration naver(String clientId, Environment env) {
        return ClientRegistration.withRegistrationId("naver")
                .clientId(clientId)
                .clientSecret(env.getProperty("NAVER_CLIENT_SECRET", ""))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(siteBaseUrl(env) + REDIRECT_PATH)
                .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                .tokenUri("https://nid.naver.com/oauth2.0/token")
                .userInfoUri("https://openapi.naver.com/v1/nid/me")
                .userNameAttributeName("response")
                .clientName("Naver")
                .build();
    }

    /** 콜백 주소의 앞부분. 프록시 뒤의 안쪽 요청(http)이 아니라 사이트 주소(SITE_BASE_URL, https)를 쓴다. */
    private static String siteBaseUrl(Environment env) {
        String base = env.getProperty("blog.site.base-url", "http://localhost:8080");
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
