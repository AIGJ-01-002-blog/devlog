package com.team.blog.post.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.team.blog.account.domain.Visibility;

/**
 * 글. 발행·공개 범위의 시각 규칙(published_at, first_public_at, edited_at)을 이 엔티티의 메서드에 모은다 (docs/05 J-1).
 * edit_version은 Redis 자동 저장·스케줄러와 함께 쓰므로 @Version을 붙이지 않는다 (J-3).
 * 본문은 원본(content_md)만 저장한다. HTML·요약·대표 사진·조회수 같은 파생 값은 읽을 때 계산한다 (V3 정규화).
 */
@Entity
@Table(name = "post")
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private long authorId;

    @Column(nullable = false, length = 100)
    private String title = "";

    @Column(nullable = false)
    private String contentMd = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostStatus status = PostStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility = Visibility.PUBLIC;

    @Column(nullable = false)
    private long editVersion;

    /** 작성자가 쓴 짧은 소개. null이면 목록이 본문 앞부분으로 요약한다 (spec 045). */
    @Column(length = 150)
    private String summary;

    private Instant publishedAt;
    private Instant firstPublicAt;
    private Instant editedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deletedAt;
    // 숨김은 관리자 처리(SQL)만 바꾼다. 작성자의 수정·발행 저장이 숨김을 덮어쓰지 않게 읽기 전용으로 둔다 (019 FR-022)
    @Column(insertable = false, updatable = false)
    private Instant hiddenAt;
    @Column(insertable = false, updatable = false)
    private Long hiddenBy;
    @Column(length = 30, insertable = false, updatable = false)
    private String hiddenReason;

    // AI 일기로 만든 글 (spec 088). 검색 수집·사이트맵·광고에서 뺀다. 만들 때 한 번 정하고 바꾸지 않는다
    @Column(nullable = false, updatable = false)
    private boolean aiDiary;

    protected Post() {}

    public static Post newDraft(long authorId, Visibility visibility, String title, String contentMd, Instant now) {
        Post p = new Post();
        p.authorId = authorId;
        p.visibility = visibility;
        p.title = title;
        p.contentMd = contentMd;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /** AI 일기 임시글 (spec 088). 독자에게는 보통 글과 같고, 검색 엔진에만 수집을 거부한다. */
    public static Post newAiDiary(long authorId, Visibility visibility, String title, String contentMd, Instant now) {
        Post p = newDraft(authorId, visibility, title, contentMd, now);
        p.aiDiary = true;
        return p;
    }

    /** 임시글 내용 저장 (수동 저장, Redis 장애 시 자동 저장). */
    public void saveDraftContent(String title, String contentMd, long version, Instant now) {
        if (status != PostStatus.DRAFT) throw new IllegalStateException("발행한 글의 수정 내용은 작업본(post_draft)에 저장한다");
        this.title = title;
        this.contentMd = contentMd;
        this.editVersion = version;
        this.updatedAt = now;
    }

    /**
     * 최초 발행과 다시 발행 (docs/05 §7 ⑦, §8).
     * @return 최초 발행이면 true
     */
    public boolean publish(String title, String contentMd, String summary, Visibility visibility, long version, Instant now) {
        boolean first = status == PostStatus.DRAFT;
        this.title = title;
        this.contentMd = contentMd;
        this.summary = summary;
        this.status = PostStatus.PUBLISHED;
        if (publishedAt == null) publishedAt = now;
        if (!first) editedAt = now;
        this.editVersion = version;
        this.updatedAt = now;
        changeVisibilityInternal(visibility, now);
        return first;
    }

    /** 공개 범위만 바꾼다. 다시 발행이 아니므로 edited_at·작업본은 그대로다 (docs/06 §4). */
    public void changeVisibility(Visibility visibility, Instant now) {
        if (this.visibility == visibility) return;
        changeVisibilityInternal(visibility, now);
        this.updatedAt = now;
    }

    private void changeVisibilityInternal(Visibility visibility, Instant now) {
        this.visibility = visibility;
        // 처음으로 "발행 + 전체 공개"가 된 순간을 한 번만 기록한다. 껐다 켜도 목록 위치가 바뀌지 않는다 (docs/05 §3)
        if (status == PostStatus.PUBLISHED && visibility == Visibility.PUBLIC && firstPublicAt == null) {
            firstPublicAt = now;
        }
    }

    /** 변경 취소 등으로 작업 버전만 올린다. 오래된 탭의 저장을 충돌로 막기 위해서다. */
    public void bumpVersion(long version, Instant now) {
        this.editVersion = version;
        this.updatedAt = now;
    }

    public boolean isPublished() { return status == PostStatus.PUBLISHED; }
    public boolean isDeleted() { return deletedAt != null; }
    public boolean isHidden() { return hiddenAt != null; }
    public boolean isAiDiary() { return aiDiary; }

    public Long getId() { return id; }
    public long getAuthorId() { return authorId; }
    public String getTitle() { return title; }
    public String getContentMd() { return contentMd; }
    public String getSummary() { return summary; }
    public PostStatus getStatus() { return status; }
    public Visibility getVisibility() { return visibility; }
    public long getEditVersion() { return editVersion; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getFirstPublicAt() { return firstPublicAt; }
    public Instant getEditedAt() { return editedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getHiddenAt() { return hiddenAt; }
    public String getHiddenReason() { return hiddenReason; }
}
