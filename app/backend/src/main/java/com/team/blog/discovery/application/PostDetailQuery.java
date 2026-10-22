package com.team.blog.discovery.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.team.blog.account.application.SocialLinks;
import com.team.blog.account.domain.Visibility;
import com.team.blog.follow.application.FollowQuery;
import com.team.blog.like.application.LikeQuery;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.post.query.PostView;
import com.team.blog.post.query.PostViewQuery;
import com.team.blog.tag.application.TagQuery;

/**
 * 글 상세 (docs/40). 글과 읽기 판정은 글 모듈(PostViewQuery)이 하고, 여기서는 보는 사람 기준의 팔로우·좋아요와
 * 태그·작성자 소셜 정보를 덧붙인다.
 */
@Service
public class PostDetailQuery {
    private final PostViewQuery posts;
    private final FollowQuery follows;
    private final LikeQuery likes;
    private final TagQuery tags;
    private final SocialLinks socialLinks;

    public PostDetailQuery(PostViewQuery posts, FollowQuery follows, LikeQuery likes, TagQuery tags, SocialLinks socialLinks) {
        this.posts = posts;
        this.follows = follows;
        this.likes = likes;
        this.tags = tags;
        this.socialLinks = socialLinks;
    }

    /**
     * @param following   보는 사람이 작성자를 팔로우 중인지 (016 FR-008, 작성자 영역 버튼)
     * @param socialLinks 글 아래 작성자 영역의 소셜 정보 (spec 044)
     */
    public record Author(long id, String handle, String nickname, String bio, String profileImageUrl, boolean following,
                         SocialLinks.Links socialLinks) {}

    /**
     * @param owner   작성자에게만 채운다. 독자에게는 null
     * @param aiDiary AI 일기로 만든 글. 독자에게는 같게 보이고 검색 수집·광고만 뺀다 (spec 088)
     * @param aiAssisted AI 도구로 만들거나 고친 글 (spec 107). 글 아래에 표기한다
     */
    public record Detail(long id, String url, String title, String contentHtml, String excerpt, String thumbnailUrl,
                         PostStatus status, Visibility visibility, Instant publishedAt, Instant firstPublicAt,
                         Instant editedAt, long viewCount, int likeCount, int commentCount, Author author,
                         boolean mine, PostView.OwnerInfo owner, List<String> tags, boolean liked, boolean aiDiary,
                         boolean aiAssisted) {
        /** 독자에게 보이는 날짜: 공개 글은 처음 공개된 날, 비공개 글은 최초 발행일 (spec 003 FR-005). */
        public Instant displayDate() {
            return firstPublicAt != null ? firstPublicAt : publishedAt;
        }

        public boolean publiclyVisible() {
            return status == PostStatus.PUBLISHED && visibility == Visibility.PUBLIC && (owner == null || !owner.hidden());
        }
    }

    public Optional<Detail> find(long postId, Viewer viewer) {
        return posts.find(postId, viewer).map(p -> {
            PostView.Writer w = p.author();
            boolean reader = !p.mine() && viewer.memberId() != null;
            return new Detail(p.id(), p.url(), p.title(), p.contentHtml(), p.excerpt(), p.thumbnailUrl(), p.status(), p.visibility(),
                    p.publishedAt(), p.firstPublicAt(), p.editedAt(), p.viewCount(), p.likeCount(), p.commentCount(),
                    new Author(w.id(), w.handle(), w.nickname(), w.bio(), w.profileImageUrl(),
                            reader && follows.isFollowing(viewer.memberId(), w.id()), socialLinks.of(w.id())),
                    p.mine(), p.owner(),
                    p.status() == PostStatus.PUBLISHED ? tags.tagsOf(p.id()) : List.of(),
                    reader && likes.likedBy(viewer.memberId(), p.id()), p.aiDiary(), p.aiAssisted());
        });
    }
}
