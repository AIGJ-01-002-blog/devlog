package com.team.blog.shorts.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shorts.application.RenderQueue;
import com.team.blog.shorts.application.RenderWorkerStatus;
import com.team.blog.shorts.application.ShortsProperties;

/**
 * 렌더 워커 API (099 FR-006). 세션·CSRF 없이 X-Render-Token만 본다(SecurityConfig의 상태 없는 사슬).
 * 토큰이 비었거나 쇼츠가 꺼져 있으면 주소가 없는 것처럼 404, 토큰이 틀리면 401이다.
 */
@RestController
public class RenderWorkerController {
    public static final String PATH = "/api/render/**";
    static final String TOKEN_HEADER = "X-Render-Token";

    private final RenderQueue queue;
    private final ShortsProperties props;
    private final RenderWorkerStatus status;

    public RenderWorkerController(RenderQueue queue, ShortsProperties props, RenderWorkerStatus status) {
        this.queue = queue;
        this.props = props;
        this.status = status;
    }

    public record Failure(String message) {}

    @PostMapping("/api/render/jobs/claim")
    public ResponseEntity<RenderQueue.Job> claim(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return queue.claim()
                .map(job -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(job))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/api/render/jobs/{id}/video")
    public ResponseEntity<Map<String, String>> video(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                                     @PathVariable long id, @RequestParam String claim,
                                                     @RequestHeader(name = "X-Duration-Ms", required = false) Integer durationMs,
                                                     @RequestHeader(name = "X-Look", required = false) String look,
                                                     HttpServletRequest request) throws IOException {
        authorize(token);
        long max = props.maxVideo().toBytes();
        if (request.getContentLengthLong() > max) throw tooLarge(max);
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, max + 1));
        }
        if (body.length > max) throw tooLarge(max);
        if (!isMp4(body)) throw ApiException.badRequest("NOT_MP4", "MP4 영상만 올릴 수 있어요.");
        queue.complete(id, claim, body, durationMs == null || durationMs <= 0 ? null : durationMs, look);
        return ResponseEntity.ok(Map.of("status", "READY"));
    }

    @PostMapping("/api/render/jobs/{id}/fail")
    public ResponseEntity<Void> fail(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                     @PathVariable long id, @RequestParam String claim, @RequestBody(required = false) Failure body) {
        authorize(token);
        queue.fail(id, claim, body == null ? null : body.message());
        return ResponseEntity.noContent().build();
    }

    /** 지금 토큰이나, 바꾸는 중이면 옛 토큰(#241)이 맞아야 한다. 둘 다 시간이 같게 비교한다 */
    private void authorize(String token) {
        if (!props.workerEnabled()) throw new NotFoundException();
        byte[] given = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        boolean current = MessageDigest.isEqual(given, props.workerToken().getBytes(StandardCharsets.UTF_8));
        String old = props.previousWorkerToken();
        boolean previous = old != null && !old.isBlank() && MessageDigest.isEqual(given, old.getBytes(StandardCharsets.UTF_8));
        if (!current && !previous) throw new ApiException(HttpStatus.UNAUTHORIZED, "RENDER_TOKEN", "렌더 워커 토큰이 맞지 않아요.");
        status.seen(!current);
    }

    /** ISO BMFF: 4~8바이트가 "ftyp" */
    static boolean isMp4(byte[] b) {
        return b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
    }

    private static ApiException tooLarge(long max) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "VIDEO_TOO_LARGE", "영상은 " + (max / 1024 / 1024) + "MB까지 올릴 수 있어요.");
    }
}
