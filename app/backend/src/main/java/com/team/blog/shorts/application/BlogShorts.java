package com.team.blog.shorts.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import com.team.blog.account.application.MemberProfileQuery;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.shared.error.NotFoundException;

/**
 * 블로그·글 화면에 보이는 쇼츠 (spec 103, #399). 회원이 직접 올린 뒤 남긴 영상 주소(shorts_upload)가 있는 쇼츠만 보인다.
 * devlog는 플랫폼에 나가 영상이 정말 있는지 확인하지 않는다(099 FR-015와 같다).
 * 바탕 글이 있으면 그 글이 누구에게나 보일 때(공개·발행·숨김 아님)만 보인다. 바탕 글이 없는 쇼츠(매매 재현 등)는 블로그 탭에만 보인다.
 * 회원이 쇼츠마다 "블로그에 보이기"(shorts.on_blog)를 끄면 어디에도 보이지 않는다.
 */
@Service
public class BlogShorts {
    static final int BLOG_MAX = 60;
    static final int POST_MAX = 6;

    /** @param embedId YouTube 영상이면 영상 ID(화면이 youtube-nocookie로 재생), 아니면 null */
    public record Link(UploadLink.Platform platform, String url, String embedId) {}

    /** @param postUrl 바탕 글 주소. 바탕 글이 없으면 null */
    public record Card(long id, String title, String postTitle, String postUrl, Integer durationMs, Instant uploadedAt, List<Link> links) {}

    /** 바탕 글은 쇼츠 주인의 글만 될 수 있어(ShortsService.ownPost) 글쓴이 = m이다. 그래도 조인에 author_id를 넣어 남의 글은 바탕 글로 보지 않는다 */
    private static final String SELECT = """
            SELECT s.id, s.title, s.duration_ms, p.id AS post_id, p.title AS post_title, m.handle,
                   u.platform, u.url, u.uploaded_at
            FROM shorts s
            JOIN member m ON m.id = s.member_id
            JOIN shorts_upload u ON u.shorts_id = s.id
            LEFT JOIN post p ON p.id = s.post_id AND p.author_id = s.member_id
            WHERE s.on_blog AND m.withdrawn_at IS NULL
              AND (s.post_id IS NULL OR (p.id IS NOT NULL AND\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + "))";

    private final ShortsProperties props;
    private final JdbcTemplate jdbc;
    private final MemberProfileQuery members;

    public BlogShorts(ShortsProperties props, JdbcTemplate jdbc, MemberProfileQuery members) {
        this.props = props;
        this.jdbc = jdbc;
        this.members = members;
    }

    /** 블로그의 "쇼츠" 탭. 최근에 올린 순서. 쇼츠가 꺼져 있거나 없는 회원이면 404 */
    public List<Card> ofBlog(String handle) {
        if (!props.enabled()) throw new NotFoundException();
        long memberId = members.activeId(handle).orElseThrow(NotFoundException::new);
        return read(SELECT + " AND s.member_id = ?", BLOG_MAX, memberId);
    }

    /** 글 상세의 "이 글의 쇼츠". 글이 누구에게나 보이지 않으면 비어 있다. 쇼츠가 꺼져 있으면 404 */
    public List<Card> ofPost(long postId) {
        if (!props.enabled()) throw new NotFoundException();
        return read(SELECT + " AND s.post_id = ?", POST_MAX, postId);
    }

    /** 쇼츠 한 편에 플랫폼 링크가 여럿이라 행을 쇼츠로 묶는다. 올린 시각은 가장 최근 것 */
    private List<Card> read(String sql, int max, Object key) {
        Map<Long, Card> cards = new LinkedHashMap<>();
        jdbc.query(sql + " ORDER BY u.uploaded_at DESC, s.id DESC", (RowCallbackHandler) rs -> {
            long id = rs.getLong("id");
            Card card = cards.get(id);
            if (card == null) {
                if (cards.size() >= max) return;
                long postId = rs.getLong("post_id");
                boolean hasPost = !rs.wasNull();
                String handle = rs.getString("handle");
                int duration = rs.getInt("duration_ms");
                Integer durationMs = rs.wasNull() ? null : duration;
                card = new Card(id, rs.getString("title"), hasPost ? rs.getString("post_title") : null,
                        hasPost && handle != null ? "/@" + handle + "/posts/" + postId : null, durationMs,
                        rs.getTimestamp("uploaded_at").toInstant(), new ArrayList<>());
                cards.put(id, card);
            }
            UploadLink.Platform platform = UploadLink.Platform.valueOf(rs.getString("platform"));
            String url = rs.getString("url");
            card.links().add(new Link(platform, url, platform == UploadLink.Platform.YOUTUBE ? UploadLink.youtubeId(url).orElse(null) : null));
        }, key);
        return cards.values().stream().map(c -> new Card(c.id(), c.title(), c.postTitle(), c.postUrl(), c.durationMs(), c.uploadedAt(),
                c.links().stream().sorted(java.util.Comparator.comparing(Link::platform)).toList())).toList();
    }
}
