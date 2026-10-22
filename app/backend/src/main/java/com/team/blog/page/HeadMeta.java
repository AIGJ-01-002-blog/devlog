package com.team.blog.page;

import java.time.Instant;

/**
 * 페이지 머리말에 넣을 값 (docs/40 §5). 값은 SpaShell이 이스케이프한다.
 * indexable=false면 검색 엔진 수집을 막는다(noindex).
 * author가 있는 글(ogType article)은 검색 엔진용 구조화 데이터(JSON-LD Article)에 글쓴이로 들어간다 (spec 097).
 * follow는 noindex 화면 안의 링크를 따라가도 되는지다 (spec 107). 일기·짧은 글·태그·검색 결과처럼 독자가 읽는 화면은
 * 그 화면만 색인에서 빼고 링크는 따라가게 해, 연결된 긴 글이 발견되는 길을 막지 않는다. 로그인·설정 같은 개인 화면은 nofollow 그대로다.
 */
public record HeadMeta(String title, String description, String canonical, String ogType, String ogImage,
                       Instant publishedTime, Instant modifiedTime, boolean indexable, String author, boolean follow) {

    public HeadMeta(String title, String description, String canonical, String ogType, String ogImage,
                    Instant publishedTime, Instant modifiedTime, boolean indexable, String author) {
        this(title, description, canonical, ogType, ogImage, publishedTime, modifiedTime, indexable, author, false);
    }

    public HeadMeta(String title, String description, String canonical, String ogType, String ogImage,
                    Instant publishedTime, Instant modifiedTime, boolean indexable) {
        this(title, description, canonical, ogType, ogImage, publishedTime, modifiedTime, indexable, null, false);
    }

    public static HeadMeta site(String siteName, String description, String canonical, String ogImage) {
        return new HeadMeta(siteName, description, canonical, "website", ogImage, null, null, true);
    }

    /** 볼 수 없는 페이지·로그인 화면 등: 공통 문구 + noindex (docs/06 §3-1). */
    public static HeadMeta privatePage(String siteName, String title) {
        return new HeadMeta(title == null ? siteName : title + " - " + siteName, null, null, null, null, null, null, false);
    }

    /** 수집하지 않는 화면이면 링크는 따라가게 한다(noindex, follow). 수집하는 화면은 그대로 둔다 */
    public HeadMeta followLinks() {
        return new HeadMeta(title, description, canonical, ogType, ogImage, publishedTime, modifiedTime, indexable, author, true);
    }

    /** 이 화면은 수집하지 않고 링크만 따라가게 한다 (태그·검색 결과, spec 107) */
    public HeadMeta noindexFollow() {
        return new HeadMeta(title, description, canonical, ogType, ogImage, publishedTime, modifiedTime, false, author, true);
    }
}
