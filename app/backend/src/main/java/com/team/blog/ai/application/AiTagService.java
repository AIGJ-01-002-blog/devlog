package com.team.blog.ai.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.team.blog.account.application.AgreementService;
import com.team.blog.account.domain.AgreementType;
import com.team.blog.ai.application.TagModel.ModelException;
import com.team.blog.ai.application.TagModel.Provider;
import com.team.blog.post.infra.PostRepository;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.tag.application.TagNormalizer;
import com.team.blog.tag.application.TagQuery;

/**
 * AI 태그 추천 (018, docs/34). 작성자가 버튼을 누를 때만 불린다. 순서: 기능 스위치 → 작성자 본인 → 동의 → 정리 후 길이 →
 * ① 같은 내용 결과 → ② 같은 글 비슷한 결과 → 개인 하루 한도 → 공급자 호출(외부 AI, 한도면 자체 AI) → 태그 규칙으로 거르기 → 저장.
 * <p>
 * 실패해도 글 저장·발행과는 아무 관계가 없다(FR-020). 글 내용·열쇠값은 로그에 남기지 않는다(FR-033).
 */
@Service
@EnableConfigurationProperties(AiProperties.class)
public class AiTagService {
    private static final Logger log = LoggerFactory.getLogger(AiTagService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String USER_PREFIX = "ai:user:";
    private static final String POPULAR_PREFIX = "ai:popular:";
    static final int POPULAR = 50;
    private static final int MAX_ATTACHED = 100;
    private static final String AI_BUSY = "AI_BUSY";

    private final AiProperties props;
    private final TagModel gemini;
    private final TagModel local;
    private final ProviderState state;
    private final SuggestionStore store;
    private final AgreementService agreements;
    private final PostRepository posts;
    private final TagNormalizer normalizer;
    private final TagQuery tags;
    private final StringRedisTemplate redis;
    private final BlogProperties.Post postProps;
    private final Clock clock;
    private final Semaphore localSlots;

    public AiTagService(AiProperties props, List<TagModel> models, ProviderState state, SuggestionStore store,
                        AgreementService agreements, PostRepository posts, TagNormalizer normalizer, TagQuery tags,
                        StringRedisTemplate redis, BlogProperties blog, Clock clock) {
        this.props = props;
        this.gemini = model(models, Provider.GEMINI);
        this.local = model(models, Provider.LOCAL);
        this.state = state;
        this.store = store;
        this.agreements = agreements;
        this.posts = posts;
        this.normalizer = normalizer;
        this.tags = tags;
        this.redis = redis;
        this.postProps = blog.post();
        this.clock = clock;
        this.localSlots = new Semaphore(Math.max(1, props.local().concurrency()));
    }

    private static TagModel model(List<TagModel> models, Provider p) {
        return models.stream().filter(m -> m.provider() == p).findFirst().orElseThrow();
    }

    /** @param again [다시 추천]: 비슷한 내용 재사용을 건너뛴다 (FR-025) */
    public record Request(String title, String content, List<String> tags, boolean again) {}

    /**
     * @param tags      제안 (이미 붙인 태그를 뺐고, 글당 상한의 남은 자리만큼)
     * @param cached    저장된 결과로 답했으면 true (하루 횟수에서 빼지 않았다)
     * @param provider  결과를 만든 공급자
     * @param truncated 본문 앞부분만 보냈으면 true
     */
    public record Result(List<String> tags, boolean cached, Provider provider, boolean truncated, int remaining) {}

    /**
     * 에디터가 버튼을 그릴 때 쓰는 상태.
     * @param provider 지금 누르면 쓸 공급자 (자체 AI면 "조금 걸려요" 안내, FR-019). 모르면 null
     */
    public record Status(boolean enabled, boolean agreed, int remaining, Provider provider, String consentVersion) {}

    public Status status(long memberId) {
        String version = agreements.currentVersion(AgreementType.AI);
        if (!props.available()) return new Status(false, false, 0, null, version);
        boolean agreed = agreements.hasAgreed(memberId, AgreementType.AI);
        try {
            return new Status(true, agreed, remaining(memberId), nextProvider(), version);
        } catch (DataAccessException e) {
            return new Status(true, agreed, 0, null, version);
        }
    }

    public Result suggest(long memberId, long postId, Request req) {
        if (!props.available()) throw unavailable();
        posts.findOwn(postId, memberId).orElseThrow(NotFoundException::new);
        if (!agreements.hasAgreed(memberId, AgreementType.AI)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_CONSENT_REQUIRED", "AI 기능을 쓰려면 먼저 동의해 주세요.");
        }
        String content = req.content() == null ? "" : AiInput.truncate(req.content(), postProps.maxContentLength());
        AiInput.Cleaned input = AiInput.clean(AiInput.truncate(req.title() == null ? "" : req.title(), postProps.maxTitleLength()), content);
        if (input.length() < props.minChars()) {
            throw ApiException.badRequest("AI_TOO_SHORT", "글을 조금 더 쓴 뒤 추천받아 보세요.");
        }
        Set<String> attached = attached(req.tags());
        int room = Math.clamp(postProps.maxTags() - (long) attached.size(), 0, TagPrompt.MAX_TAGS);
        try {
            return run(memberId, postId, input, attached, room, req.again());
        } catch (DataAccessException e) {
            // 결과 보관소·상태를 읽고 쓸 수 없으면 사용할 수 없음 (2026-10-07 장애 정책)
            log.warn("AI 태그 추천 보관소를 쓸 수 없습니다: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private Result run(long memberId, long postId, AiInput.Cleaned input, Set<String> attached, int room, boolean again) {
        int widest = props.gemini().maxChars();
        String key = AiInput.key(input, widest);
        String sent = input.title() + "\n" + input.bodyUpTo(widest);
        Optional<SuggestionStore.Stored> exact = store.findExact(key);
        boolean upgrade = again && !props.preferLocal() && exact.isPresent() && exact.get().provider() == Provider.LOCAL && state.geminiUsable();
        if (exact.isPresent() && !upgrade) {
            return answer(exact.get().tags(), true, exact.get().provider(), input, attached, room, remaining(memberId));
        }
        if (!again) {
            Optional<SuggestionStore.ForPost> similar = store.forPost(postId)
                    .filter(p -> AiInput.similarity(p.input(), sent) >= props.similarity());
            if (similar.isPresent()) {
                return answer(similar.get().tags(), true, similar.get().provider(), input, attached, room, remaining(memberId));
            }
        }
        if (nextProvider() == null) throw unavailable();
        int left = take(memberId);
        Called called;
        try {
            called = call(input, attached);
        } catch (ApiException e) {
            // 자체 AI가 바빠 아무것도 보내지 못했으면 횟수를 돌려준다
            if (AI_BUSY.equals(e.code())) redis.opsForValue().decrement(userKey(memberId));
            throw e;
        }
        List<String> filtered = filter(called.raw());
        if (!filtered.isEmpty()) store.save(key, postId, sent, filtered, called.provider());
        return answer(filtered, false, called.provider(), input, attached, room, left);
    }

    private record Called(List<String> raw, Provider provider) {}

    /**
     * 공급자 고르기·전환 (FR-015·FR-016). 집 PC를 먼저 쓰는 설정이면 집 PC → (꺼졌거나 바쁘면) 외부 AI.
     * 그렇지 않으면 외부 AI → (한도면) 집 PC.
     */
    private Called call(AiInput.Cleaned input, Set<String> attached) {
        if (props.preferLocal() && state.localUsable()) return callLocalFirst(input, attached);
        if (state.geminiUsable()) {
            try {
                return callGemini(input, attached);
            } catch (ModelException e) {
                log.warn("외부 AI 태그 추천 실패: {}", e.kind());
                // 한도 초과만 같은 요청을 자체 AI로 넘긴다. 시간 초과·오류는 더 기다리게 하지 않는다
                if (!e.quota() || !local.configured()) throw unavailable();
            }
        } else if (!local.configured()) {
            throw unavailable();
        }
        return new Called(callLocal(input, attached), Provider.LOCAL);
    }

    /** 집 PC를 먼저 쓰고, 꺼졌거나 바쁘면 외부 AI로 넘긴다 */
    private Called callLocalFirst(AiInput.Cleaned input, Set<String> attached) {
        if (localSlots.tryAcquire()) {
            try {
                return new Called(local.suggest(prompt(local, input, attached)), Provider.LOCAL);
            } catch (ModelException e) {
                state.localFailed(e.kind());
                log.warn("자체 AI 태그 추천 실패, 외부 AI로 넘깁니다: {}", e.kind());
            } finally {
                localSlots.release();
            }
        } else if (!state.geminiUsable()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, AI_BUSY, "잠시 후 다시 시도해 주세요.");
        }
        if (!state.geminiUsable()) throw unavailable();
        try {
            return callGemini(input, attached);
        } catch (ModelException e) {
            log.warn("외부 AI 태그 추천 실패: {}", e.kind());
            throw unavailable();
        }
    }

    /** 외부 AI를 부른다. 실패하면 상태를 적고 예외를 그대로 던진다(기록은 부르는 쪽이 남긴다) */
    private Called callGemini(AiInput.Cleaned input, Set<String> attached) throws ModelException {
        try {
            List<String> raw = gemini.suggest(prompt(gemini, input, attached));
            state.succeeded();
            return new Called(raw, Provider.GEMINI);
        } catch (ModelException e) {
            state.failed(e.kind());
            throw e;
        }
    }

    private List<String> callLocal(AiInput.Cleaned input, Set<String> attached) {
        if (!localSlots.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, AI_BUSY, "잠시 후 다시 시도해 주세요.");
        try {
            return local.suggest(prompt(local, input, attached));
        } catch (ModelException e) {
            state.localFailed(e.kind());
            log.warn("자체 AI 태그 추천 실패: {}", e.kind());
            throw unavailable();
        } finally {
            localSlots.release();
        }
    }

    private TagModel.Prompt prompt(TagModel model, AiInput.Cleaned input, Set<String> attached) {
        String body = input.bodyUpTo(model.maxChars());
        List<String> popular = popular();
        if (model.provider() == Provider.LOCAL) popular = TagPrompt.mentioned(popular, input.title(), body);
        return TagPrompt.build(input.title(), body, popular, attached);
    }

    /** 인기 태그 상위 50개를 하루(한국 날짜) 한 번 정해 쓴다 (FR-009). */
    private List<String> popular() {
        String key = POPULAR_PREFIX + LocalDate.now(clock.withZone(KST));
        String cached = redis.opsForValue().get(key);
        if (cached != null) return cached.isEmpty() ? List.of() : List.of(cached.split(","));
        List<String> names = tags.top(POPULAR).stream().map(TagQuery.TagCount::name).toList();
        redis.opsForValue().set(key, String.join(",", names), Duration.ofDays(1));
        return names;
    }

    /** 태그 규칙으로 정규화하고 금칙어·규칙 위반·중복을 뺀다 (FR-012). 이미 붙인 태그는 응답할 때 뺀다. */
    private List<String> filter(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            TagNormalizer.Verdict v = normalizer.check(r);
            if (v.ok()) out.add(v.name());
            if (out.size() == TagPrompt.MAX_TAGS) break;
        }
        return List.copyOf(out);
    }

    private Result answer(List<String> stored, boolean cached, Provider provider, AiInput.Cleaned input, Set<String> attached,
                          int room, int remaining) {
        List<String> shown = new ArrayList<>();
        for (String t : stored) {
            if (shown.size() >= room) break;
            if (!attached.contains(t)) shown.add(t);
        }
        int max = provider == Provider.LOCAL ? props.local().maxChars() : props.gemini().maxChars();
        return new Result(List.copyOf(shown), cached, provider, input.truncatedAt(max), remaining);
    }

    private static Set<String> attached(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        raw.stream().limit(MAX_ATTACHED).map(TagNormalizer::clean).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }

    // --- 사용자 하루 횟수 (FR-027): 실제 호출만 센다. 한국 0시 기준 ---

    private String userKey(long memberId) {
        return USER_PREFIX + memberId + ":" + LocalDate.now(clock.withZone(KST));
    }

    private int remaining(long memberId) {
        String v = redis.opsForValue().get(userKey(memberId));
        return Math.max(0, props.userDailyLimit() - (v == null ? 0 : Integer.parseInt(v)));
    }

    /** 한 번 쓴다. @return 쓰고 남은 횟수 */
    private int take(long memberId) {
        String key = userKey(memberId);
        Long used = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofDays(2));
        if (used == null || used > props.userDailyLimit()) {
            redis.opsForValue().decrement(key);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_DAILY_LIMIT", "오늘 AI 추천 횟수를 다 썼어요. 내일 다시 쓸 수 있어요.");
        }
        return (int) (props.userDailyLimit() - used);
    }

    private Provider nextProvider() {
        if (props.preferLocal() && state.localUsable()) return Provider.LOCAL;
        if (state.geminiUsable()) return Provider.GEMINI;
        return local.configured() ? Provider.LOCAL : null;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "지금은 추천할 수 없어요.");
    }
}
