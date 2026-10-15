package com.team.blog.post.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 자동 저장 버퍼 (docs/04 §2-3). Redis Hash autosave:post:{id} + 반영 대기 집합 autosave:dirty.
 * 버전 확인과 저장은 Lua 하나로 원자적으로 한다. 현재 버전은 max(Redis 버전, DB 버전)이다 —
 * 수동 저장·발행이 DB 버전을 올린 뒤에도 오래된 Redis 버전으로 저장이 통과하지 않게 하기 위해서다.
 * Redis 오류는 그대로 던진다. 호출하는 쪽이 DB 경로로 넘어간다 (§2-6 Redis 장애).
 */
@Component
public class AutosaveStore {
    public static final String DIRTY = "autosave:dirty";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SAVE = RedisScript.of("""
            local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '-1')
            if cur >= 0 and redis.call('HGET', KEYS[1], 'memberId') ~= ARGV[1] then return {-1, 0} end
            local db = tonumber(ARGV[3])
            if db > cur then cur = db end
            if tonumber(ARGV[2]) ~= cur then return {0, cur} end
            local nextVersion = cur + 1
            redis.call('HSET', KEYS[1], 'memberId', ARGV[1], 'title', ARGV[4], 'contentMd', ARGV[5],
                       'version', nextVersion, 'savedAt', ARGV[6])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[7]))
            if ARGV[9] == '1' then redis.call('SADD', KEYS[2], ARGV[8]) end
            return {1, nextVersion}
            """, List.class);

    private static final RedisScript<Long> DELETE_IF_AT_MOST = RedisScript.of("""
            local v = redis.call('HGET', KEYS[1], 'version')
            if v and tonumber(v) <= tonumber(ARGV[1]) then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public AutosaveStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public sealed interface SaveResult {
        record Saved(long version) implements SaveResult {}
        record Conflict(long currentVersion) implements SaveResult {}
        record NotOwner() implements SaveResult {}
    }

    public record Snapshot(long memberId, String title, String contentMd, long version, Instant savedAt) {}

    /** 버퍼에 쓸 글 한 벌. baseVersion은 편집기가 본 버전, dbVersion은 DB에 있는 버전 */
    public record Entry(long postId, long memberId, long baseVersion, long dbVersion, String title, String contentMd) {}

    /**
     * @param markDirty true면 스케줄러가 DB에 반영하도록 dirty 집합에 넣는다. 수동 저장·발행처럼 바로 DB에 쓰는 경우는 false.
     */
    public SaveResult save(Entry e, Instant savedAt, Duration ttl, boolean markDirty) {
        List<?> r = redis.execute(SAVE, List.of(key(e.postId()), DIRTY),
                String.valueOf(e.memberId()), String.valueOf(e.baseVersion()), String.valueOf(e.dbVersion()), e.title(), e.contentMd(),
                savedAt.toString(), String.valueOf(ttl.toSeconds()), String.valueOf(e.postId()), markDirty ? "1" : "0");
        long kind = ((Number) r.get(0)).longValue();
        long v = ((Number) r.get(1)).longValue();
        if (kind == -1) return new SaveResult.NotOwner();
        if (kind == 0) return new SaveResult.Conflict(v);
        return new SaveResult.Saved(v);
    }

    public Optional<Snapshot> read(long postId) {
        Map<Object, Object> h = redis.opsForHash().entries(key(postId));
        if (h.isEmpty() || h.get("version") == null) return Optional.empty();
        return Optional.of(new Snapshot(Long.parseLong((String) h.get("memberId")), (String) h.get("title"),
                (String) h.get("contentMd"), Long.parseLong((String) h.get("version")),
                Instant.parse((String) h.get("savedAt"))));
    }

    /** 발행 커밋 후 정리. 그 사이 다른 탭에서 더 새 버전이 들어왔다면 지우지 않는다 (docs/05 §7 ⑨). */
    public void deleteIfVersionAtMost(long postId, long version) {
        redis.execute(DELETE_IF_AT_MOST, List.of(key(postId)), String.valueOf(version));
    }

    public void delete(long postId) {
        redis.delete(key(postId));
    }

    public boolean exists(long postId) {
        return Boolean.TRUE.equals(redis.hasKey(key(postId)));
    }

    public Set<Long> dirtyPostIds() {
        // 파이프라인·트랜잭션 안에서만 null이다
        return Objects.requireNonNullElse(redis.opsForSet().members(DIRTY), Set.<String>of()).stream()
                .map(Long::valueOf).collect(Collectors.toSet());
    }

    public void markDirty(long postId) {
        redis.opsForSet().add(DIRTY, String.valueOf(postId));
    }

    public void removeDirty(long postId) {
        redis.opsForSet().remove(DIRTY, String.valueOf(postId));
    }

    static String key(long postId) {
        return "autosave:post:" + postId;
    }
}
