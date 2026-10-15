package com.team.blog.view.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.time.Times;
import com.team.blog.shared.web.RateLimiter;

/**
 * 사이트 방문 기록 (spec 064). 화면을 처음 열 때마다 한 번 온다. 같은 사람은 하루 한 행으로 모으고(순방문자),
 * 30분 넘게 쉬었다가 다시 오면 방문 수만 하나 늘린다. 방문자 구분은 조회수(013)와 같다.
 * 어디서 왔는지(유입 경로)와 어느 화면을 열었는지도 하루 단위 수로 모은다(spec 070).
 * 로봇·미리 불러오기는 세지 않는다. 관리자·매니저 방문은 센다(1.42.1, 블로그 주인 결정). 실패해도 화면에는 알리지 않는다.
 */
@Service
public class VisitRecorder {
    private static final Logger log = LoggerFactory.getLogger(VisitRecorder.class);
    static final Duration RETENTION = Duration.ofDays(400);
    private static final int PER_MINUTE = 20;
    /** 방문자 값(쿠키)을 바꿔 가며 보내도 막히도록 IP에도 건다. 회사·학교처럼 IP를 나눠 쓰는 곳을 생각해 넉넉히 */
    private static final int PER_MINUTE_PER_IP = 120;
    /** 화면 옮기기는 방문보다 잦다 (070) */
    private static final int PAGES_PER_MINUTE = 60;
    private static final int PAGES_PER_MINUTE_PER_IP = 300;

    public enum Outcome { COUNTED, EXCLUDED, SKIPPED }

    private final JdbcTemplate jdbc;
    private final ViewRecorder views;
    private final RateLimiter rateLimiter;
    private final JobLock lock;
    private final Clock clock;
    /** 이만큼 쉬었다 다시 오면 방문 한 번 더 (기본 30분) */
    private final Duration sessionGap;

    public VisitRecorder(JdbcTemplate jdbc, ViewRecorder views, RateLimiter rateLimiter, JobLock lock, Clock clock,
                         @Value("${blog.view.visit-session-gap:30m}") Duration sessionGap) {
        this.sessionGap = sessionGap;
        this.jdbc = jdbc;
        this.views = views;
        this.rateLimiter = rateLimiter;
        this.lock = lock;
        this.clock = clock;
    }

    /**
     * 화면이 보낸 값 (spec 070). first는 화면을 처음 열 때 한 번(방문으로 센다), 그 뒤 화면을 옮길 때마다는 false(화면 순위에만 센다).
     * @param referrer 처음 열 때만, 다른 사이트에서 왔을 때만 온다
     */
    public record Page(String path, String referrer, boolean first, String ownHost) {
        /** 예전 화면처럼 아무 값 없이 보낸 방문 */
        public static final Page UNKNOWN = new Page(null, null, true, null);
    }

    public Outcome recordVisit(ViewRecorder.Visit visit) {
        return recordVisit(visit, Page.UNKNOWN);
    }

    public Outcome recordVisit(ViewRecorder.Visit visit, Page page) {
        if (visit.prefetch() || views.isBot(visit.userAgent())) return Outcome.EXCLUDED;
        // 처음 쿠키를 받는 비회원은 그 쿠키로 센다. 다음 방문이 같은 사람으로 이어진다
        String visitor = visit.memberId() == null && visit.issuedVisitorId() != null
                ? ViewRecorder.sha256("v:" + visit.issuedVisitorId()) : views.visitorKey(visit);
        if (visitor == null) return Outcome.SKIPPED;
        String ipKey = visit.ip() == null ? null : ViewRecorder.sha256(visit.ip());
        Instant now = Times.now(clock);
        LocalDate today = LocalDate.ofInstant(now, Counts.KST);
        String path = VisitSources.page(page.path());

        if (!page.first()) return recordPageMove(visitor, ipKey, today, path);

        if (ipKey != null && !rateLimiter.tryAcquire("visit-ip:" + ipKey, PER_MINUTE_PER_IP, Duration.ofMinutes(1))) return Outcome.EXCLUDED;
        if (!rateLimiter.tryAcquire("visit:" + visitor, PER_MINUTE, Duration.ofMinutes(1))) return Outcome.EXCLUDED;
        try {
            if (touchVisit(visitor, visit.memberId() != null, today, now)) {
                countSource(today, VisitSources.classify(page.referrer(), visit.userAgent(), page.ownHost()));
            }
        } catch (RuntimeException e) {
            log.warn("방문 기록을 건너뜁니다: {}", e.getClass().getSimpleName());
            return Outcome.SKIPPED;
        }
        if (path != null) countPage(today, path);
        return Outcome.COUNTED;
    }

    /** 처음 연 뒤의 화면 이동. 화면 순위에만 센다 */
    private Outcome recordPageMove(String visitor, String ipKey, LocalDate today, String path) {
        if (path == null) return Outcome.SKIPPED;
        if (ipKey != null && !rateLimiter.tryAcquire("page-ip:" + ipKey, PAGES_PER_MINUTE_PER_IP, Duration.ofMinutes(1))) return Outcome.EXCLUDED;
        if (!rateLimiter.tryAcquire("page:" + visitor, PAGES_PER_MINUTE, Duration.ofMinutes(1))) return Outcome.EXCLUDED;
        // 오늘 방문으로 들어온 사람의 화면 이동만 센다. 쿠키를 바꿔 가며 화면 수만 부풀리는 것을 막는다
        try {
            if (jdbc.queryForList("SELECT 1 FROM site_visit WHERE day = ? AND visitor = ?", today, visitor).isEmpty()) return Outcome.SKIPPED;
        } catch (RuntimeException e) {
            log.warn("화면 기록을 건너뜁니다: {}", e.getClass().getSimpleName());
            return Outcome.SKIPPED;
        }
        return countPage(today, path) ? Outcome.COUNTED : Outcome.SKIPPED;
    }

    /** 방문을 적고, 새 방문(처음이거나 30분 넘게 쉬었다 온 것)이면 true */
    private boolean touchVisit(String visitor, boolean member, LocalDate today, Instant now) {
        // 새 방문일 때만 유입 경로를 센다. 세 문장 모두 행 단위로 원자적이라
        // 같은 사람의 요청이 동시에 와도 새 방문은 한 번만 잡힌다
        Timestamp at = Timestamp.from(now);
        boolean newVisit = jdbc.update("""
                INSERT INTO site_visit (day, visitor, member, visits, first_at, last_at) VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT (day, visitor) DO NOTHING
                """, today, visitor, member, at, at) == 1
                || jdbc.update("""
                UPDATE site_visit SET visits = visits + 1, last_at = ?
                WHERE day = ? AND visitor = ? AND last_at < ?
                """, at, today, visitor, Timestamp.from(now.minus(sessionGap))) == 1;
        if (!newVisit) {
            jdbc.update("UPDATE site_visit SET last_at = GREATEST(last_at, ?) WHERE day = ? AND visitor = ?", at, today, visitor);
        }
        return newVisit;
    }

    private void countSource(LocalDate day, VisitSources.Source s) {
        try {
            jdbc.update("""
                    INSERT INTO visit_source_day (day, source, host, visits) VALUES (?, ?, ?, 1)
                    ON CONFLICT (day, source, host) DO UPDATE SET visits = visit_source_day.visits + 1
                    """, day, s.source(), s.host());
        } catch (RuntimeException e) {
            log.warn("유입 경로를 건너뜁니다: {}", e.getClass().getSimpleName());
        }
    }

    private boolean countPage(LocalDate day, String path) {
        try {
            jdbc.update("""
                    INSERT INTO page_view_day (day, path, views) VALUES (?, ?, 1)
                    ON CONFLICT (day, path) DO UPDATE SET views = page_view_day.views + 1
                    """, day, path);
            return true;
        } catch (RuntimeException e) {
            log.warn("화면 기록을 건너뜁니다: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Scheduled(cron = "${blog.view.visit-purge-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        lock.runExclusively("visit-purge", Duration.ofMinutes(10), this::purge);
    }

    /** @return 지운 행 수 */
    public int purge() {
        LocalDate cutoff = LocalDate.ofInstant(Times.now(clock).minus(RETENTION), Counts.KST);
        return jdbc.update("DELETE FROM site_visit WHERE day < ?", cutoff)
                + jdbc.update("DELETE FROM visit_source_day WHERE day < ?", cutoff)
                + jdbc.update("DELETE FROM page_view_day WHERE day < ?", cutoff);
    }
}
