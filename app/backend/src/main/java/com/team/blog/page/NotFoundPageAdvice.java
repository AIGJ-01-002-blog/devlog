package com.team.blog.page;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ErrorResponse;
import com.team.blog.shared.error.NotFoundException;

/**
 * 어느 주소에도 맞지 않는 요청 (spec 097 FR-001). 브라우저가 화면을 달라고 했으면(GET·HEAD, /api 밖, Accept에 text/html)
 * 머리말과 [홈으로]가 있는 앱 404 화면을 내리고, 그 밖(API·확장자가 있는 파일 요청)은 Accept와 상관없이 JSON 404를 낸다.
 * 전에는 모두 JSON이라 잘못된 링크로 들어온 방문자에게 {"code":"NOT_FOUND",...} 글자만 보였다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotFoundPageAdvice {
    private final SpaShell shell;
    private final String siteName;

    public NotFoundPageAdvice(SpaShell shell, BlogProperties props) {
        this.shell = shell;
        this.siteName = props.site().name();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Object> handle(NoResourceFoundException e, HttpServletRequest request) {
        if (wantsPage(request)) {
            ResponseEntity<String> page = PageController.notFoundPage(shell, siteName);
            return new ResponseEntity<>(page.getBody(), page.getHeaders(), page.getStatusCode());
        }
        // Accept 협상에 맡기면 text/html 요청에서 JSON을 쓰지 못해 406이 되므로 JSON으로 못 박는다
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("NOT_FOUND", NotFoundException.MESSAGE));
    }

    static boolean wantsPage(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) return false;
        String path = request.getRequestURI();
        if (path.startsWith("/api/") || path.equals("/api")) return false;
        // 파일 요청(/missing.css, /manifest.json 등)은 화면이 아니다. 마지막 조각에 확장자가 있으면 JSON 404
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.matches(".*\\.[A-Za-z0-9]{1,8}")) return false;
        String accept = request.getHeader("Accept");
        if (accept == null) return false;
        try {
            for (MediaType type : MediaType.parseMediaTypes(accept)) {
                if (type.isCompatibleWith(MediaType.TEXT_HTML) && !type.isWildcardType()) return true;
            }
        } catch (org.springframework.http.InvalidMediaTypeException ignored) {
            // 망가진 Accept는 화면 요청으로 보지 않는다
        }
        return false;
    }
}
