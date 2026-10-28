package com.team.blog.shorts.autoupload;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/**
 * 쇼츠 자동 올리기 API (spec 108). 로그인한 본인 것만, 쓸 수 없으면 모두 404다.
 * <ul>
 *   <li>GET /api/me/shorts/auto-upload: 플랫폼별 연결·시험·켜짐</li>
 *   <li>POST /api/me/shorts/auto-upload/{platform}/connect: 동의 화면 주소 (state는 세션에)</li>
 *   <li>GET /api/me/shorts/auto-upload/{platform}/callback: 플랫폼이 돌려보내는 곳. 끝나면 /studio?auto=1로 보낸다</li>
 *   <li>DELETE /api/me/shorts/auto-upload/{platform}: 연결 끊기</li>
 *   <li>POST /api/me/shorts/auto-upload/{platform}/test {shortsId}: 시험 올리기</li>
 *   <li>PUT /api/me/shorts/auto-upload/{platform}/enabled {enabled}: 켜고 끄기 (시험을 통과해야 켠다)</li>
 *   <li>GET /api/me/shorts/{id}/auto-uploads, POST …/auto-uploads/{platform}/retry: 쇼츠별 올리기 상태와 다시 하기</li>
 * </ul>
 */
@RestController
public class AutoUploadController {
    static final String STATE_ATTR = "shorts.autoUpload.state.";
    private final AutoUploads uploads;

    public AutoUploadController(AutoUploads uploads) {
        this.uploads = uploads;
    }

    @GetMapping("/api/me/shorts/auto-upload")
    public ResponseEntity<AutoUploads.Status> status(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(uploads.status(me));
    }

    @PostMapping("/api/me/shorts/auto-upload/{platform}/connect")
    public Map<String, String> connect(@CurrentMember MemberPrincipal me, @PathVariable String platform, HttpSession session) {
        String url = uploads.startConnect(me, platform, s -> session.setAttribute(attr(platform), s));
        return Map.of("url", url);
    }

    @GetMapping("/api/me/shorts/auto-upload/{platform}/callback")
    public ResponseEntity<Void> callback(@CurrentMember MemberPrincipal me, @PathVariable String platform,
                                         @RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error, HttpSession session) {
        Object pending = session.getAttribute(attr(platform));
        // state는 한 번만 쓴다(성공·실패와 상관없이 지운다)
        session.removeAttribute(attr(platform));
        AutoUploads.ConnectResult result = uploads.finishConnect(me, platform, code, state, error,
                pending instanceof AutoUploads.PendingState p ? p : null);
        String to = "/studio?auto=1&platform=" + platform.toLowerCase(Locale.ROOT) + "&result=" + result.name().toLowerCase(Locale.ROOT);
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(to).toString())
                .cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/api/me/shorts/auto-upload/{platform}")
    public ResponseEntity<Void> disconnect(@CurrentMember MemberPrincipal me, @PathVariable String platform) {
        uploads.disconnect(me, platform);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/me/shorts/auto-upload/{platform}/test")
    public AutoUploads.TestResult test(@CurrentMember MemberPrincipal me, @PathVariable String platform, @RequestBody AutoUploads.TestBody body) {
        return uploads.test(me, platform, body);
    }

    @PutMapping("/api/me/shorts/auto-upload/{platform}/enabled")
    public AutoUploads.Status enabled(@CurrentMember MemberPrincipal me, @PathVariable String platform, @RequestBody AutoUploads.EnableBody body) {
        return uploads.setEnabled(me, platform, body);
    }

    @GetMapping("/api/me/shorts/{id}/auto-uploads")
    public ResponseEntity<List<AutoUploadJobs.JobView>> jobs(@CurrentMember MemberPrincipal me, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(uploads.jobs(me, id));
    }

    @PostMapping("/api/me/shorts/{id}/auto-uploads/{platform}/retry")
    public List<AutoUploadJobs.JobView> retry(@CurrentMember MemberPrincipal me, @PathVariable long id, @PathVariable String platform) {
        return uploads.retry(me, id, platform);
    }

    private static String attr(String platform) {
        return STATE_ATTR + platform.toLowerCase(Locale.ROOT);
    }
}
