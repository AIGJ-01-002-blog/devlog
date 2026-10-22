package com.team.blog.mcp.application;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.blog.shared.scheduling.JobLock;

/** 주간 회고 초안 (spec 107). 매주 일요일 22시(KST)에 그 주 일기를 묶는다. 서버가 여러 대여도 한 대만 돈다. */
@Component
public class WeeklyRetroJob {
    private final WeeklyRetro retro;
    private final JobLock lock;

    public WeeklyRetroJob(WeeklyRetro retro, JobLock lock) {
        this.retro = retro;
        this.lock = lock;
    }

    @Scheduled(cron = "${blog.mcp.weekly-retro-cron:0 0 22 * * SUN}", zone = "Asia/Seoul")
    public void scheduled() {
        lock.runExclusively("weekly-retro", Duration.ofMinutes(30), retro::createDrafts);
    }
}
