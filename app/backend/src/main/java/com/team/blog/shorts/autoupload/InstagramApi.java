package com.team.blog.shorts.autoupload;

import java.time.Instant;
import java.util.List;

/**
 * Instagram API with Instagram Login (spec 108). 프로페셔널 계정의 Instagram 사용자 토큰으로 릴스를 게시한다.
 * API에는 예약이 없어 devlog 서버가 20:00(KST)에 게시한다. 24시간에 API 게시 100개까지.
 */
public interface InstagramApi {
    List<String> SCOPES = List.of("instagram_business_basic", "instagram_business_content_publish");

    /** 장기 토큰(약 60일). 만료 전에 refresh로 늘린다 */
    record Tokens(String accessToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "Tokens[expiresAt=" + expiresAt + "]";
        }
    }

    record Account(String userId, String username) {}

    enum ContainerStatus { IN_PROGRESS, FINISHED, PUBLISHED, ERROR, EXPIRED }

    String authorizeUrl(String redirectUri, String state);

    /** 인가 코드 → 단기 토큰 → 장기 토큰 */
    Tokens exchange(String code, String redirectUri);

    Tokens refresh(String accessToken);

    Account account(String accessToken);

    /** 릴스 컨테이너를 만든다. videoUrl은 이때 바깥에서 받을 수 있는 https 주소여야 한다. @return 컨테이너 ID */
    String createReel(String accessToken, String userId, String videoUrl, String caption);

    ContainerStatus status(String accessToken, String containerId);

    /** @return 게시된 미디어 ID */
    String publish(String accessToken, String userId, String containerId);

    /** 게시물 주소(https://www.instagram.com/reel/…). 못 받으면 null */
    String permalink(String accessToken, String mediaId);
}
