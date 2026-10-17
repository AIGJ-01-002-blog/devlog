package com.team.blog.shared.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 본문 상한 (보안 점검 2026-10-16). ingress는 사진·영상 때문에 21MB까지 넘겨 주는데, JSON 본문은 앱이 크기를 따로 막지 않아
 * 큰 요청 몇 개를 동시에 보내면 파드가 메모리 부족으로 죽을 수 있었다. 경로별 상한을 넘으면 컨트롤러에 닿기 전에 413으로 끊는다.
 * <ul>
 *   <li>기본 256KB. 글 저장·미리보기는 1MB(본문 100,000자), MCP는 4MB(사진 2MB를 base64로 싣는 upload_image)</li>
 *   <li>사진·파일·영상·프로필 사진처럼 본문을 그대로 받는 곳은 컨트롤러가 스트림을 직접 세어 막으므로 여기서 보지 않는다</li>
 *   <li>Content-Length가 없는 청크 본문은 상한+1바이트까지만 미리 읽어 넘으면 끊고, 읽은 것을 그대로 넘겨 준다
 *       (Content-Length가 있으면 Tomcat이 그 길이 넘게 읽히지 않게 막는다)</li>
 * </ul>
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final int DEFAULT_LIMIT = 256 * 1024;
    static final int POST_LIMIT = 1024 * 1024;
    static final int MCP_LIMIT = 4 * 1024 * 1024;

    private static final Set<String> RAW_POST = Set.of("/api/images", "/api/files", "/api/me/profile-image");
    private static final Pattern RAW_VIDEO = Pattern.compile("/api/render/jobs/[^/]+/video");
    private static final Pattern MCP_UPLOAD = Pattern.compile("/api/mcp/uploads/[^/]+");

    static int limitFor(String method, String path) {
        if ("POST".equals(method) && RAW_POST.contains(path)) return -1;
        if ("PUT".equals(method) && RAW_VIDEO.matcher(path).matches()) return -1;
        if (("PUT".equals(method) || "POST".equals(method)) && MCP_UPLOAD.matcher(path).matches()) return -1;
        if (path.equals("/api/mcp")) return MCP_LIMIT;
        if (path.equals("/api/posts") || path.startsWith("/api/posts/") || path.equals("/api/markdown/preview")) return POST_LIMIT;
        return DEFAULT_LIMIT;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = limitFor(request.getMethod(), RequestPaths.of(request));
        if (limit < 0) {
            chain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            reject(response);
            return;
        }
        // 폼 본문은 Tomcat이 자기 스트림에서 읽고 max-http-form-post-size(1MB)로 막으므로 미리 읽지 않는다
        if (declared < 0 && isChunked(request) && !isForm(request)) {
            byte[] body;
            try (InputStream in = request.getInputStream()) {
                body = in.readNBytes(limit + 1);
            }
            if (body.length > limit) {
                reject(response);
                return;
            }
            chain.doFilter(new BufferedBody(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isChunked(HttpServletRequest request) {
        String te = request.getHeader("Transfer-Encoding");
        return te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
    }

    private static boolean isForm(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // 남은 본문은 읽지 않고 연결을 닫게 한다 (Tomcat max-swallow-size보다 큰 본문도 끝까지 받지 않는다)
        response.setHeader("Connection", "close");
        response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"요청이 너무 커요.\",\"errors\":[],\"details\":null}");
    }

    /** 미리 읽은 청크 본문을 다시 읽을 수 있게 돌려주는 요청. */
    static final class BufferedBody extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("비동기 읽기는 쓰지 않는다");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }
    }
}
