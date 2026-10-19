package com.team.blog.discovery.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.team.blog.account.application.MemberProfileQuery;
import com.team.blog.account.application.SocialLinks;
import com.team.blog.follow.application.FollowQuery;
import com.team.blog.follow.application.FollowSql;
import com.team.blog.friend.application.FriendQuery;
import com.team.blog.friend.application.FriendService;
import com.team.blog.like.application.LikeQuery;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.query.PostCard;
import com.team.blog.post.query.PostCardPage;
import com.team.blog.post.query.PostCardQuery;
import com.team.blog.post.query.PostFilter;
import com.team.blog.post.query.PostListSpec;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.series.application.SeriesQuery;
import com.team.blog.series.application.SeriesSql;
import com.team.blog.tag.application.TagSql;
import com.team.blog.topic.application.TopicQuery;
import com.team.blog.topic.application.TopicSql;

/**
 * 홈(전체 글)·개인 블로그·태그·팔로잉·좋아한 글 목록과 블로그 머리 (docs/10 §4·§5·§7). 여기는 조립만 한다:
 * 글 카드는 글 모듈(PostCardQuery)이 읽고, 팔로우·태그·좋아요·친구 조건은 각 모듈이 넘긴다 (헌법 IV).
 * 작성자 본인이 봐도 블로그에는 공개 글만 나온다 (spec 003 FR-021).
 */
@Service
public class FeedQuery {
    private final PostCardQuery posts;
    private final MemberProfileQuery memberProfiles;
    private final FriendService friends;
    private final FriendQuery friendQuery;
    private final FollowQuery follows;
    private final LikeQuery likes;
    private final SocialLinks socialLinks;
    private final SeriesQuery series;
    private final TopicQuery topics;

    public FeedQuery(PostCardQuery posts, MemberProfileQuery memberProfiles, FriendService friends,
                     FriendQuery friendQuery, FollowQuery follows, LikeQuery likes, SocialLinks socialLinks,
                     SeriesQuery series, TopicQuery topics) {
        this.posts = posts;
        this.memberProfiles = memberProfiles;
        this.friends = friends;
        this.friendQuery = friendQuery;
        this.follows = follows;
        this.likes = likes;
        this.socialLinks = socialLinks;
        this.series = series;
        this.topics = topics;
    }

    /**
     * @param friendship        보는 사람 기준 친구 관계 (NONE·SENT·RECEIVED·FRIENDS). 비회원·본인이면 null
     * @param lastActiveDaysAgo 친구이고 양쪽 모두 공개 설정을 켰을 때만 0~7 (008 FR-009·FR-010)
     * @param publicPostCount   친구가 보면 친구 공개 글까지 센 수 (docs/06 §3 "블로그 글 수")
     * @param followerCount     팔로워·팔로잉 수는 비회원도 본다 (016 FR-010). following은 보는 사람이 팔로우 중인지
     * @param socialLinks       블로그 머리의 소셜 정보 (spec 043). 비회원도 본다
     */
    public record BlogProfile(long id, String handle, String nickname, String bio, String profileImageUrl,
                              long publicPostCount, boolean mine, String friendship, Integer lastActiveDaysAgo,
                              long followerCount, long followingCount, boolean following, SocialLinks.Links socialLinks) {}

    /** 블로그를 검색 수집할지 정할 때 쓰는 공개 글 수. AI 일기는 세지 않는다 (spec 104) */
    public long indexableArticleCount(long authorId) {
        return posts.count(authorId, PostAccessPolicy.PUBLIC_LIST_CONDITION + " AND NOT p.ai_diary");
    }

    /** 홈 피드는 직접 쓴 글만 보인다. AI 일기는 블로그·일기 탭에 그대로 남는다 (spec 104 FR-008, 애드센스) */
    private static final PostFilter NOT_AI_DIARY = new PostFilter("p.ai_diary = ?", false);

    public PostCardPage home(String cursor) {
        return withBranches(posts.page(PostListSpec.everyone("home", NOT_AI_DIARY), cursor));
    }

    /**
     * 홈 브랜치 거르기 (072): 시리즈 s12 또는 주제 t34의 공개 글만. 형식이 틀리면 404.
     * 커서 이름에 브랜치를 넣어 다른 목록의 커서를 쓸 수 없다.
     */
    public PostCardPage home(String cursor, String branch) {
        if (branch == null || branch.isEmpty()) return home(cursor);
        if (!branch.matches("[st][1-9]\\d{0,17}")) throw new NotFoundException();
        long id = Long.parseLong(branch.substring(1));
        PostFilter filter = branch.charAt(0) == 's' ? SeriesSql.inSeries(id) : TopicSql.inTopic(id);
        return withBranches(posts.page(PostListSpec.everyone("home:" + branch, filter, NOT_AI_DIARY), cursor));
    }

    /** 카드마다 이어지는 브랜치를 붙인다. 시리즈가 먼저이고, 시리즈에 없는 글만 주제 브랜치를 갖는다 */
    private PostCardPage withBranches(PostCardPage page) {
        List<Long> ids = page.items().stream().map(PostCard::id).toList();
        Map<Long, PostCard.Branch> bySeries = series.branchesOf(ids);
        Map<Long, PostCard.Branch> byTopic = topics.branchesOf(ids);
        List<PostCard> items = page.items().stream().map(c -> {
            PostCard.Branch b = bySeries.getOrDefault(c.id(), byTopic.get(c.id()));
            return b == null ? c : c.withBranch(b);
        }).toList();
        return new PostCardPage(items, page.nextCursor(), page.friendsView());
    }

    public PostCardPage blog(String handle, String cursor) {
        return blog(handle, null, cursor, null);
    }

    /**
     * @param tag      정규화한 태그 이름. null이면 거르지 않는다 (010 블로그 안 태그 필터)
     * @param viewerId 보는 사람. 블로그 주인과 수락된 친구면 친구 공개 글도 함께 나온다 (docs/06 §3)
     */
    public PostCardPage blog(String handle, String tag, String cursor, Long viewerId) {
        long authorId = ownerId(handle).orElseThrow(NotFoundException::new);
        boolean friend = viewerId != null && friendQuery.areFriends(authorId, viewerId);
        // 목록 이름이 달라 친구 목록 커서를 공개 목록에 쓸 수 없다 (정렬 기준도 다름)
        String name = (friend ? "blogf:" : "blog:") + authorId + (tag == null ? "" : ":tag:" + tag);
        List<PostFilter> filters = new ArrayList<>();
        if (tag != null) filters.add(TagSql.taggedWith(tag));
        return posts.page(new PostListSpec(name, friend, authorId, filters), cursor);
    }

    /** 태그별 목록 (010 FR-019). 없는 태그도 빈 목록이라 비공개 글에만 쓰인 태그와 구별되지 않는다. */
    public PostCardPage tag(String tag, String cursor) {
        return posts.page(PostListSpec.everyone("tag:" + tag, TagSql.taggedWith(tag)), cursor);
    }

    /**
     * 팔로잉 피드 (016 US2): 내가 팔로우한 사람의 공개 글만, 홈과 같은 정렬·카드·9개 (FR-016·FR-017).
     * 친구 공개 글은 친구여도 넣지 않는다.
     */
    public PostCardPage following(long memberId, String cursor) {
        return posts.page(PostListSpec.everyone("feed:" + memberId, FollowSql.authorFollowedBy(memberId)), cursor);
    }

    /**
     * 좋아한 글 (027 US1): 좋아요를 누른 최신순, 9개씩. 순서·커서는 좋아요 모듈이 정하고, 카드는 번호로 한 번 더 읽는다.
     * 친구 공개 글이 섞일 수 있어 응답을 저장하지 않는다.
     */
    public PostCardPage liked(long memberId, String cursor) {
        LikeQuery.PostIdPage ids = likes.likedPosts(memberId, cursor, posts.pageSize());
        return new PostCardPage(posts.cards(ids.postIds(), PostAccessPolicy.FRIENDS_LIST_CONDITION), ids.nextCursor(), true);
    }

    public Optional<BlogProfile> profile(String handle, Long viewerId) {
        return memberProfiles.findActive(handle).map(m -> {
            boolean mine = viewerId != null && viewerId == m.id();
            FollowQuery.Counts counts = follows.counts(m.id());
            boolean viewerFollows = viewerId != null && !mine && follows.isFollowing(viewerId, m.id());
            String relation = null;
            Integer lastActive = null;
            String countCondition = PostAccessPolicy.PUBLIC_LIST_CONDITION;
            if (viewerId != null && !mine) {
                FriendService.Relation r = friends.relation(viewerId, m.id());
                relation = r.name();
                lastActive = friendQuery.lastActiveDaysAgo(viewerId, m.id()).orElse(null);
                if (r == FriendService.Relation.FRIENDS) countCondition = PostAccessPolicy.FRIENDS_LIST_CONDITION;
            }
            return new BlogProfile(m.id(), m.handle(), m.nickname(), m.bio(), m.profileImageUrl(), posts.count(m.id(), countCondition),
                    mine, relation, lastActive, counts.followers(), counts.following(), viewerFollows, socialLinks.of(m.id()));
        });
    }

    /** 블로그 주인 번호. 탈퇴 신청했거나 정리된 회원은 없는 것으로 본다. */
    public Optional<Long> ownerId(String handle) {
        return memberProfiles.activeId(handle);
    }
}
