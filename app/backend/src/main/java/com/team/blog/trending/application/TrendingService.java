package com.team.blog.trending.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.team.blog.post.query.PostCard;
import com.team.blog.post.query.PostCardQuery;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.scheduling.JobLock;
import com.team.blog.shared.time.Times;

/**
 * 트렌딩 순위표 (017 FR-013~FR-020, docs/32 §4). 10분마다 한 서버가 상위 100개를 계산해 Redis에 30분 보관하고,
 * 요청은 순위표를 읽기만 한다(전체 계산 없음). 커서에 순위표 번호와 위치를 담아, 보는 도중 새 순위표가 생겨도 보던 순위표로 이어진다.
 * 이어 볼 때 그 사이 볼 수 없게 된 글은 건너뛰고 다음 글로 9개를 채운다.
 * Redis가 안 되면 그 자리에서 계산한 첫 9개만 [더 보기] 없이 낸다(FR-020).
 */
@Service
public class TrendingService {
    private static final Logger log = LoggerFactory.getLogger(TrendingService.class);
    static final String LIST = "trending";
    static final String CURRENT = "trending:current";
    static final String SNAPSHOT = "trending:snapshot:";

    private final TrendingRanker ranker;
    private final PostCardQuery feed;
    private final CursorCodec cursors;
    private final StringRedisTemplate redis;
    private final JobLock lock;
    private final Clock clock;
    private final int pageSize;
    private final Duration keep;

    public TrendingService(TrendingRanker ranker, PostCardQuery feed, CursorCodec cursors, StringRedisTemplate redis, JobLock lock,
                           Clock clock, BlogProperties props, @Value("${blog.trending.keep:30m}") Duration keep) {
        this.ranker = ranker;
        this.feed = feed;
        this.cursors = cursors;
        this.redis = redis;
        this.lock = lock;
        this.clock = clock;
        this.pageSize = props.feed().pageSize();
        this.keep = keep;
    }

    /** @param temporary 보관소 장애로 바로 계산한 결과 (다음 쪽 없음) */
    public record Page(List<PostCard> items, String nextCursor, boolean temporary) {}

    @Scheduled(cron = "${blog.trending.cron:0 */10 * * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        lock.runExclusively(LIST, Duration.ofMinutes(5), this::refresh);
    }

    /** 새 순위표를 만들고 지금 순위표로 바꾼다. @return 순위표 번호(만든 시각, ms) */
    public long refresh() {
        Instant now = Times.now(clock);
        List<Long> ids = ranker.rank(now, TrendingRanker.MAX);
        long snapshot = now.toEpochMilli();
        redis.opsForValue().set(SNAPSHOT + snapshot, ids.stream().map(String::valueOf).collect(Collectors.joining(",")), keep);
        redis.opsForValue().set(CURRENT, String.valueOf(snapshot), keep);
        log.debug("트렌딩 순위표 {}: {}개", snapshot, ids.size());
        return snapshot;
    }

    public Page page(String cursor) {
        long[] k = cursors.decode(cursor, LIST, 2);
        boolean first = k.length == 0;
        Table table;
        try {
            table = first ? currentTable() : savedTable(k[0]);
        } catch (DataAccessException e) {
            log.warn("트렌딩 순위표를 읽지 못해 바로 계산합니다: {}", e.getClass().getSimpleName());
            if (!first) throw expired();
            return new Page(feed.publicCards(ranker.rank(Times.now(clock), pageSize)), null, true);
        }
        long snapshot = table.snapshot();
        List<Long> ids = table.ids();
        int offset = first ? 0 : (int) k[1];
        if (offset < 0 || offset > ids.size()) throw ApiException.badRequest("INVALID_CURSOR", "목록 위치 값이 올바르지 않아요. 처음부터 다시 불러와 주세요.");
        // 볼 수 없게 된 글을 건너뛰며 9개를 채운다
        List<PostCard> items = new ArrayList<>();
        int pos = offset;
        while (items.size() < pageSize && pos < ids.size()) {
            int end = Math.min(ids.size(), pos + (pageSize - items.size()));
            items.addAll(feed.publicCards(ids.subList(pos, end)));
            pos = end;
        }
        String next = pos < ids.size() ? cursors.encode(LIST, snapshot, pos) : null;
        return new Page(items, next, false);
    }

    private record Table(long snapshot, List<Long> ids) {}

    /** 지금 순위표. 없으면 새로 만든다 */
    private Table currentTable() {
        String current = redis.opsForValue().get(CURRENT);
        long snapshot = current == null ? refresh() : Long.parseLong(current);
        Optional<List<Long>> ids = load(snapshot);
        if (ids.isPresent()) return new Table(snapshot, ids.get());
        // 지금 순위표가 막 만료됐다. 새로 만든 것까지 바로 사라졌으면 빈 목록이다
        long fresh = refresh();
        return new Table(fresh, load(fresh).orElse(List.of()));
    }

    /** 다음 쪽이 이어 보는 순위표. 만료됐으면 410 */
    private Table savedTable(long snapshot) {
        return new Table(snapshot, load(snapshot).orElseThrow(TrendingService::expired));
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.GONE, "TRENDING_EXPIRED", "순위가 새로 바뀌었어요.");
    }

    /** @return 순위표의 글 번호. 만료됐으면 비어 있다 */
    private Optional<List<Long>> load(long snapshot) {
        String raw = redis.opsForValue().get(SNAPSHOT + snapshot);
        if (raw == null) return Optional.empty();
        if (raw.isEmpty()) return Optional.of(List.of());
        return Optional.of(Arrays.stream(raw.split(",")).map(Long::valueOf).toList());
    }
}
