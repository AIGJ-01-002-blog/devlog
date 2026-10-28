package com.team.blog.shorts.autoupload;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 공개 시각 고르기 (spec 108 FR-007). 하루 한 편, 매일 같은 시각(한국 20:00).
 * 오늘 시각이 이미 지났거나 minLead보다 덜 남았으면 다음 날부터 보고, 이미 한 편이 잡힌 날은 건너뛴다.
 */
public final class PublishSlots {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 1년 넘게 다 찼으면 뭔가 잘못된 것이다(무한 반복 막기) */
    static final int MAX_DAYS = 400;

    private PublishSlots() {}

    /**
     * @param taken 이미 공개가 잡힌 날(한국 날짜)
     */
    public static Instant next(Instant now, LocalTime time, Duration minLead, Set<LocalDate> taken) {
        LocalDate day = now.atZone(KST).toLocalDate();
        Instant today = day.atTime(time).atZone(KST).toInstant();
        if (today.isBefore(now.plus(minLead))) day = day.plusDays(1);
        for (int i = 0; i < MAX_DAYS; i++, day = day.plusDays(1)) {
            if (!taken.contains(day)) return day.atTime(time).atZone(KST).toInstant();
        }
        throw new IllegalStateException("앞으로 " + MAX_DAYS + "일이 모두 찼습니다");
    }

    public static LocalDate dayOf(Instant at) {
        return at.atZone(KST).toLocalDate();
    }
}
