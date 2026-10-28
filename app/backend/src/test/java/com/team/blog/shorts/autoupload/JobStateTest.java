package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.autoupload.JobState.Action;
import com.team.blog.shorts.autoupload.JobState.Status;
import com.team.blog.shorts.autoupload.PlatformException.Kind;

/** 올리기 작업 상태 바꾸기 (spec 108 FR-008): 단계 고르기, 다시 하기 기다림, 3번 뒤 FAILED */
class JobStateTest {
    static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    static final Instant PUBLISH = Instant.parse("2026-10-28T11:00:00Z");

    @Test
    void YouTube는_바로_올리고_예약한_뒤_공개_시각에_공개로_본다() {
        assertThat(JobState.firstAttempt(AutoPlatform.YOUTUBE, PUBLISH, NOW)).isEqualTo(NOW);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.QUEUED, false, false, PUBLISH, NOW)).isEqualTo(Action.UPLOAD_VIDEO);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.UPLOADING, false, false, PUBLISH, NOW)).as("잡은 채 서버가 죽음").isEqualTo(Action.UPLOAD_VIDEO);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.SCHEDULED, true, false, PUBLISH, NOW)).isEqualTo(Action.NONE);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.SCHEDULED, true, false, PUBLISH, PUBLISH)).isEqualTo(Action.MARK_PUBLISHED);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.PUBLISHED, true, false, PUBLISH, PUBLISH)).isEqualTo(Action.NONE);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.FAILED, false, false, PUBLISH, PUBLISH)).isEqualTo(Action.NONE);
        // 올린 뒤(영상 ID를 적은 뒤) 실패해 QUEUED로 돌아와도 다시 올리지 않는다
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.QUEUED, true, false, PUBLISH, NOW)).isEqualTo(Action.NONE);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.QUEUED, true, false, PUBLISH, PUBLISH)).isEqualTo(Action.MARK_PUBLISHED);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.UPLOADING, true, false, PUBLISH, PUBLISH)).isEqualTo(Action.MARK_PUBLISHED);
    }

    @Test
    void YouTube를_비공개로만_올리면_올린_뒤_공개_시각을_기다리지_않고_UPLOADED로_끝낸다() {
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.QUEUED, false, false, PUBLISH, NOW, true)).isEqualTo(Action.UPLOAD_VIDEO);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.QUEUED, true, false, PUBLISH, NOW, true)).as("올린 뒤 실패").isEqualTo(Action.MARK_UPLOADED);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.UPLOADING, true, false, PUBLISH, NOW, true)).isEqualTo(Action.MARK_UPLOADED);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.UPLOADED, true, false, PUBLISH, PUBLISH, true)).isEqualTo(Action.NONE);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.UPLOADED, true, false, PUBLISH, PUBLISH, false)).isEqualTo(Action.NONE);
        // 설정을 바꾸기 전에 이미 공개 예약한 영상은 그대로 공개 시각을 기다린다
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.SCHEDULED, true, false, PUBLISH, NOW, true)).isEqualTo(Action.NONE);
        assertThat(JobState.next(AutoPlatform.YOUTUBE, Status.SCHEDULED, true, false, PUBLISH, PUBLISH, true)).isEqualTo(Action.MARK_PUBLISHED);
    }

    @Test
    void Instagram은_공개_15분_전에_컨테이너를_만들고_처리를_본다() {
        Instant lead = PUBLISH.minus(JobState.CONTAINER_LEAD);
        assertThat(JobState.firstAttempt(AutoPlatform.INSTAGRAM, PUBLISH, NOW)).isEqualTo(lead);
        assertThat(JobState.firstAttempt(AutoPlatform.INSTAGRAM, PUBLISH, PUBLISH)).as("늦었으면 지금").isEqualTo(PUBLISH);
        assertThat(JobState.next(AutoPlatform.INSTAGRAM, Status.QUEUED, false, false, PUBLISH, NOW)).isEqualTo(Action.NONE);
        assertThat(JobState.next(AutoPlatform.INSTAGRAM, Status.QUEUED, false, false, PUBLISH, lead)).isEqualTo(Action.CREATE_CONTAINER);
        assertThat(JobState.next(AutoPlatform.INSTAGRAM, Status.UPLOADING, false, true, PUBLISH, lead)).isEqualTo(Action.CHECK_CONTAINER);
        assertThat(JobState.next(AutoPlatform.INSTAGRAM, Status.QUEUED, false, true, PUBLISH, lead)).as("확인하다 실패한 뒤").isEqualTo(Action.CHECK_CONTAINER);
    }

    @Test
    void 다시_할_수_있는_실패는_5분_10분_기다리고_세_번째에_FAILED() {
        PlatformException busy = new PlatformException(Kind.RETRYABLE, "YouTube가 잠시 바빠요.");
        JobState.Failure f1 = JobState.afterFailure(0, busy, 3, Duration.ofMinutes(5), NOW);
        assertThat(f1.status()).isEqualTo(Status.QUEUED);
        assertThat(f1.attempts()).isEqualTo(1);
        assertThat(f1.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        JobState.Failure f2 = JobState.afterFailure(1, busy, 3, Duration.ofMinutes(5), NOW);
        assertThat(f2.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        JobState.Failure f3 = JobState.afterFailure(2, busy, 3, Duration.ofMinutes(5), NOW);
        assertThat(f3.status()).isEqualTo(Status.FAILED);
        assertThat(f3.attempts()).isEqualTo(3);
        assertThat(f3.error()).isEqualTo("YouTube가 잠시 바빠요. (3번 해 봤어요)");
    }

    @Test
    void 연결이_끊겼거나_거절된_요청은_다시_하지_않는다() {
        JobState.Failure auth = JobState.afterFailure(0, PlatformException.auth("YouTube"), 3, Duration.ofMinutes(5), NOW);
        assertThat(auth.status()).isEqualTo(Status.FAILED);
        assertThat(auth.error()).contains("다시 연결해 주세요");
        JobState.Failure bad = JobState.afterFailure(0, new PlatformException(Kind.PERMANENT, "거절"), 3, Duration.ofMinutes(5), NOW);
        assertThat(bad.status()).isEqualTo(Status.FAILED);
        assertThat(bad.error()).isEqualTo("거절");
        assertThat(JobState.backoff(11, Duration.ofMinutes(1))).as("너무 길어지지 않게 막는다").isEqualTo(Duration.ofMinutes(1024));
    }
}
