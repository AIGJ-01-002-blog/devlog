package com.team.blog.post.infra;

import com.team.blog.account.application.MemberSql;

/**
 * 읽기 쿼리가 같이 쓰는 SQL 조각 (V3 정규화). 파생 값은 저장하지 않으므로 여기서 계산한다.
 * 별칭 약속: 글 p, 회원 m.
 */
public final class PostSql {
    private PostSql() {}

    /**
     * 목록 요약 재료: 작성자가 쓴 짧은 소개(045)와, 소개가 없을 때만 원문 앞부분. 긴 글도 앞부분만 풀어 읽고,
     * 소개가 있으면 본문을 읽지 않는다. ContentRenderer.summary(summary, content_head)로 요약을 만든다.
     */
    public static final String SUMMARY_SOURCE =
            "p.summary, CASE WHEN p.summary IS NULL THEN left(p.content_md, 600) END AS content_head";

    /**
     * 대표 사진의 썸네일(없으면 원본). 작성자가 고른 사진(047 post_thumbnail)이 먼저이고, 고르지 않았으면 본문 첫 사진이다.
     * 썸네일을 없앤 글(사진 없는 post_thumbnail 행)은 NULL이다.
     */
    public static final String THUMBNAIL_KEY = """
            (SELECT COALESCE(ri.thumb_storage_key, r.storage_key)
             FROM resource_image ri
             JOIN resource r ON r.id = ri.resource_id
             WHERE ri.resource_id = COALESCE(
                 (SELECT pt.resource_id FROM post_thumbnail pt WHERE pt.post_id = p.id),
                 (SELECT pim.resource_id FROM post_image pim
                  WHERE pim.post_id = p.id AND NOT EXISTS (SELECT 1 FROM post_thumbnail pt WHERE pt.post_id = p.id)
                  ORDER BY pim.position LIMIT 1))) AS thumbnail_key""";

    /** 작성자 프로필 사진 (회원당 하나, 회원 모듈의 조각). PROFILE_IMAGE_KEY와 함께 쓴다. */
    public static final String PROFILE_IMAGE_JOIN = MemberSql.PROFILE_IMAGE_JOIN;

    public static final String PROFILE_IMAGE_KEY = MemberSql.PROFILE_IMAGE_KEY;

    /**
     * 아무것도 쓰지 않은 임시글: 제목·본문·짧은 소개가 비었고 태그·사진·첨부파일·썸네일·시리즈도 없다.
     * [글쓰기]를 눌렀다 바로 나온 글을 휴지통 없이 지우고([나가기]·[삭제]·새벽 정리), [새 글]이 다시 쓸 때 같은 기준을 쓴다.
     */
    public static final String BLANK_DRAFT = """
            p.status = 'DRAFT' AND btrim(p.title) = '' AND btrim(p.content_md) = '' AND p.summary IS NULL
              AND NOT EXISTS (SELECT 1 FROM post_tag x WHERE x.post_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM post_image x WHERE x.post_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM post_file x WHERE x.post_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM post_thumbnail x WHERE x.post_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM series_post x WHERE x.post_id = p.id)""";

    /** 조회수·좋아요 수·댓글 수 (post_stat 뷰). 별칭 s. */
    public static final String STAT_JOIN = "JOIN post_stat s ON s.post_id = p.id";
}
