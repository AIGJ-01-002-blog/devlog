package com.team.blog.account.domain;

/** 로그인 수단과 블로그 주소 접두어 (docs/08 H-1). */
public enum AuthProvider {
    LOCAL(""), GOOGLE("go-"), GITHUB("gi-"), KAKAO("ka-"), FACEBOOK("fb-"), NAVER("nv-");

    private final String handlePrefix;

    AuthProvider(String handlePrefix) {
        this.handlePrefix = handlePrefix;
    }

    public String handlePrefix() {
        return handlePrefix;
    }
}
