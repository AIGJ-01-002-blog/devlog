package com.team.blog.shorts.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 쇼츠 스튜디오 설정 (099). 2.0.0 릴리스 전까지 enabled=false로 화면·API·MCP 도구를 숨긴다.
 *
 * @param enabled        SHORTS_ENABLED. 꺼져 있으면 /api/me/shorts·워커 API는 없는 주소(404)이고 MCP 도구도 보이지 않는다
 * @param workerToken    SHORTS_WORKER_TOKEN. 렌더 워커가 X-Render-Token으로 보낸다. 비어 있으면 워커 API는 404다
 * @param previousWorkerToken SHORTS_WORKER_TOKEN_PREVIOUS. 토큰을 바꾸는 동안 옛 토큰도 받는다(#241). 워커를 새 토큰으로 바꾼 뒤 비운다
 * @param maxVideo       워커가 올리는 MP4 상한. 인그레스 본문 상한(21m)보다 작아야 한다
 * @param dailyRenders   회원별 하루(한국 0시 기준) 영상 만들기 횟수
 * @param renderTimeout  워커가 받은 뒤 이만큼 결과가 없으면 대기로 되돌린다
 * @param maxAttempts    이만큼 실패하면 FAILED로 끝낸다
 */
@ConfigurationProperties(prefix = "blog.shorts")
public record ShortsProperties(@DefaultValue("false") boolean enabled,
                               @DefaultValue("") String workerToken,
                               @DefaultValue("") String previousWorkerToken,
                               @DefaultValue("20MB") DataSize maxVideo,
                               @DefaultValue("10") int dailyRenders,
                               @DefaultValue("15m") Duration renderTimeout,
                               @DefaultValue("3") int maxAttempts) {

    public boolean workerEnabled() {
        return enabled && workerToken != null && !workerToken.isBlank();
    }
}
