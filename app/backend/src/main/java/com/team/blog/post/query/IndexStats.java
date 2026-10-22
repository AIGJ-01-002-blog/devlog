package com.team.blog.post.query;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.time.Times;

/**
 * 검색 수집 대상(index)과 제외(noindex) 공개 글 수, 검수를 기다리는 외부 회원 글 (spec 107).
 * 관리자 대시보드가 보여 주고, 배포 뒤 앱이 뜰 때 한 줄로 로그에 남겨 배포 기록에서도 볼 수 있게 한다.
 * 제외 사유는 겹칠 수 있어(짧은 일기 등) 일기 → 짧은 글 → 검수 전 순서로 처음 걸린 사유 하나로 센다.
 */
@Service
public class IndexStats {
    private static final Logger log = LoggerFactory.getLogger(IndexStats.class);
    /** 검수 목록에 한 번에 보이는 글 수 */
    static final int PENDING_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IndexStats(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param indexed      검색 수집·사이트맵에 들어가는 공개 글
     * @param aiDiary      AI 일기라 빠진 글
     * @param shortPost    본문이 짧아 빠진 글
     * @param unreviewed   외부 회원 글인데 아직 검수하지 않아 빠진 글
     * @param pending      검수하면 바로 수집 대상이 되는 외부 회원 글 (최근 발행 순)
     */
    public record Report(long indexed, long aiDiary, long shortPost, long unreviewed, List<Pending> pending) {
        public long noindex() {
            return aiDiary + shortPost + unreviewed;
        }
    }

    public record Pending(long id, String title, String authorHandle, String url, int bodyChars) {}

    public Report report() {
        String sql = "SELECT count(*) FILTER (WHERE " + PostAccessPolicy.INDEXABLE_ARTICLE_CONDITION + ") AS indexed,"
                + " count(*) FILTER (WHERE p.ai_diary) AS diary,"
                + " count(*) FILTER (WHERE NOT p.ai_diary AND p.body_chars < " + PostAccessPolicy.MIN_ARTICLE_CHARS + ") AS short,"
                + " count(*) FILTER (WHERE NOT p.ai_diary AND p.body_chars >= " + PostAccessPolicy.MIN_ARTICLE_CHARS
                + " AND NOT " + PostAccessPolicy.INDEX_REVIEWED_CONDITION + ") AS unreviewed"
                + " FROM post p JOIN member m ON m.id = p.author_id WHERE " + PostAccessPolicy.PUBLIC_LIST_CONDITION;
        long[] c = jdbc.queryForObject(sql, (rs, i) -> new long[] {rs.getLong("indexed"), rs.getLong("diary"), rs.getLong("short"),
                rs.getLong("unreviewed")});
        List<Pending> pending = jdbc.query("SELECT p.id, p.title, m.handle, p.body_chars FROM post p JOIN member m ON m.id = p.author_id WHERE "
                        + PostAccessPolicy.PUBLIC_LIST_CONDITION + " AND NOT p.ai_diary AND p.body_chars >= " + PostAccessPolicy.MIN_ARTICLE_CHARS
                        + " AND NOT " + PostAccessPolicy.INDEX_REVIEWED_CONDITION + " ORDER BY p.first_public_at DESC, p.id DESC LIMIT ?",
                (rs, i) -> new Pending(rs.getLong("id"), rs.getString("title"), rs.getString("handle"),
                        "/@" + rs.getString("handle") + "/posts/" + rs.getLong("id"), rs.getInt("body_chars")), PENDING_LIMIT);
        return new Report(c[0], c[1], c[2], c[3], pending);
    }

    /** 외부 회원 글의 검색 수집을 허락하거나 거둔다. 발행한 글이 아니면 404 */
    public void approve(long postId, boolean approved) {
        int n = jdbc.update("UPDATE post SET index_approved_at = ? WHERE id = ? AND status = 'PUBLISHED' AND deleted_at IS NULL",
                approved ? Timestamp.from(Times.now(clock)) : null, postId);
        if (n == 0) throw new NotFoundException();
    }

    /** 배포 기록에서도 수집 대상 글 수를 보도록 앱이 뜰 때 한 줄 남긴다. 실패해도 앱은 뜬다 */
    @EventListener(ApplicationReadyEvent.class)
    public void logOnStartup() {
        try {
            Report r = report();
            log.info("검색 수집 대상 글 {}편, 제외 {}편 (AI 일기 {}, 짧은 글 {}, 검수 전 외부 글 {})",
                    r.indexed(), r.noindex(), r.aiDiary(), r.shortPost(), r.unreviewed());
        } catch (RuntimeException e) {
            log.warn("검색 수집 글 수를 세지 못했어요: {}", e.getMessage());
        }
    }
}
