package com.team.blog.shorts.autoupload;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.blog.shared.scheduling.JobLock;

/**
 * 자동 올리기 예약 작업 (spec 108). 서버가 여럿이어도 JobLock으로 한 곳에서만 돈다. 시험(poll=false)은 직접 부른다.
 * 1분마다 새 영상을 넣고 차례가 온 작업을 진행한다. 20:00(KST)에도 한 번 더 돌려 Instagram을 제 시각에 게시한다.
 */
@Component
class AutoUploadScheduler {
    private final AutoUploadJobs jobs;
    private final PlatformConnections connections;
    private final AutoUploadProperties props;
    private final JobLock lock;

    AutoUploadScheduler(AutoUploadJobs jobs, PlatformConnections connections, AutoUploadProperties props, JobLock lock) {
        this.jobs = jobs;
        this.connections = connections;
        this.props = props;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${blog.shorts.auto-upload.interval:1m}", initialDelayString = "${blog.shorts.auto-upload.interval:1m}")
    void tick() {
        run();
    }

    @Scheduled(cron = "${blog.shorts.auto-upload.publish-cron:5 0 20 * * *}", zone = "Asia/Seoul")
    void publishTime() {
        run();
    }

    @Scheduled(cron = "${blog.shorts.auto-upload.refresh-cron:0 20 4 * * *}", zone = "Asia/Seoul")
    void refreshTokens() {
        if (!props.poll() || !connections.anyConfigured()) return;
        lock.runExclusively("shorts-auto-upload-refresh", Duration.ofMinutes(5), connections::refreshInstagram);
    }

    private void run() {
        if (!props.poll() || !connections.anyConfigured()) return;
        // YouTube 올리기(20MB)가 몇 분 걸릴 수 있어 잠금을 넉넉히 잡는다
        lock.runExclusively("shorts-auto-upload", Duration.ofMinutes(15), () -> {
            jobs.enqueue();
            jobs.runDue();
        });
    }
}
