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

import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import com.team.blog.shared.config.BlogProperties;

/**
 * 요청 본문 상한 (보안 점검 2026-10-16). ingress는 사진·영상 때문에 21MB까지 넘겨 주는데, JSON 본문은 앱이 크기를 따로 막지 않아
 * 큰 요청 몇 개를 동시에 보내면 파드가 메모리 부족으로 죽을 수 있었다. 경로별 상한을 넘으면 컨트롤러에 닿기 전에 413으로 끊는다.
 * <ul>
 *   <li>상한은 {@code blog.web.max-body}(기본 256KB), 글 저장·미리보기 {@code max-post-body}(1MB), MCP {@code max-mcp-body}(4MB)</li>
 *   <li>사진·파일·영상·프로필 사진처럼 본문을 그대로 받는 곳은 컨트롤러가 스트림을 직접 세어 막으므로 여기서 보지 않는다</li>
 *   <li>Content-Length가 없는 청크 본문은 상한+1바이트까지만 미리 읽어 넘으면 끊고, 읽은 것을 그대로 넘겨 준다
 *       (Content-Length가 있으면 Tomcat이 그 길이 넘게 읽히지 않게 막는다)</li>
 * </ul>
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final int defaultLimit;
    private final int postLimit;
    private final int mcpLimit;

    private static final Set<String> RAW_POST = Set.of("/api/images", "/api/files", "/api/me/profile-image");
    private static final Pattern RAW_VIDEO = Pattern.compile("/api/render/jobs/[^/]+/video");
    private static final Pattern MCP_UPLOAD = Pattern.compile("/api/mcp/uploads/[^/]+");

    public RequestBodyLimitFilter(BlogProperties props) {
        this(props.web().maxBody(), props.web().maxPostBody(), props.web().maxMcpBody());
    }

    RequestBodyLimitFilter(DataSize defaultLimit, DataSize postLimit, DataSize mcpLimit) {
        this.defaultLimit = Math.toIntExact(defaultLimit.toBytes());
        this.postLimit = Math.toIntExact(postLimit.toBytes());
        this.mcpLimit = Math.toIntExact(mcpLimit.toBytes());
    }

    /** 이 요청의 본문 상한(바이트). 본문을 그대로 받아 스스로 세는 업로드는 -1. */
    int limitFor(String method, String path) {
        if ("POST".equals(method) && RAW_POST.contains(path)) return -1;
        if ("PUT".equals(method) && RAW_VIDEO.matcher(path).matches()) return -1;
        if (("PUT".equals(method) || "POST".equals(method)) && MCP_UPLOAD.matcher(path).matches()) return -1;
        if (path.equals("/api/mcp")) return mcpLimit;
        if (path.equals("/api/posts") || path.startsWith("/api/posts/") || path.equals("/api/markdown/preview")) return postLimit;
        return defaultLimit;
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
        // 길이를 밝히지 않은 본문(HTTP/1.1 청크, Content-Length 없는 HTTP/2)은 상한까지만 미리 읽는다.
        // 폼 본문은 Tomcat이 자기 스트림에서 읽고 max-http-form-post-size(1MB)로 막으므로 미리 읽지 않는다
        if (declared < 0 && mayHaveBody(request) && !isForm(request)) {
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

    /** 본문이 없는 메서드는 빈 스트림을 미리 읽지 않는다 */
    private static boolean mayHaveBody(HttpServletRequest request) {
        String m = request.getMethod();
        return !("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m) || "TRACE".equals(m));
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
