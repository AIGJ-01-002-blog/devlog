package com.team.blog.friend.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 최근 활동 구간 (008 FR-008, docs/06 §6-4). 정확한 시각은 어디에도 내보내지 않고 "며칠 전"만 0~7로 준다.
 * 0 = 오늘, 1 = 어제, 2~6 = N일 전, 7 = 1주 이상. 날짜 경계는 서비스 기준 시간대(한국)다.
 */
public final class LastActive {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final int WEEK_OR_MORE = 7;

    private LastActive() {}

    public static int daysAgo(Instant lastActiveAt, Instant now) {
        long days = ChronoUnit.DAYS.between(LocalDate.ofInstant(lastActiveAt, ZONE), LocalDate.ofInstant(now, ZONE));
        return Math.clamp(days, 0, WEEK_OR_MORE);
    }
}
