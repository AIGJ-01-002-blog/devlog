package com.team.blog.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class RequestBodyLimitFilterTest {
    private static final int DEFAULT_LIMIT = 256 * 1024;
    private static final int POST_LIMIT = 1024 * 1024;
    private static final int MCP_LIMIT = 4 * 1024 * 1024;
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(
            DataSize.ofKilobytes(256), DataSize.ofMegabytes(1), DataSize.ofMegabytes(4));

    @Test
    void 경로마다_상한이_다르고_본문을_그대로_받는_곳은_보지_않는다() {
        assertThat(filter.limitFor("POST", "/api/comments")).isEqualTo(DEFAULT_LIMIT);
        assertThat(filter.limitFor("PUT", "/api/posts/12")).isEqualTo(POST_LIMIT);
        assertThat(filter.limitFor("POST", "/api/markdown/preview")).isEqualTo(POST_LIMIT);
        assertThat(filter.limitFor("POST", "/api/mcp")).isEqualTo(MCP_LIMIT);
        assertThat(filter.limitFor("POST", "/api/images")).isNegative();
        assertThat(filter.limitFor("POST", "/api/files")).isNegative();
        assertThat(filter.limitFor("POST", "/api/me/profile-image")).isNegative();
        assertThat(filter.limitFor("PUT", "/api/render/jobs/7/video")).isNegative();
        assertThat(filter.limitFor("PUT", "/api/mcp/uploads/abc")).isNegative();
        // 같은 경로라도 본문을 그대로 받는 메서드가 아니면 기본 상한
        assertThat(filter.limitFor("PUT", "/api/posts/12/files")).isEqualTo(POST_LIMIT);
        assertThat(filter.limitFor("PUT", "/api/files")).isEqualTo(DEFAULT_LIMIT);
    }

    @Test
    void 길이를_밝힌_본문이_상한을_넘으면_컨트롤러에_닿기_전에_413() throws Exception {
        MockHttpServletRequest req = json("/api/comments", new byte[DEFAULT_LIMIT + 1]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).contains("PAYLOAD_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void 상한_안이면_그대로_넘긴다() throws Exception {
        MockHttpServletRequest req = json("/api/comments", new byte[DEFAULT_LIMIT]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(req);
    }

    @Test
    void 길이를_밝히지_않은_청크_본문도_상한까지만_읽고_끊는다() throws Exception {
        MockHttpServletRequest big = chunked("/api/comments", new byte[DEFAULT_LIMIT + 1]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(big, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void 상한_안의_청크_본문은_읽은_그대로_다시_읽을_수_있다() throws Exception {
        byte[] body = "{\"content\":\"안녕\"}".getBytes(StandardCharsets.UTF_8);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked("/api/comments", body), new MockHttpServletResponse(), chain);

        var passed = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
        assertThat(passed.getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(passed.getContentLengthLong()).isEqualTo(body.length);
    }

    @Test
    void 사진_올리기는_상한을_보지_않는다() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/images");
        req.setContent(new byte[MCP_LIMIT + 1]);
        req.setContentType("image/png");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(req);
    }

    private static MockHttpServletRequest json(String path, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContentType("application/json");
        req.setContent(body);
        return req;
    }

    /** MockHttpServletRequest는 setContent로 Content-Length를 채우므로, 청크 본문은 길이를 모르는 요청으로 감싼다. */
    private static MockHttpServletRequest chunked(String path, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        req.setContentType("application/json");
        req.addHeader("Transfer-Encoding", "chunked");
        req.setContent(body);
        return req;
    }
}
