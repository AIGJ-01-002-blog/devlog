package com.team.blog.mcp.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shorts.application.OriginalityCheck;
import com.team.blog.shorts.application.ShortsScript;
import com.team.blog.shorts.application.ShortsService;

/**
 * 쇼츠 MCP 도구 (099 FR-007). 사용자 쪽 AI가 내 글을 쇼츠 대본으로 저장하고 검사 결과를 돌려받는다.
 * 영상 만들기 도구는 두지 않는다: 회원이 스튜디오 화면에서 대본을 확인하고 직접 누른다.
 */
@Component
class ShortsTools {
    static final String SCENES_SCHEMA = """
            "scenes":{"type":"array","maxItems":10,"description":"장면 3~10개. 합쳐서 60초 안(내레이션 약 330자 안)","items":{"type":"object","properties":{
                "caption":{"type":"string","description":"화면 큰 자막 (40자까지, 한 줄 요점)"},
                "narration":{"type":"string","description":"읽을 문장 (200자까지, 말하듯이)"},
                "code":{"type":"string","description":"화면에 보일 짧은 코드 (선택, 300자까지)"}},
              "required":["caption","narration"]}}""";

    static final String GUIDE = "양산형으로 보이지 않게: 원본 글 문장을 그대로 읽지 말고 말로 풀어 쓴다. 첫 장면은 시청자가 겪는 문제나 질문으로 시작한다. "
            + "사용자가 직접 겪은 일과 결과(숫자)를 한 장면 이상 넣는다. 장면마다 새 정보(원인·시도·결과·배운 점)를 하나씩 넣고 같은 말로 시작하지 않는다. "
            + "글에 없는 경험이나 수치를 지어내지 않는다.";

    private final ShortsService shorts;
    private final String baseUrl;

    ShortsTools(ShortsService shorts, BlogProperties props) {
        this.shorts = shorts;
        this.baseUrl = props.site().baseUrl();
    }

    boolean enabled() {
        return shorts.enabled();
    }

    McpTools.Result create(AccessTokens.Caller caller, JsonNode a) {
        Long postId = a.path("post_id").canConvertToLong() ? a.path("post_id").asLong() : null;
        try {
            ShortsService.Item item = shorts.create(caller.memberId(), new ShortsService.Draft(postId, text(a, "title"), scenes(a)));
            return McpTools.Result.ok("쇼츠 대본을 저장했어요 (쇼츠 번호 " + item.id() + ", 예상 " + Math.round(item.estimatedSeconds()) + "초).\n"
                    + report(shorts.check(caller.memberId(), item.id()))
                    + "\n영상은 사용자가 이 화면에서 대본을 확인하고 [영상 만들기]를 눌러야 만들어져요: " + studioUrl(item.id()));
        } catch (ApiException e) {
            return McpTools.Result.fail(message(e));
        }
    }

    McpTools.Result update(AccessTokens.Caller caller, JsonNode a) {
        JsonNode idNode = a.path("shorts_id");
        if (!idNode.canConvertToLong()) return McpTools.Result.fail("쇼츠 번호(shorts_id)가 필요해요.");
        long id = idNode.asLong();
        String title = a.has("title") ? text(a, "title") : null;
        List<ShortsScript.Scene> scenes = a.has("scenes") ? scenes(a) : null;
        if (title == null && scenes == null) return McpTools.Result.fail("바꿀 제목(title)이나 장면(scenes)을 주세요.");
        try {
            ShortsService.Item item = shorts.update(caller.memberId(), id, new ShortsService.Edit(title, scenes, null));
            return McpTools.Result.ok("쇼츠 " + id + "번 대본을 고쳤어요 (예상 " + Math.round(item.estimatedSeconds()) + "초). 만들어 둔 영상이 있었다면 지워졌어요.\n"
                    + report(shorts.check(caller.memberId(), id)) + "\n확인 화면: " + studioUrl(id));
        } catch (ApiException e) {
            return McpTools.Result.fail(message(e));
        }
    }

    McpTools.Result list(AccessTokens.Caller caller) {
        ShortsService.Overview o = shorts.overview(caller.memberId());
        if (o.items().isEmpty()) return McpTools.Result.ok("아직 쇼츠가 없어요. create_shorts_script로 내 글에서 대본을 만들 수 있어요.");
        StringBuilder sb = new StringBuilder("내 쇼츠 (최근 고친 순):\n");
        for (ShortsService.Item i : o.items()) {
            sb.append("- ").append(i.id()).append(" · ").append(i.title()).append(" · ").append(label(i.status()))
                    .append(" · 장면 ").append(i.scenes().size()).append("개 · 예상 ").append(Math.round(i.estimatedSeconds())).append("초");
            if (i.postId() != null) sb.append(" · 원본 글 ").append(i.postId());
            if (i.error() != null && i.status() != ShortsService.Status.READY) sb.append(" · 최근 오류: ").append(i.error());
            sb.append('\n');
        }
        sb.append("오늘 영상 만들기 ").append(o.rendersToday()).append('/').append(o.dailyRenders()).append("번. 스튜디오: ").append(baseUrl).append("/studio");
        return McpTools.Result.ok(sb.toString());
    }

    static String report(OriginalityCheck.Report r) {
        StringBuilder sb = new StringBuilder("양산형 방지 검사: " + r.score() + "점, " + (r.passed() ? "영상으로 만들 수 있어요." : "이대로는 영상으로 만들 수 없어요."));
        for (OriginalityCheck.Issue i : r.issues()) {
            sb.append("\n- ").append(i.level() == OriginalityCheck.Level.BLOCK ? "[막힘] " : "[경고] ").append(i.message()).append(" → ").append(i.hint());
        }
        if (!r.issues().isEmpty()) sb.append("\nupdate_shorts_script로 고친 뒤 다시 확인할 수 있어요.");
        return sb.toString();
    }

    private static String label(ShortsService.Status s) {
        return switch (s) {
            case DRAFT -> "대본";
            case QUEUED -> "만들기 대기";
            case RENDERING -> "만드는 중";
            case READY -> "완성";
            case FAILED -> "실패";
        };
    }

    private String studioUrl(long id) {
        return baseUrl + "/studio?id=" + id;
    }

    private static List<ShortsScript.Scene> scenes(JsonNode a) {
        List<ShortsScript.Scene> out = new ArrayList<>();
        for (JsonNode n : a.path("scenes")) out.add(new ShortsScript.Scene(text(n, "caption"), text(n, "narration"), n.has("code") ? text(n, "code") : null));
        return out;
    }

    private static String text(JsonNode a, String field) {
        JsonNode n = a.path(field);
        return n.isString() ? n.asString() : "";
    }

    private static String message(ApiException e) {
        if (e.status().value() == 404) return "쇼츠를 찾을 수 없어요 (없는 번호이거나 내 쇼츠가 아니에요).";
        if (!e.errors().isEmpty()) return e.errors().stream().map(f -> f.message()).distinct().reduce((x, y) -> x + " " + y).orElse(e.getMessage());
        if ("VERSION_CONFLICT".equals(e.code())) return "그 사이 사용자가 대본을 고쳤어요. list_shorts로 다시 확인해 주세요.";
        return e.getMessage();
    }
}
