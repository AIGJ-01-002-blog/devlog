package com.team.blog.post.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.team.blog.account.domain.Member;
import com.team.blog.account.domain.Visibility;
import com.team.blog.account.infra.MemberRepository;
import com.team.blog.post.domain.Post;
import com.team.blog.post.domain.PostDraft;
import com.team.blog.post.infra.AutosaveStore;
import com.team.blog.post.infra.AutosaveStore.SaveResult;
import com.team.blog.post.infra.IdempotencyStore;
import com.team.blog.post.infra.PostDraftRepository;
import com.team.blog.post.infra.PostRepository;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.FieldErrorItem;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.markdown.RenderedContent;
import com.team.blog.shared.time.Times;

/**
 * 글 쓰기 쪽 유스케이스: 새 글, 자동 저장, 수동 저장, 변경 취소, 발행, 공개 범위 변경 (docs/04·05·06).
 *
 * 버전 규칙: 저장이 받아들여질 때마다 편집 버전이 1 오른다. 모든 저장(자동·수동·발행·변경 취소)은 Redis Lua 한 번으로
 * "현재 버전 == baseVersion이면 기록"을 원자적으로 판정한다. 현재 버전은 max(Redis, post_draft, post)라서
 * 어느 경로로 저장했든 오래된 탭의 저장은 409가 된다. Redis가 멈추면 행 잠금과 DB 버전으로 같은 판정을 한다.
 * 남의 글·없는 글은 모두 404다 (헌법 II).
 */
@Service
public class PostCommandService {
    private static final Logger log = LoggerFactory.getLogger(PostCommandService.class);
    private static final String CONFLICT_MESSAGE = "다른 탭이나 기기에서 이 글이 수정되었어요.";

    private final PostRepository posts;
    private final PostDraftRepository drafts;
    private final MemberRepository members;
    private final AutosaveStore autosave;
    private final IdempotencyStore idempotency;
    private final EditorStateLoader editorState;
    private final ContentRenderer renderer;
    private final List<PublishExtension> extensions;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final BlogProperties.Post rules;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public PostCommandService(PostRepository posts, PostDraftRepository drafts, MemberRepository members,
                              AutosaveStore autosave, IdempotencyStore idempotency, EditorStateLoader editorState,
                              ContentRenderer renderer, List<PublishExtension> extensions,
                              ApplicationEventPublisher events, TransactionTemplate tx, BlogProperties props, Clock clock) {
        this.posts = posts;
        this.drafts = drafts;
        this.members = members;
        this.autosave = autosave;
        this.idempotency = idempotency;
        this.editorState = editorState;
        this.renderer = renderer;
        this.extensions = extensions;
        this.events = events;
        this.tx = tx;
        this.rules = props.post();
        this.clock = clock;
    }

    public record Created(long id, long version, Visibility visibility) {}

    public record Saved(long version, Instant savedAt) {}

    public record PublishResult(long id, String url, Instant publishedAt, Instant firstPublicAt, Instant editedAt,
                                long version, Visibility visibility, boolean firstPublish) {}

    public record VisibilityResult(Visibility visibility, Instant firstPublicAt) {}

    /** [새 글] 또는 충돌 창의 [새 임시글로 따로 저장]. 공개 범위는 본인의 기본 공개 범위로 시작한다 (docs/06 V-4). */
    public Created create(long memberId, String rawTitle, String rawContent) {
        PostInput in = PostInput.forSave(rawTitle, rawContent, rules);
        if (in.title().isBlank() && in.contentMd().isBlank()) {
            Optional<Created> reused = reusableEmptyDraft(memberId);
            if (reused.isPresent()) return reused.get();
        }
        return tx.execute(s -> {
            Visibility v = members.findById(memberId).map(Member::getDefaultVisibility).orElseThrow(NotFoundException::new);
            Post p = posts.save(Post.newDraft(memberId, v, in.title(), in.contentMd(), Times.now(clock)));
            return new Created(p.getId(), p.getEditVersion(), p.getVisibility());
        });
    }

    /** 자정 AI 일기 (071, spec 088). 빈 임시글을 다시 쓰지 않고 늘 새로 만들며, 검색 수집에서 빼도록 표시해 둔다. */
    public Created createAiDiary(long memberId, String rawTitle, String rawContent) {
        PostInput in = PostInput.forSave(rawTitle, rawContent, rules);
        return tx.execute(s -> {
            Visibility v = members.findById(memberId).map(m -> m.getDefaultVisibility()).orElseThrow(NotFoundException::new);
            Post p = posts.save(Post.newAiDiary(memberId, v, in.title(), in.contentMd(), Times.now(clock)));
            return new Created(p.getId(), p.getEditVersion(), p.getVisibility());
        });
    }

    /**
     * [새 글]을 열 때마다 빈 임시글이 쌓이지 않게, 아직 아무것도 쓰지 않은 임시글이 있으면 그 글을 돌려준다.
     * 자동 저장 버퍼에 쓰던 내용이 있으면 빈 글이 아니므로 건너뛴다. Redis를 확인할 수 없으면 새로 만든다.
     */
    private Optional<Created> reusableEmptyDraft(long memberId) {
        for (long id : posts.findEmptyDraftIds(memberId)) {
            try {
                if (autosave.exists(id)) continue;
            } catch (RuntimeException e) {
                return Optional.empty();
            }
            Optional<Post> p = posts.findOwn(id, memberId);
            if (p.isPresent()) return Optional.of(new Created(id, p.get().getEditVersion(), p.get().getVisibility()));
        }
        return Optional.empty();
    }

    /**
     * 자동 저장 (docs/04 §2-3). 평소에는 Redis에만 쓰고 1분 안에 스케줄러가 DB에 반영한다.
     * Redis가 멈추면 DB에 바로 쓴다 (§2-6).
     */
    public Saved autosave(long memberId, long postId, String rawTitle, String rawContent, long baseVersion) {
        PostInput in = PostInput.forSave(rawTitle, rawContent, rules);
        Post post = posts.findOwn(postId, memberId).orElseThrow(NotFoundException::new);
        long dbVersion = editorState.dbVersion(post, editorState.workingCopy(post));
        Instant now = Times.now(clock);
        SaveResult r;
        try {
            r = autosave.save(postId, memberId, baseVersion, dbVersion, in.title(), in.contentMd(), now, rules.autosaveTtl(), true);
        } catch (RuntimeException e) {
            log.warn("자동 저장 버퍼를 쓸 수 없어 DB에 바로 저장합니다 (post {}): {}", postId, e.getMessage());
            return saveToDatabase(memberId, postId, in, baseVersion, false);
        }
        return switch (r) {
            case SaveResult.Saved(long savedVersion) -> new Saved(savedVersion, now);
            case SaveResult.NotOwner n -> throw new NotFoundException();
            case SaveResult.Conflict c -> throw conflict(post);
        };
    }

    /** [저장]: 같은 버전 확인을 거쳐 DB에 즉시 반영한다 (docs/04 D-3, §2-5). */
    public Saved save(long memberId, long postId, String rawTitle, String rawContent, long baseVersion) {
        PostInput in = PostInput.forSave(rawTitle, rawContent, rules);
        return saveToDatabase(memberId, postId, in, baseVersion, true);
    }

    private Saved saveToDatabase(long memberId, long postId, PostInput in, long baseVersion, boolean useGate) {
        return tx.execute(s -> {
            Post post = posts.findOwnForUpdate(postId, memberId).orElseThrow(NotFoundException::new);
            Optional<PostDraft> draft = editorState.workingCopy(post);
            Instant now = Times.now(clock);
            long version = gate(post, draft, baseVersion, in, now, useGate);
            writeContent(post, draft, in, version, now);
            return new Saved(version, now);
        });
    }

    /**
     * 버전 확인. 받아들여지면 새 버전을 돌려준다. 행 잠금을 잡은 상태에서 부른다.
     * Redis가 살아 있으면 Lua로 판정해 자동 저장과 같은 기준을 쓰고, 멈췄으면 DB 버전만으로 판정한다.
     */
    private long gate(Post post, Optional<PostDraft> draft, long baseVersion, PostInput in, Instant now, boolean useRedis) {
        long dbVersion = editorState.dbVersion(post, draft);
        if (useRedis) {
            try {
                SaveResult r = autosave.save(post.getId(), post.getAuthorId(), baseVersion, dbVersion,
                        in.title(), in.contentMd(), now, rules.autosaveTtl(), true);
                return switch (r) {
                    case SaveResult.Saved(long savedVersion) -> savedVersion;
                    case SaveResult.NotOwner n -> throw new NotFoundException();
                    case SaveResult.Conflict c -> throw conflict(post);
                };
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("자동 저장 버퍼를 쓸 수 없어 DB 버전으로 판정합니다 (post {}): {}", post.getId(), e.getMessage());
            }
        }
        if (baseVersion != dbVersion) throw conflict(post);
        return dbVersion + 1;
    }

    private void writeContent(Post post, Optional<PostDraft> draft, PostInput in, long version, Instant now) {
        if (!post.isPublished()) {
            post.saveDraftContent(in.title(), in.contentMd(), version, now);
            return;
        }
        // 발행한 글은 작업본에만 쓴다. 독자는 다시 발행할 때까지 발행본을 본다
        if (draft.isPresent()) draft.get().update(in.title(), in.contentMd(), version, now);
        else drafts.save(PostDraft.of(post.getId(), in.title(), in.contentMd(), version, now));
        post.bumpVersion(post.getEditVersion(), now); // updated_at만 갱신 → 내 글 관리에서 위로 올라온다
    }

    private ApiException conflict(Post post) {
        EditorState server = editorState.load(post);
        return ApiException.conflict("VERSION_CONFLICT", CONFLICT_MESSAGE, server.asConflictDetails());
    }

    /** [변경 취소]: 작업본과 자동 저장을 버리고 발행본으로 돌아간다 (docs/04 §2-5). */
    public Saved discardWorkingCopy(long memberId, long postId) {
        return tx.execute(s -> {
            Post post = posts.findOwnForUpdate(postId, memberId).orElseThrow(NotFoundException::new);
            if (!post.isPublished()) {
                throw ApiException.badRequest("NOT_PUBLISHED", "발행한 글만 변경을 취소할 수 있어요.");
            }
            Optional<PostDraft> draft = editorState.workingCopy(post);
            Instant now = Times.now(clock);
            EditorState current = editorState.load(post);
            // 발행본 내용을 새 버전으로 기록해 두면, 취소 전 내용을 들고 있는 다른 탭의 저장은 409가 된다
            PostInput published = new PostInput(post.getTitle(), post.getContentMd());
            long version;
            try {
                SaveResult r = autosave.save(postId, memberId, current.version(), editorState.dbVersion(post, draft),
                        published.title(), published.contentMd(), now, rules.autosaveTtl(), false);
                version = r instanceof SaveResult.Saved(long savedVersion) ? savedVersion : current.version() + 1;
                if (!(r instanceof SaveResult.Saved)) autosave.delete(postId);
            } catch (RuntimeException e) {
                version = current.version() + 1;
            }
            draft.ifPresent(drafts::delete);
            post.bumpVersion(version, now);
            long done = version;
            events.publishEvent(new AutosaveCleanup(postId, done));
            return new Saved(done, now);
        });
    }

    /** 커밋 후 Redis 자동 저장 키 정리 요청 (docs/05 J-5). */
    public record AutosaveCleanup(long postId, long version) {}

    /** 발행·다시 발행 (docs/05 §7). idempotencyKey가 있으면 같은 요청은 한 번만 처리한다 (§6). */
    public PublishResult publish(PublishCommand cmd, String handle, String idempotencyKey) {
        List<FieldErrorItem> errors = new ArrayList<>();
        PostInput in = PostInput.forPublish(cmd.title(), cmd.contentMd(), rules, errors);
        if (cmd.visibility() == null) {
            errors.add(new FieldErrorItem("visibility", "INVALID_VISIBILITY", "공개 범위를 골라 주세요."));
        }
        String summary = PostInput.cleanSummary(cmd.summary(), errors);
        PublishCommand cleaned = new PublishCommand(cmd.postId(), cmd.memberId(), in.title(), in.contentMd(), summary,
                cmd.visibility(), cmd.tags() == null ? List.of() : cmd.tags(), cmd.baseVersion(),
                cmd.thumbnailUrl() == null || cmd.thumbnailUrl().isBlank() ? null : cmd.thumbnailUrl().strip(), cmd.thumbnailHidden());
        extensions.forEach(e -> e.validate(cleaned, errors));
        if (!errors.isEmpty()) throw ApiException.validation(errors);

        if (idempotencyKey == null || idempotencyKey.isBlank()) return doPublish(cleaned, handle);
        if (idempotencyKey.length() > 100) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "요청 키가 올바르지 않아요.");

        String key = "idem:publish:" + cmd.memberId() + ":" + idempotencyKey;
        String hash = hash(cleaned);
        IdempotencyStore.Claim claim = idempotency.claim(key, hash, rules.idempotencyTtl());
        switch (claim) {
            case IdempotencyStore.Claim.InProgress p ->
                    throw ApiException.conflict("IN_PROGRESS", "발행을 처리하고 있어요. 잠시 후 다시 확인해 주세요.");
            case IdempotencyStore.Claim.Mismatch m -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IDEMPOTENCY_KEY_REUSED", "같은 요청 키로 다른 내용을 보냈어요.");
            case IdempotencyStore.Claim.Completed(String responseJson) -> {
                return json.readValue(responseJson, PublishResult.class);
            }
            case IdempotencyStore.Claim.Acquired a -> { /* 아래에서 처리 */ }
        }
        try {
            PublishResult result = doPublish(cleaned, handle);
            idempotency.complete(key, hash, json.writeValueAsString(result), rules.idempotencyTtl());
            return result;
        } catch (RuntimeException e) {
            idempotency.release(key);
            throw e;
        }
    }

    private PublishResult doPublish(PublishCommand cmd, String handle) {
        // CPU 작업인 렌더링은 잠금을 잡기 전에 끝내 트랜잭션을 짧게 한다 (§7 ②)
        RenderedContent rendered = renderer.render(cmd.contentMd(), cmd.memberId());
        PostInput in = new PostInput(cmd.title(), cmd.contentMd());
        return tx.execute(s -> {
            Post post = posts.findOwnForUpdate(cmd.postId(), cmd.memberId()).orElseThrow(NotFoundException::new);
            Optional<PostDraft> draft = editorState.workingCopy(post);
            Instant now = Times.now(clock);
            long version = gate(post, draft, cmd.baseVersion(), in, now, true);
            boolean wasPublic = post.getFirstPublicAt() != null;
            boolean first = post.publish(in.title(), in.contentMd(), cmd.summary(), cmd.visibility(), version, now);
            draft.ifPresent(drafts::delete);
            extensions.forEach(e -> e.onPublish(post, rendered, cmd));
            if (first) {
                events.publishEvent(new PostEvents.PostPublished(post.getId(), post.getAuthorId(), post.getVisibility(), version, now));
            } else {
                events.publishEvent(new PostEvents.PostEdited(post.getId(), post.getAuthorId(), post.getVisibility(), version, now));
            }
            if (!wasPublic && post.getFirstPublicAt() != null) {
                events.publishEvent(new PostEvents.PostFirstPublic(post.getId(), post.getAuthorId(), post.getFirstPublicAt()));
            }
            events.publishEvent(new AutosaveCleanup(post.getId(), version));
            return new PublishResult(post.getId(), "/@" + handle + "/posts/" + post.getId(), post.getPublishedAt(),
                    post.getFirstPublicAt(), post.getEditedAt(), version, post.getVisibility(), first);
        });
    }

    /** 공개 범위 즉시 변경 (docs/06 §4). 다시 발행이 아니라 "수정됨"·작업본은 그대로다. */
    public VisibilityResult changeVisibility(long memberId, long postId, Visibility to) {
        if (to == null) {
            throw ApiException.validation(List.of(new FieldErrorItem("visibility", "INVALID_VISIBILITY", "공개 범위를 골라 주세요.")));
        }
        return tx.execute(s -> {
            Post post = posts.findOwnForUpdate(postId, memberId).orElseThrow(NotFoundException::new);
            Visibility from = post.getVisibility();
            boolean wasPublic = post.getFirstPublicAt() != null;
            Instant now = Times.now(clock);
            post.changeVisibility(to, now);
            if (from != to) events.publishEvent(new PostEvents.PostVisibilityChanged(postId, memberId, from, to, now));
            if (!wasPublic && post.getFirstPublicAt() != null) {
                events.publishEvent(new PostEvents.PostFirstPublic(postId, memberId, post.getFirstPublicAt()));
            }
            return new VisibilityResult(post.getVisibility(), post.getFirstPublicAt());
        });
    }

    private static String hash(PublishCommand c) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(c.title(), c.contentMd(), String.valueOf(c.summary()), String.valueOf(c.visibility()),
                    String.join("\u0001", c.tags()), String.valueOf(c.baseVersion()), String.valueOf(c.thumbnailUrl()),
                    String.valueOf(c.thumbnailHidden()))) {
                md.update(part.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
