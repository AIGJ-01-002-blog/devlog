package com.team.blog.shorts.autoupload;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.shorts.application.UploadLink;
import com.team.blog.shorts.autoupload.JobState.Action;
import com.team.blog.shorts.autoupload.JobState.Status;
import com.team.blog.shorts.autoupload.PlatformConnections.Connection;

/**
 * 쇼츠 × 플랫폼 올리기 작업 (spec 108 FR-006~FR-009). 예약 작업(AutoUploadScheduler)이 한 서버에서만 부른다.
 * <ul>
 *   <li>enqueue: 켠 플랫폼마다, 켠 뒤 렌더가 끝난(READY) 쇼츠 가운데 작업도 직접 남긴 주소도 없는 것을 다음 빈 날 20:00으로 넣는다</li>
 *   <li>runDue: 차례가 온 작업을 한 단계씩 진행한다(JobState.next)</li>
 * </ul>
 */
@Service
public class AutoUploadJobs {
    private static final Logger log = LoggerFactory.getLogger(AutoUploadJobs.class);
    static final int BATCH = 20;
    /** Instagram은 공개 시각보다 이만큼 넘게 늦으면 바로 게시하지 않고 다음 빈 날로 미룬다 */
    static final Duration LATE = Duration.ofHours(1);
    /** 아직 끝나지 않은 작업. 연결을 끊어 FAILED로 멈춘 작업을 진행 중이던 단계가 덮어쓰지 않게 갱신마다 이 조건을 단다 */
    private static final String OPEN = "status IN ('QUEUED', 'UPLOADING', 'SCHEDULED')";

    /**
     * 화면에 보이는 작업
     * @param privateOnly 공개 예약 없이 비공개로만 올리는 작업(#501). publishAt은 공개 시각이 아니라 올릴 차례·올린 시각이다
     */
    public record JobView(AutoPlatform platform, Status status, int attempts, Instant publishAt, String url, String error, String note,
                          Instant updatedAt, boolean privateOnly) {}

    record Job(long id, long shortsId, long memberId, AutoPlatform platform, Status status, int attempts, Instant publishAt,
               String externalId, String containerId, String url) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PlatformConnections connections;
    private final YouTubeApi youtube;
    private final InstagramApi instagram;
    private final ShortsService shorts;
    private final ShortsVideos videos;
    private final AutoUploadProperties props;
    private final String origin;
    private final Clock clock;

    AutoUploadJobs(JdbcTemplate jdbc, TransactionTemplate tx, PlatformConnections connections, YouTubeApi youtube, InstagramApi instagram, ShortsService shorts,
                   ShortsVideos videos, AutoUploadProperties props, BlogProperties blog, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.connections = connections;
        this.youtube = youtube;
        this.instagram = instagram;
        this.shorts = shorts;
        this.videos = videos;
        this.props = props;
        this.origin = HttpJson.trimBase(blog.site().baseUrl());
        this.clock = clock;
    }

    public List<JobView> list(long memberId, long shortsId) {
        return jdbc.query("""
                SELECT platform, status, attempts, publish_at, url, error, note, updated_at FROM shorts_auto_upload
                WHERE member_id = ? AND shorts_id = ? ORDER BY platform
                """, (rs, i) -> new JobView(AutoPlatform.valueOf(rs.getString(1)), Status.valueOf(rs.getString(2)), rs.getInt(3),
                rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                privateOnly(AutoPlatform.valueOf(rs.getString(1)))), memberId, shortsId);
    }

    /** YouTube를 공개 예약 없이 비공개로만 올리는지 (#501) */
    boolean privateOnly(AutoPlatform platform) {
        return platform == AutoPlatform.YOUTUBE && props.youtube().privateOnly();
    }

    /** 실패한 작업을 처음부터 다시. 공개 시각도 다시 고른다 */
    public void retry(long memberId, long shortsId, AutoPlatform platform) {
        Instant now = Times.now(clock);
        int n = withSlotLock(memberId, () -> {
            Instant slot = publishAt(memberId, platform, now);
            return jdbc.update("""
                    UPDATE shorts_auto_upload SET status = 'QUEUED', attempts = 0, error = NULL, note = NULL, external_id = NULL, container_id = NULL,
                        url = NULL, publish_at = ?, next_attempt_at = ?, updated_at = ?
                    WHERE member_id = ? AND shorts_id = ? AND platform = ? AND status = 'FAILED'
                    """, Timestamp.from(slot), Timestamp.from(JobState.firstAttempt(platform, slot, now)), Timestamp.from(now), memberId, shortsId, platform.name());
        });
        if (n == 0) throw new NotFoundException();
    }

    /** 연결을 끊으면 아직 올리지 않은 작업을 멈춘다(이미 올려 예약한 YouTube 영상은 YouTube에 남는다) */
    void stopPending(long memberId, AutoPlatform platform, String why) {
        jdbc.update("""
                UPDATE shorts_auto_upload SET status = 'FAILED', error = ?, updated_at = ?
                WHERE member_id = ? AND platform = ? AND status IN ('QUEUED', 'UPLOADING')
                """, why, Timestamp.from(Times.now(clock)), memberId, platform.name());
    }

    /** @return 새로 넣은 작업 수 */
    public int enqueue() {
        if (!shorts.enabled()) return 0;
        int n = 0;
        for (Connection c : connections.enabled()) {
            if (!connections.configured(c.platform()) || c.enabledAt() == null || !allowed(c.memberId())) continue;
            List<Long> ready = jdbc.queryForList("""
                    SELECT s.id FROM shorts s
                    WHERE s.member_id = ? AND s.status = 'READY' AND s.rendered_at >= ?
                      AND NOT EXISTS (SELECT 1 FROM shorts_auto_upload j WHERE j.shorts_id = s.id AND j.platform = ?)
                      AND NOT EXISTS (SELECT 1 FROM shorts_upload u WHERE u.shorts_id = s.id AND u.platform = ?)
                    ORDER BY s.rendered_at, s.id LIMIT ?
                    """, Long.class, c.memberId(), Timestamp.from(c.enabledAt()), c.platform().name(), c.platform().name(), BATCH);
            for (long shortsId : ready) {
                Instant now = Times.now(clock);
                n += withSlotLock(c.memberId(), () -> {
                    Instant slot = publishAt(c.memberId(), c.platform(), now);
                    return jdbc.update("""
                            INSERT INTO shorts_auto_upload (shorts_id, member_id, platform, status, publish_at, next_attempt_at, created_at, updated_at)
                            VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?) ON CONFLICT (shorts_id, platform) DO NOTHING
                            """, shortsId, c.memberId(), c.platform().name(), Timestamp.from(slot),
                            Timestamp.from(JobState.firstAttempt(c.platform(), slot, now)), Timestamp.from(now), Timestamp.from(now));
                });
            }
        }
        if (n > 0) log.info("쇼츠 자동 올리기 작업 {}건을 넣었습니다", n);
        return n;
    }

    /** 이 회원이 쓸 수 있는지. 화면의 AutoUploads.available()과 같은 조건(기본은 관리자만)을 예약 작업에도 건다 */
    boolean allowed(long memberId) {
        if (props.audience() == AutoUploadProperties.Audience.MEMBERS) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM member WHERE id = ? AND role = 'ADMIN')", Boolean.class, memberId));
    }

    /**
     * 빈 날을 고르고 적는 일을 회원 행 잠금 안에서 한다. 다시 올리기(요청)와 예약 작업이 같은 날을 함께 고르지 않게 한다.
     * 잠금은 이 트랜잭션이 끝나면 풀린다(플랫폼 요청은 이 안에서 하지 않는다)
     */
    private <T> T withSlotLock(long memberId, java.util.function.Supplier<T> work) {
        return tx.execute(s -> {
            jdbc.queryForList("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, memberId);
            return work.get();
        });
    }

    /** 공개 시각을 다음 빈 날로 옮겨 적는다 */
    private Instant reslot(Job job, Instant now) {
        return withSlotLock(job.memberId(), () -> {
            Instant next = slot(job.memberId(), job.platform(), now, job.id());
            jdbc.update("UPDATE shorts_auto_upload SET publish_at = ?, updated_at = ? WHERE id = ?", Timestamp.from(next), Timestamp.from(now), job.id());
            return next;
        });
    }

    /** 새 작업의 publish_at: 비공개로만 올리면 지금(바로 올린다), 아니면 다음 빈 날 공개 시각 */
    private Instant publishAt(long memberId, AutoPlatform platform, Instant now) {
        return privateOnly(platform) ? now : slot(memberId, platform, now, null);
    }

    /** 다음 빈 날 공개 시각. exceptJob은 다시 고르는 작업 자신(그 날을 비운다). 비공개로만 올린 작업은 날을 차지하지 않는다 */
    Instant slot(long memberId, AutoPlatform platform, Instant now, Long exceptJob) {
        Set<LocalDate> taken = new HashSet<>();
        jdbc.query("""
                SELECT publish_at FROM shorts_auto_upload WHERE member_id = ? AND platform = ? AND status NOT IN ('FAILED', 'UPLOADED') AND publish_at >= ?
                    AND id <> ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> taken.add(PublishSlots.dayOf(rs.getTimestamp(1).toInstant())),
                memberId, platform.name(), Timestamp.from(now.minus(Duration.ofDays(1))), exceptJob == null ? -1L : exceptJob);
        return PublishSlots.next(now, props.publishTime(), props.minLead(), taken);
    }

    /** @return 진행한 작업 수 */
    public int runDue() {
        if (!shorts.enabled()) return 0;
        Instant now = Times.now(clock);
        List<Job> due = jdbc.query("""
                SELECT j.* FROM shorts_auto_upload j
                WHERE j.status IN ('QUEUED', 'UPLOADING', 'SCHEDULED') AND j.next_attempt_at <= ?
                ORDER BY j.next_attempt_at, j.id LIMIT ?
                """, AutoUploadJobs::map, Timestamp.from(now), BATCH);
        int n = 0;
        for (Job job : due) {
            try {
                if (step(job)) n++;
            } catch (RuntimeException e) {
                // 예상하지 못한 오류도 작업 하나만 실패로 두고 다음 작업을 본다. 예외 메시지에 토큰이 실릴 수 있어 종류만 남긴다
                log.warn("쇼츠 자동 올리기 작업 {}에서 오류: {}", job.id(), e.getClass().getSimpleName());
                failed(job, new PlatformException(PlatformException.Kind.RETRYABLE, "올리는 중에 알 수 없는 문제가 생겼어요. 잠시 뒤 다시 해 볼게요."));
            }
        }
        return n;
    }

    /** 작업 한 단계. @return 무언가 했으면 true */
    boolean step(Job job) {
        Instant now = Times.now(clock);
        Action action = JobState.next(job.platform(), job.status(), job.externalId() != null, job.containerId() != null, job.publishAt(), now,
                privateOnly(job.platform()));
        boolean uploaded = job.platform() == AutoPlatform.YOUTUBE && job.externalId() != null;
        if (action == Action.NONE) {
            // 잘못 깨운 작업(시각이 아직)은 제 시각으로 미룬다. 올린 뒤 실패해 QUEUED로 돌아온 YouTube 작업은 예약됨으로 되돌린다
            Instant at = job.status() == Status.SCHEDULED || uploaded ? job.publishAt() : JobState.firstAttempt(job.platform(), job.publishAt(), now);
            jdbc.update("UPDATE shorts_auto_upload SET status = CASE WHEN ? THEN 'SCHEDULED' ELSE status END, next_attempt_at = ? WHERE id = ? AND " + OPEN,
                    uploaded, Timestamp.from(at.isAfter(now) ? at : now.plus(JobState.LEASE)), job.id());
            return false;
        }
        if (action == Action.MARK_PUBLISHED) {
            published(job, job.url(), now);
            return true;
        }
        if (action == Action.MARK_UPLOADED) {
            jdbc.update("UPDATE shorts_auto_upload SET status = 'UPLOADED', error = NULL, updated_at = ? WHERE id = ? AND " + OPEN,
                    Timestamp.from(now), job.id());
            return true;
        }
        Optional<Connection> conn = connections.find(job.memberId(), job.platform()).filter(Connection::enabled);
        if (conn.isEmpty() || !connections.configured(job.platform()) || !allowed(job.memberId())) {
            // 꺼 두거나 쓸 수 없는 회원이 되면 미뤄 둔다. 다시 켜면 이어서 한다
            jdbc.update("UPDATE shorts_auto_upload SET next_attempt_at = ? WHERE id = ?", Timestamp.from(now.plus(JobState.LEASE)), job.id());
            return false;
        }
        Optional<String> key = readyVideo(job.shortsId());
        if (key.isEmpty() && action != Action.CHECK_CONTAINER) {
            // 대본을 고쳐 영상을 다시 만드는 중이다. 다 만들면 이어서 올린다
            jdbc.update("UPDATE shorts_auto_upload SET next_attempt_at = ?, note = ? WHERE id = ?", Timestamp.from(now.plus(JobState.LEASE)),
                    "영상을 다시 만드는 중이라 기다려요.", job.id());
            return false;
        }
        // 잡은 표시: 서버가 도중에 죽어도 LEASE 뒤 다시 잡는다. 그사이 연결을 끊어 멈춘 작업이면 잡지 않는다
        int claimed = jdbc.update("UPDATE shorts_auto_upload SET status = 'UPLOADING', next_attempt_at = ?, updated_at = ? WHERE id = ? AND status = ?",
                Timestamp.from(now.plus(JobState.LEASE)), Timestamp.from(now), job.id(), job.status().name());
        if (claimed == 0) return false;
        try {
            switch (action) {
                case UPLOAD_VIDEO -> uploadYouTube(job, conn.get(), key.get(), now);
                case CREATE_CONTAINER -> createContainer(job, conn.get(), key.get(), now);
                case CHECK_CONTAINER -> checkContainer(job, conn.get(), now);
                default -> { }
            }
        } catch (PlatformException e) {
            failed(job, e);
        }
        return true;
    }

    private void uploadYouTube(Job job, Connection conn, String videoKey, Instant now) {
        boolean privateOnly = privateOnly(job.platform());
        Instant publishAt = privateOnly ? now : job.publishAt();
        if (!privateOnly && publishAt.isBefore(now.plus(props.minLead()))) {
            // 다시 하느라 공개 시각을 지났다. 다음 빈 날로
            publishAt = reslot(job, now);
        }
        ShortsService.Item item = shorts.get(job.memberId(), job.shortsId());
        ShortsCaption.Text text = ShortsCaption.build(ShortsCaption.Input.of(item), UploadLink.Platform.YOUTUBE, origin, props.publishTime());
        String token = connections.accessToken(conn);
        byte[] bytes = videos.bytes(videoKey);
        if (!stillOpen(job)) return;
        String id = youtube.upload(token, bytes, new YouTubeApi.Video(ShortsCaption.limit(text.title(), 100),
                ShortsCaption.limit(text.caption(), 5000), text.tags().stream().map(t -> t.substring(1)).toList(), privateOnly ? null : publishAt));
        String url = "https://youtube.com/shorts/" + id;
        // 올리자마자 영상 ID부터 적는다. 뒤(재생목록·상태 적기)에서 실패해 다시 해도 같은 영상을 또 올리지 않는다(JobState.next)
        jdbc.update("UPDATE shorts_auto_upload SET external_id = ?, url = ?, publish_at = ?, updated_at = ? WHERE id = ?",
                id, url, Timestamp.from(publishAt), Timestamp.from(now), job.id());
        String note = null;
        if (text.playlistName() != null) note = addToPlaylist(token, text.playlistName(), id);
        // 비공개로만 올리면 여기서 끝난다(UPLOADED). 공개 예약이면 공개 시각에 다시 깨운다(SCHEDULED)
        int n = jdbc.update("""
                UPDATE shorts_auto_upload SET status = ?, next_attempt_at = ?, error = NULL, note = ?, updated_at = ?
                WHERE id = ? AND status = 'UPLOADING'
                """, privateOnly ? "UPLOADED" : "SCHEDULED", Timestamp.from(publishAt), note, Timestamp.from(now), job.id());
        if (n == 0) {
            // 올리는 사이에 연결을 끊었다. 작업은 멈춘 그대로 두고, 이미 예약된 영상이 있다고 알린다
            jdbc.update("UPDATE shorts_auto_upload SET note = ? WHERE id = ?", privateOnly
                    ? "연결을 끊기 전에 올라간 영상이 YouTube에 비공개로 남아 있어요. 원하지 않으면 YouTube 스튜디오에서 지워 주세요."
                    : "연결을 끊기 전에 올라간 영상이 YouTube에 공개 예약돼 있어요. 원하지 않으면 YouTube 스튜디오에서 지워 주세요.", job.id());
            log.info("쇼츠 {}를 올리는 사이 연결이 끊겼습니다. 올린 영상은 YouTube에 남습니다", job.shortsId());
            return;
        }
        if (privateOnly) log.info("쇼츠 {}를 YouTube에 비공개로 올렸습니다(공개 예약 없음)", job.shortsId());
        else log.info("쇼츠 {}를 YouTube에 비공개로 올리고 {}에 공개 예약했습니다", job.shortsId(), publishAt);
    }

    /** 플랫폼에 보내기 직전에 다시 본다: 연결이 그대로 켜져 있고 작업을 아직 잡고 있는지(연결을 끊으면 stopPending이 FAILED로 바꾼다) */
    private boolean stillOpen(Job job) {
        boolean on = connections.find(job.memberId(), job.platform()).filter(Connection::enabled).isPresent();
        return on && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shorts_auto_upload WHERE id = ? AND status = 'UPLOADING')",
                Boolean.class, job.id()));
    }

    /** 재생목록은 만들지 않는다. 못 찾거나 못 넣어도 올리기는 성공으로 두고 알림만 남긴다 */
    private String addToPlaylist(String token, String name, String videoId) {
        try {
            Optional<String> playlist = youtube.findPlaylist(token, name);
            if (playlist.isEmpty()) return "채널에 \"" + name + "\" 재생목록이 없어 넣지 않았어요. 만든 뒤 YouTube 스튜디오에서 넣어 주세요.";
            youtube.addToPlaylist(token, playlist.get(), videoId);
            return null;
        } catch (PlatformException e) {
            return "재생목록에 넣지 못했어요: " + e.getMessage();
        }
    }

    private void createContainer(Job job, Connection conn, String videoKey, Instant now) {
        if (now.isAfter(job.publishAt().plus(LATE))) {
            postpone(job, now);
            return;
        }
        ShortsService.Item item = shorts.get(job.memberId(), job.shortsId());
        ShortsCaption.Text text = ShortsCaption.build(ShortsCaption.Input.of(item), UploadLink.Platform.INSTAGRAM, origin, props.publishTime());
        String container = instagram.createReel(connections.accessToken(conn), conn.accountId(), videos.publicUrl(videoKey),
                ShortsCaption.limit(text.caption(), 2200));
        jdbc.update("UPDATE shorts_auto_upload SET container_id = ?, next_attempt_at = ?, error = NULL, updated_at = ? WHERE id = ? AND status = 'UPLOADING'",
                container, Timestamp.from(now.plus(JobState.CONTAINER_POLL)), Timestamp.from(now), job.id());
    }

    /**
     * 서버가 오래 멈췄거나 다시 하느라 공개 시각보다 한 시간 넘게 늦었다. 다음 빈 날로 미룬다.
     * 만들어 둔 컨테이너는 그때까지 사라지므로(24시간) 버리고 다음 날 새로 만든다
     */
    private void postpone(Job job, Instant now) {
        Instant next = reslot(job, now);
        jdbc.update("UPDATE shorts_auto_upload SET status = 'QUEUED', container_id = NULL, next_attempt_at = ?, updated_at = ? WHERE id = ? AND " + OPEN,
                Timestamp.from(JobState.firstAttempt(job.platform(), next, now)), Timestamp.from(now), job.id());
    }

    private void checkContainer(Job job, Connection conn, Instant now) {
        String token = connections.accessToken(conn);
        InstagramApi.ContainerStatus status = instagram.status(token, job.containerId());
        switch (status) {
            case IN_PROGRESS -> jdbc.update("UPDATE shorts_auto_upload SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.from(now.plus(JobState.CONTAINER_POLL)), job.id());
            case FINISHED -> {
                if (now.isBefore(job.publishAt())) {
                    jdbc.update("UPDATE shorts_auto_upload SET next_attempt_at = ? WHERE id = ?", Timestamp.from(job.publishAt()), job.id());
                    return;
                }
                if (now.isAfter(job.publishAt().plus(LATE))) {
                    // createContainer와 같은 기준: 한 시간 넘게 늦었으면 지금 게시하지 않는다
                    postpone(job, now);
                    return;
                }
                if (!stillOpen(job)) return;
                String media = instagram.publish(token, conn.accountId(), job.containerId());
                jdbc.update("UPDATE shorts_auto_upload SET external_id = ? WHERE id = ?", media, job.id());
                published(job, instagram.permalink(token, media), now);
                log.info("쇼츠 {}를 Instagram 릴스로 게시했습니다", job.shortsId());
            }
            // 게시는 됐는데 주소를 받기 전에 실패했다. 게시물 ID가 있으면 주소를 다시 받는다
            case PUBLISHED -> published(job, job.externalId() != null ? instagram.permalink(token, job.externalId()) : job.url(), now);
            case ERROR, EXPIRED -> {
                // 다시 할 때 컨테이너를 새로 만든다
                jdbc.update("UPDATE shorts_auto_upload SET container_id = NULL WHERE id = ?", job.id());
                throw new PlatformException(PlatformException.Kind.RETRYABLE, status == InstagramApi.ContainerStatus.EXPIRED
                        ? "Instagram 컨테이너가 시간이 지나 사라졌어요. 새로 만들어 볼게요."
                        : "Instagram이 영상을 처리하지 못했어요(영상 주소를 바깥에서 열 수 있는지 확인해 주세요).");
            }
        }
    }

    /** 공개됐다: 스튜디오·블로그가 쓰는 올린 주소(shorts_upload)에 적는다. 회원이 직접 남긴 주소가 있으면 그대로 둔다 */
    /** 두 쓰기는 한 트랜잭션이다: 주소 적기가 실패하면 상태도 그대로라 다음 차례에 다시 한다 */
    private void published(Job job, String url, Instant now) {
        String clean = url == null ? null : UploadLink.clean(job.platform().link(), url).orElse(null);
        tx.executeWithoutResult(s -> {
            int n = jdbc.update("UPDATE shorts_auto_upload SET status = 'PUBLISHED', url = COALESCE(?, url), error = NULL, note = COALESCE(?, note), updated_at = ? WHERE id = ? AND " + OPEN,
                    clean, clean == null ? "게시물 주소를 받지 못했어요. 앱에서 주소를 복사해 남겨 주세요." : null, Timestamp.from(now), job.id());
            if (n > 0 && clean != null) {
                jdbc.update("""
                        INSERT INTO shorts_upload (shorts_id, platform, url, uploaded_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (shorts_id, platform) DO NOTHING
                        """, job.shortsId(), job.platform().name(), clean, Timestamp.from(job.publishAt().isAfter(now) ? now : job.publishAt()));
            }
        });
    }

    private void failed(Job job, PlatformException e) {
        Instant now = Times.now(clock);
        JobState.Failure f = JobState.afterFailure(job.attempts(), e, props.maxAttempts(), props.retryBackoff(), now);
        jdbc.update("UPDATE shorts_auto_upload SET status = ?, attempts = ?, next_attempt_at = ?, error = ?, updated_at = ? WHERE id = ? AND " + OPEN,
                f.status().name(), f.attempts(), Timestamp.from(f.nextAttemptAt()), PlatformConnections.cut(f.error(), 500), Timestamp.from(now), job.id());
        if (f.status() == Status.FAILED) log.warn("쇼츠 {} {} 자동 올리기 실패: {}", job.shortsId(), job.platform(), f.error());
    }

    private Optional<String> readyVideo(long shortsId) {
        return jdbc.query("SELECT video_key FROM shorts WHERE id = ? AND status = 'READY' AND video_key IS NOT NULL",
                (rs, i) -> rs.getString(1), shortsId).stream().findFirst();
    }

    private static Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getLong("id"), rs.getLong("shorts_id"), rs.getLong("member_id"), AutoPlatform.valueOf(rs.getString("platform")),
                Status.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getTimestamp("publish_at").toInstant(),
                rs.getString("external_id"), rs.getString("container_id"), rs.getString("url"));
    }
}
