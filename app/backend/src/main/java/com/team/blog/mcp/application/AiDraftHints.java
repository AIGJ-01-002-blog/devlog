package com.team.blog.mcp.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.team.blog.post.application.PublishCommand;
import com.team.blog.post.application.PublishExtension;
import com.team.blog.post.domain.Post;
import com.team.blog.shared.markdown.RenderedContent;

/**
 * AI(MCP)가 만든 임시글의 태그 제안과 발행 요청 (052). 임시글에는 태그를 저장하지 않으므로(태그는 발행 때 붙는다)
 * 제안을 따로 두었다가 발행 창에 미리 채운다. 글이 발행되면 할 일이 끝났으니 지운다.
 */
@Component
public class AiDraftHints implements PublishExtension {
    public record Hint(List<String> tags, Instant publishRequestedAt) {}

    private final JdbcTemplate jdbc;

    public AiDraftHints(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void suggestTags(long postId, List<String> tags) {
        jdbc.update("""
                INSERT INTO post_ai_hint (post_id, tags) VALUES (?, ?)
                ON CONFLICT (post_id) DO UPDATE SET tags = EXCLUDED.tags
                """, postId, String.join(",", tags));
    }

    /**
     * AI 도구(MCP)가 만들거나 고치거나 발행한 글로 표시한다 (spec 107). 글 아래에 "AI 보조 작성"이 보이고, 한 번 붙으면 떼지 않는다.
     * 사람이 나중에 다 고쳐 써도 처음 초안을 AI가 쓴 사실은 그대로라서다.
     */
    public void markAssisted(long postId) {
        jdbc.update("UPDATE post SET ai_assisted = true WHERE id = ? AND NOT ai_assisted", postId);
    }

    public void requestPublish(long postId, Instant at) {
        jdbc.update("""
                INSERT INTO post_ai_hint (post_id, publish_requested_at) VALUES (?, ?)
                ON CONFLICT (post_id) DO UPDATE SET publish_requested_at = EXCLUDED.publish_requested_at
                """, postId, Timestamp.from(at));
    }

    public Optional<Hint> find(long postId) {
        return jdbc.query("SELECT tags, publish_requested_at FROM post_ai_hint WHERE post_id = ?", (rs, i) -> {
            Timestamp at = rs.getTimestamp("publish_requested_at");
            String tags = rs.getString("tags");
            return new Hint(tags.isEmpty() ? List.of() : Arrays.asList(tags.split(",")), at == null ? null : at.toInstant());
        }, postId).stream().findFirst();
    }

    @Override
    public void onPublish(Post post, RenderedContent rendered, PublishCommand command) {
        jdbc.update("DELETE FROM post_ai_hint WHERE post_id = ?", post.getId());
    }
}
