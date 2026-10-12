package com.team.blog.mcp.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.mcp.application.McpImages;
import com.team.blog.media.PostImages;
import com.team.blog.shared.error.ApiException;

/**
 * AI용 한 번 쓰는 사진 올리기 주소 (060). create_image_upload_link가 만든 주소로 사진 파일 바이트를 그대로 보낸다.
 * <pre>curl -T shot.png https://devlog.life/api/mcp/uploads/{표}</pre>
 * 표가 곧 권한이라 쿠키·토큰을 보지 않는다(MCP 보안 체인). 표는 10분 동안 한 번만 쓸 수 있고, 쓰는 순간 지워진다.
 */
@RestController
public class McpUploadController {
    public static final String PATH = McpController.PATH + "/uploads/*";

    private final McpImages images;
    private final JdbcTemplate jdbc;

    public McpUploadController(McpImages images, JdbcTemplate jdbc) {
        this.images = images;
        this.jdbc = jdbc;
    }

    @RequestMapping(path = McpController.PATH + "/uploads/{ticket}", method = {RequestMethod.PUT, RequestMethod.POST},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String ticket, HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > PostImages.MAX_BYTES) return tooLarge();
        Optional<McpImages.Ticket> t = images.useTicket(ticket);
        if (t.isEmpty()) {
            return json(HttpStatus.NOT_FOUND, Map.of("error", "올리기 주소가 없거나 이미 썼거나 10분이 지났어요. create_image_upload_link로 새 주소를 받아 주세요."));
        }
        List<String> status = jdbc.query("SELECT status FROM member WHERE id = ? AND deleted_at IS NULL", (rs, i) -> rs.getString(1), t.get().memberId());
        if (status.isEmpty() || !"ACTIVE".equals(status.getFirst())) {
            return json(HttpStatus.FORBIDDEN, Map.of("error", "지금 계정 상태로는 사진을 올릴 수 없어요."));
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(PostImages.MAX_BYTES + 1);
        }
        if (body.length > PostImages.MAX_BYTES) return tooLarge();
        if (body.length == 0) return json(HttpStatus.BAD_REQUEST, Map.of("error", "사진 파일이 비어 있어요."));
        try {
            McpImages.Uploaded up = images.upload(t.get().memberId(), body, t.get().alt());
            return json(HttpStatus.CREATED, Map.of("url", up.url(), "markdown", up.markdown(), "width", up.width(), "height", up.height()));
        } catch (ApiException e) {
            return json(e.status(), Map.of("error", e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<Map<String, Object>> tooLarge() {
        return json(HttpStatus.CONTENT_TOO_LARGE, Map.of("error", "사진은 10MB까지 올릴 수 있어요."));
    }
}
