package com.team.blog.shared.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.util.UrlPathHelper;

/**
 * 필터가 경로로 판단할 때 쓰는 주소. 컨트롤러를 고르는 Spring MVC와 같은 꼴(퍼센트 디코딩, ;뒤 제거)로 맞춘다.
 * getRequestURI()는 날 주소라서 /api/%70osts처럼 인코딩하면 필터 비교는 빗나가고 컨트롤러에는 닿았다 (보안 점검 2026-10-15).
 */
public final class RequestPaths {
    private RequestPaths() {}

    public static String of(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
