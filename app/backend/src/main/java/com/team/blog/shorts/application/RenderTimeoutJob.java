package com.team.blog.shorts.application;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.blog.shared.scheduling.JobLock;

/** 응답 없는 렌더 작업 되돌리기 (099 시나리오 5). 쇼츠가 꺼져 있으면 아무것도 하지 않는다 */
@Component
class RenderTimeoutJob {
    private final RenderQueue queue;
    private final JobLock lock;
    private final ShortsProperties props;

    RenderTimeoutJob(RenderQueue queue, JobLock lock, ShortsProperties props) {
        this.queue = queue;
        this.lock = lock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${blog.shorts.timeout-check-interval:2m}", initialDelayString = "${blog.shorts.timeout-check-interval:2m}")
    void run() {
        if (!props.enabled()) return;
        lock.runExclusively("shorts-render-timeout", Duration.ofMinutes(1), queue::releaseStale);
    }
}
