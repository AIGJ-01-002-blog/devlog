package com.team.blog.post.query;

import java.time.Instant;

import com.team.blog.account.domain.Visibility;
import com.team.blog.post.domain.PostStatus;

/**
 * 읽기 판정을 통과한 글 하나 (글 모듈이 아는 것만). 팔로우·좋아요·태그·소셜 정보는 discovery가 덧붙인다.
 *
 * @param owner   작성자에게만 채운다. 독자에게는 null
 * @param aiDiary AI 일기로 만든 글이면 true. 화면은 같고 검색 수집만 거부한다 (spec 088)
 */
public record PostView(long id, String url, String title, String contentHtml, String excerpt, String thumbnailUrl,
                       PostStatus status, Visibility visibility, Instant publishedAt, Instant firstPublicAt,
                       Instant editedAt, long viewCount, int likeCount, int commentCount, Writer author,
                       boolean mine, OwnerInfo owner, boolean aiDiary) {
    public record Writer(long id, String handle, String nickname, String bio, String profileImageUrl) {}

    /** @param hiddenReason 숨긴 글이면 숨김 사유 코드 (019 FR-021) */
    public record OwnerInfo(boolean editing, Instant editingSavedAt, boolean hidden, String hiddenReason) {}
}
