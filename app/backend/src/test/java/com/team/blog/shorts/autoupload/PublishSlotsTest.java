package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 공개 시각 (spec 108 FR-007): 하루 한 편, 한국 20:00, 30분 안쪽이면 다음 날, 잡힌 날은 건너뛴다 */
class PublishSlotsTest {
    static final LocalTime EIGHT = LocalTime.of(20, 0);
    static final Duration LEAD = Duration.ofMinutes(30);

    private static Instant kst(String local) {
        return java.time.LocalDateTime.parse(local).atZone(PublishSlots.KST).toInstant();
    }

    @Test
    void 오늘_20시까지_여유가_있으면_오늘이다() {
        assertThat(PublishSlots.next(kst("2026-10-28T10:00"), EIGHT, LEAD, Set.of())).isEqualTo(kst("2026-10-28T20:00"));
        assertThat(PublishSlots.next(kst("2026-10-28T19:30"), EIGHT, LEAD, Set.of())).as("딱 30분 남음").isEqualTo(kst("2026-10-28T20:00"));
    }

    @Test
    void 삼십분_안쪽이거나_지났으면_다음_날이다() {
        assertThat(PublishSlots.next(kst("2026-10-28T19:31"), EIGHT, LEAD, Set.of())).isEqualTo(kst("2026-10-29T20:00"));
        assertThat(PublishSlots.next(kst("2026-10-28T20:00"), EIGHT, LEAD, Set.of())).isEqualTo(kst("2026-10-29T20:00"));
        assertThat(PublishSlots.next(kst("2026-10-28T23:59"), EIGHT, LEAD, Set.of())).isEqualTo(kst("2026-10-29T20:00"));
    }

    @Test
    void 이미_잡힌_날은_건너뛰어_하루에_두_편이_없다() {
        Set<LocalDate> taken = Set.of(LocalDate.parse("2026-10-28"), LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-31"));
        assertThat(PublishSlots.next(kst("2026-10-28T09:00"), EIGHT, LEAD, taken)).isEqualTo(kst("2026-10-30T20:00"));
    }

    @Test
    void 날짜는_한국_시간으로_센다() {
        // UTC로는 10/28 16:00이지만 한국은 10/29 01:00이다
        Instant now = Instant.parse("2026-10-28T16:00:00Z");
        assertThat(PublishSlots.next(now, EIGHT, LEAD, Set.of())).isEqualTo(Instant.parse("2026-10-29T11:00:00Z"));
        assertThat(PublishSlots.dayOf(Instant.parse("2026-10-28T16:00:00Z"))).isEqualTo(LocalDate.parse("2026-10-29"));
        // 연말을 넘어도 이어진다
        assertThat(PublishSlots.next(kst("2026-12-31T21:00"), EIGHT, LEAD, Set.of(LocalDate.parse("2027-01-01"))))
                .isEqualTo(kst("2027-01-02T20:00"));
    }
}
