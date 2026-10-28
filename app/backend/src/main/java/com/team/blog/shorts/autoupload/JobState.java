package com.team.blog.shorts.autoupload;

import java.time.Duration;
import java.time.Instant;

/**
 * 올리기 작업의 상태 바꾸기 규칙 (spec 108 FR-008). DB를 모르는 순수 함수라 단위 테스트로 본다.
 * <pre>
 * YouTube:   QUEUED ─올리기─▶ SCHEDULED(비공개 + publishAt) ─공개 시각─▶ PUBLISHED
 * Instagram: QUEUED ─컨테이너─▶ UPLOADING ─FINISHED·공개 시각─▶ PUBLISHED
 * 실패: 다시 할 수 있으면 QUEUED(기다림 5분·10분…), maxAttempts를 채웠거나 다시 해도 같은 실패면 FAILED
 * </pre>
 */
public final class JobState {
    public enum Status { QUEUED, UPLOADING, SCHEDULED, PUBLISHED, FAILED }

    /** 이번 차례에 할 일 */
    public enum Action { UPLOAD_VIDEO, CREATE_CONTAINER, CHECK_CONTAINER, MARK_PUBLISHED, NONE }

    /** Instagram 컨테이너는 공개 15분 전에 만든다(영상 처리 시간). 컨테이너는 24시간 뒤 사라진다 */
    public static final Duration CONTAINER_LEAD = Duration.ofMinutes(15);
    /** 작업을 잡은 뒤 서버가 죽어도 이만큼 지나면 다시 잡는다 */
    public static final Duration LEASE = Duration.ofMinutes(10);
    /** 컨테이너 처리를 기다리는 간격 */
    public static final Duration CONTAINER_POLL = Duration.ofMinutes(1);

    private JobState() {}

    public static Action next(AutoPlatform platform, Status status, boolean hasExternalId, boolean hasContainer, Instant publishAt, Instant now) {
        return switch (status) {
            case PUBLISHED, FAILED -> Action.NONE;
            case SCHEDULED -> now.isBefore(publishAt) ? Action.NONE : Action.MARK_PUBLISHED;
            case QUEUED, UPLOADING -> switch (platform) {
                case YOUTUBE -> hasExternalId ? Action.NONE : Action.UPLOAD_VIDEO;
                case INSTAGRAM -> hasContainer ? Action.CHECK_CONTAINER
                        : now.isBefore(publishAt.minus(CONTAINER_LEAD)) ? Action.NONE : Action.CREATE_CONTAINER;
            };
        };
    }

    /** 처음 볼 시각: YouTube는 바로, Instagram은 컨테이너를 만들 때 */
    public static Instant firstAttempt(AutoPlatform platform, Instant publishAt, Instant now) {
        if (platform == AutoPlatform.YOUTUBE) return now;
        Instant at = publishAt.minus(CONTAINER_LEAD);
        return at.isBefore(now) ? now : at;
    }

    /** 실패 뒤 상태 */
    public record Failure(Status status, int attempts, Instant nextAttemptAt, String error) {}

    /**
     * @param attempts 이번 실패 전까지 실패한 횟수
     */
    public static Failure afterFailure(int attempts, PlatformException e, int maxAttempts, Duration backoff, Instant now) {
        int n = attempts + 1;
        boolean last = e.kind() != PlatformException.Kind.RETRYABLE || n >= maxAttempts;
        if (last) {
            String why = e.kind() == PlatformException.Kind.RETRYABLE ? " (" + n + "번 해 봤어요)" : "";
            return new Failure(Status.FAILED, n, now, e.getMessage() + why);
        }
        return new Failure(Status.QUEUED, n, now.plus(backoff(n, backoff)), e.getMessage());
    }

    /** n번째 실패 뒤 기다림: base, base×2, base×4 … */
    public static Duration backoff(int n, Duration base) {
        return base.multipliedBy(1L << Math.min(Math.max(n - 1, 0), 10));
    }
}
