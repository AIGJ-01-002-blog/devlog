package com.team.blog.post.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.access.ReadablePost;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.post.infra.AutosaveStore;
import com.team.blog.post.infra.PostSql;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.markdown.RenderedHtmlCache;

/**
 * 글 하나 읽기 (docs/40). 판정은 PostAccessPolicy 한곳에서 한다. 본문 HTML은 원문에서 렌더링하고 Redis에 캐시한다(V3).
 * 수정 중인 글이라도 독자·작성자 모두 마지막 발행본을 본다 (작성자에게는 "수정 중" 안내 정보만 더한다).
 */
@Service
public class PostViewQuery {
    private final JdbcTemplate jdbc;
    private final PostAccessPolicy policy;
    private final ImageUrls imageUrls;
    private final AutosaveStore autosaves;
    private final RenderedHtmlCache htmlCache;
    private final ContentRenderer renderer;

    public PostViewQuery(JdbcTemplate jdbc, PostAccessPolicy policy, ImageUrls imageUrls, AutosaveStore autosaves,
                         RenderedHtmlCache htmlCache, ContentRenderer renderer) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.imageUrls = imageUrls;
        this.autosaves = autosaves;
        this.htmlCache = htmlCache;
        this.renderer = renderer;
    }

    /** 읽을 수 없으면 비어 있다 (호출한 쪽이 404, docs/06 R-1). */
    public Optional<PostView> find(long postId, Viewer viewer) {
        List<Row> rows = jdbc.query("""
                SELECT p.id, p.author_id, p.title, p.content_md, p.summary, p.edit_version, p.status, p.visibility,
                       p.published_at, p.first_public_at, p.edited_at, s.view_count, s.like_count, s.comment_count,
                       p.deleted_at IS NOT NULL AS deleted, p.hidden_at IS NOT NULL AS hidden, p.ai_diary, p.ai_assisted,
                       m.handle, m.nickname, m.bio, m.withdrawn_at IS NOT NULL AS withdrawn,
                       d.updated_at AS draft_saved_at,
                       """ + PostSql.THUMBNAIL_KEY + ", " + PostSql.PROFILE_IMAGE_KEY + """

                FROM post p
                JOIN member m ON m.id = p.author_id
                """ + PostSql.STAT_JOIN + "\n" + PostSql.PROFILE_IMAGE_JOIN + """

                LEFT JOIN post_draft d ON d.post_id = p.id
                WHERE p.id = ?
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getLong("author_id"), rs.getString("title"),
                rs.getString("content_md"), rs.getString("summary"), rs.getLong("edit_version"), rs.getString("thumbnail_key"),
                PostStatus.valueOf(rs.getString("status")), Visibility.valueOf(rs.getString("visibility")),
                instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("first_public_at")),
                instant(rs.getTimestamp("edited_at")), rs.getLong("view_count"), rs.getInt("like_count"),
                rs.getInt("comment_count"), rs.getBoolean("deleted"), rs.getBoolean("hidden"), rs.getString("handle"),
                rs.getString("nickname"), rs.getString("bio"), rs.getBoolean("withdrawn"),
                rs.getString("profile_image_key"), instant(rs.getTimestamp("draft_saved_at")), rs.getBoolean("ai_diary"),
                rs.getBoolean("ai_assisted")), postId);
        if (rows.isEmpty()) return Optional.empty();
        Row r = rows.get(0);
        ReadablePost readable = new ReadablePost(r.authorId, r.status, r.visibility, r.deleted, r.hidden, r.withdrawn);
        if (!policy.canRead(readable, viewer)) return Optional.empty();
        boolean mine = viewer.is(r.authorId);
        PostView.OwnerInfo owner = null;
        if (mine) {
            Instant editingAt = r.status == PostStatus.PUBLISHED ? latest(r.draftSavedAt, unflushedSavedAt(r.id)) : null;
            String hiddenReason = r.hidden
                    ? jdbc.queryForObject("SELECT hidden_reason FROM post WHERE id = ?", String.class, r.id) : null;
            owner = new PostView.OwnerInfo(editingAt != null, editingAt, r.hidden, hiddenReason);
        }
        // 본문 HTML은 저장하지 않고 원문을 렌더링해 캐시한다 (V3). 임시글은 상세로 보이지 않으므로 렌더링하지 않는다
        String html = r.status == PostStatus.PUBLISHED ? htmlCache.html(r.id, r.editVersion, r.authorId, r.contentMd) : "";
        String head = r.contentMd.length() > 600 ? r.contentMd.substring(0, 600) : r.contentMd;
        return Optional.of(new PostView(r.id, "/@" + r.handle + "/posts/" + r.id, r.title, html, renderer.summary(r.summary, head),
                imageUrls.urlOf(r.thumbnailKey), r.status, r.visibility, r.publishedAt, r.firstPublicAt, r.editedAt, r.viewCount,
                r.likeCount, r.commentCount,
                new PostView.Writer(r.authorId, r.handle, r.nickname, r.bio, imageUrls.urlOf(r.profileImageKey)), mine, owner, r.aiDiary, r.aiAssisted));
    }

    /** DB에 아직 반영 전인 자동 저장(최대 1분)도 "수정 중"으로 본다. Redis가 안 되면 DB 작업본만 본다. */
    private Instant unflushedSavedAt(long postId) {
        try {
            return autosaves.read(postId).map(AutosaveStore.Snapshot::savedAt).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private record Row(long id, long authorId, String title, String contentMd, String summary, long editVersion, String thumbnailKey,
                       PostStatus status, Visibility visibility, Instant publishedAt, Instant firstPublicAt,
                       Instant editedAt, long viewCount, int likeCount, int commentCount, boolean deleted,
                       boolean hidden, String handle, String nickname, String bio, boolean withdrawn,
                       String profileImageKey, Instant draftSavedAt, boolean aiDiary, boolean aiAssisted) {}
}
