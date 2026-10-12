package com.team.blog.topic.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.post.query.PostCard;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.topic.application.BranchSuggester;
import com.team.blog.topic.application.SimilarPosts;
import com.team.blog.topic.application.TopicProperties;
import com.team.blog.topic.application.TopicQuery;

/** 주제 브랜치 (072). 읽기는 공개 글만 세서 누구에게나 같다. 추천·묶지 않기는 글쓴이만 한다. */
@RestController
public class TopicController {
    /** 홈 옆 칸에 보이는 수 */
    static final int POPULAR_LIMIT = 5;

    private final TopicQuery topics;
    private final SimilarPosts similar;
    private final BranchSuggester suggester;
    private final TopicProperties props;
    private final JdbcTemplate jdbc;

    public TopicController(TopicQuery topics, SimilarPosts similar, BranchSuggester suggester, TopicProperties props, JdbcTemplate jdbc) {
        this.topics = topics;
        this.similar = similar;
        this.suggester = suggester;
        this.props = props;
        this.jdbc = jdbc;
    }

    public record SuggestRequest(List<String> tags) {}

    public record OptOutRequest(boolean optOut) {}

    @GetMapping("/api/topics/popular")
    public ResponseEntity<List<TopicQuery.Popular>> popular() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(topics.popular(POPULAR_LIMIT));
    }

    /** 글 화면 브랜치 상자. 주제 브랜치에 없으면 본문 없이 204 */
    @GetMapping("/api/posts/{postId}/topic")
    public ResponseEntity<TopicQuery.Navigation> topic(@PathVariable String postId) {
        return topics.forPost(parseId(postId))
                .map(n -> ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(n))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noCache()).build());
    }

    /** 내용이 비슷한 다른 글. 공개 글이 아니면 빈 목록 */
    @GetMapping("/api/posts/{postId}/similar")
    public ResponseEntity<List<PostCard>> similar(@PathVariable String postId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(similar.of(parseId(postId), props.similarLimit()));
    }

    /** 발행 창 브랜치 추천. 저장 전 태그로 계산한다 */
    @PostMapping("/api/posts/{postId}/branch-suggestion")
    public ResponseEntity<BranchSuggester.Result> suggest(@CurrentMember MemberPrincipal me, @PathVariable String postId,
                                                          @RequestBody(required = false) SuggestRequest body) {
        long id = ownPost(me, postId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(suggester.suggest(me.id(), id, body == null ? List.of() : body.tags()));
    }

    /** [묶지 않기] / 다시 묶기 */
    @PutMapping("/api/posts/{postId}/topic-optout")
    public ResponseEntity<Void> optOut(@CurrentMember MemberPrincipal me, @PathVariable String postId, @RequestBody OptOutRequest body) {
        topics.setOptOut(ownPost(me, postId), body != null && body.optOut());
        return ResponseEntity.noContent().build();
    }

    /** 내 글(휴지통 제외)이 아니면 없는 글로 본다 */
    private long ownPost(MemberPrincipal me, String raw) {
        long id = parseId(raw);
        Boolean mine = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM post WHERE id = ? AND author_id = ? AND deleted_at IS NULL)",
                Boolean.class, id, me.id());
        if (!Boolean.TRUE.equals(mine)) throw new NotFoundException();
        return id;
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
