package com.team.blog.mcp.application;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shorts.application.OriginalityCheck;
import com.team.blog.shorts.application.ShortsScript;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.shorts.application.TradeReplay;
import com.team.blog.trade.application.TradeReplays;

/**
 * 쇼츠 MCP 도구 (099 FR-007). 사용자 쪽 AI가 내 글을 쇼츠 대본으로 저장하고 검사 결과를 돌려받는다.
 * 영상 만들기 도구는 두지 않는다: 회원이 스튜디오 화면에서 대본을 확인하고 직접 누른다.
 */
@Component
class ShortsTools {
    private static final Logger log = LoggerFactory.getLogger(ShortsTools.class);
    static final String SCENES_SCHEMA = """
            "scenes":{"type":"array","maxItems":10,"description":"장면 3~10개. 합쳐서 60초 안(내레이션 약 330자 안)","items":{"type":"object","properties":{
                "caption":{"type":"string","description":"화면 큰 자막 (40자까지, 한 줄 요점)"},
                "narration":{"type":"string","description":"읽을 문장 (200자까지, 말하듯이)"},
                "code":{"type":"string","description":"화면에 보일 짧은 코드 (선택, 300자까지)"}},
              "required":["caption","narration"]}}""";

    static final String GUIDE = "양산형으로 보이지 않게: 원본 글 문장을 그대로 읽지 말고 말로 풀어 쓴다. 첫 장면은 시청자가 겪는 문제나 질문으로 시작한다. "
            + "사용자가 직접 겪은 일과 결과(숫자)를 한 장면 이상 넣는다. 장면마다 새 정보(원인·시도·결과·배운 점)를 하나씩 넣고 같은 말로 시작하지 않는다. "
            + "글에 없는 경험이나 수치를 지어내지 않는다.";

    /** 매매 재현 대본(#265) 다시 쓰기 안내. 장면 순서가 영상의 차트 진행과 맞물린다 */
    static final String REPLAY_GUIDE = "매매 재현 영상이에요. 차트는 장면 순서대로 흘러가요: 1번 장면에서 체결 전 흐름을 보여 주고, 2번 장면부터 체결 하나에 장면 하나씩 "
            + "그 체결 시각에 매수·매도 표시가 찍혀요. 체결 수만큼 체결 장면을 두고, 그 뒤에 결과·배운 점 장면을 두세요. "
            + "그 시각에 매매하는 사람처럼 \"지금 사는 이유\"를 현재형으로 말하되, 체결 이유와 사용자 메모에 있는 것만 쓰고 지어내지 마세요. "
            + "고지는 따로 장면을 두지 말고 마지막 장면 끝에 '투자 권유는 아니에요.' 한마디만 붙이세요(화면에는 영상이 모드와 고지를 작게 적어요). 추천·확실한 수익 같은 표현은 쓰지 마세요.";

    static final int CANDIDATES_DEFAULT = 5;
    static final int CANDIDATES_MAX = 10;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ShortsService shorts;
    private final ShortsCandidates candidates;
    private final TradeReplays replays;
    private final String baseUrl;

    ShortsTools(ShortsService shorts, ShortsCandidates candidates, TradeReplays replays, BlogProperties props) {
        this.shorts = shorts;
        this.candidates = candidates;
        this.replays = replays;
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
            if (i.replay() != null) sb.append(" · 매매 재현(체결 ").append(i.replay().fills().size()).append("개)");
            if (i.error() != null && i.status() != ShortsService.Status.READY) sb.append(" · 최근 오류: ").append(i.error());
            sb.append('\n');
        }
        sb.append("오늘 영상 만들기 ").append(o.rendersToday()).append('/').append(o.dailyRenders()).append("번. 스튜디오: ").append(baseUrl).append("/studio");
        return McpTools.Result.ok(sb.toString());
    }

    /** 쇼츠 하나의 대본 전체. 매매 재현 영상(#265)이면 고른 체결·차트 구간·그날 메모와 다시 쓰기 안내를 붙인다 */
    McpTools.Result get(AccessTokens.Caller caller, JsonNode a) {
        JsonNode idNode = a.path("shorts_id");
        if (!idNode.canConvertToLong()) return McpTools.Result.fail("쇼츠 번호(shorts_id)가 필요해요.");
        ShortsService.Item i;
        try {
            i = shorts.get(caller.memberId(), idNode.asLong());
        } catch (ApiException e) {
            return McpTools.Result.fail(message(e));
        }
        StringBuilder sb = new StringBuilder("쇼츠 ").append(i.id()).append("번 · ").append(i.title()).append(" · ").append(label(i.status()))
                .append(" · 예상 ").append(Math.round(i.estimatedSeconds())).append("초 · 고칠 때 쓸 버전 ").append(i.version()).append('\n');
        if (i.postId() != null) sb.append("원본 글 ").append(i.postId()).append(": ").append(i.postTitle()).append('\n');
        TradeReplay r = i.replay();
        if (r != null) {
            TradeReplay.Interval interval = TradeReplay.Interval.parse(r.interval()).orElse(TradeReplay.Interval.M15);
            sb.append("\n매매 재현: ").append(TradeReplays.exchangeName(r.exchange())).append(' ')
                    .append(r.symbolName().isBlank() ? r.symbol() : r.symbolName() + "(" + r.symbol() + ")").append(" · ").append(interval.label)
                    .append(" · ").append("LIVE".equals(r.mode()) ? "실계좌" : "모의투자").append(" · 차트 ").append(DAY_TIME.format(r.from()))
                    .append(" ~ ").append(DAY_TIME.format(r.to())).append(" (한국 시간)\n고른 체결 (").append(r.fills().size()).append("개, 시간 순):\n");
            for (String line : TradeReplays.fillLines(r)) sb.append("- ").append(line).append('\n');
            replays.memos(caller.memberId(), r).forEach((day, memo) ->
                    sb.append("사용자 메모 ").append(day).append(": ").append(memo.isBlank() ? "(없음)" : memo).append('\n'));
        }
        sb.append("\n지금 대본:\n");
        for (int k = 0; k < i.scenes().size(); k++) {
            ShortsScript.Scene s = i.scenes().get(k);
            sb.append(k + 1).append(". [").append(s.caption()).append("] ").append(s.narration()).append('\n');
        }
        if (r != null) sb.append('\n').append(REPLAY_GUIDE).append('\n');
        sb.append("고치려면 update_shorts_script(shorts_id ").append(i.id()).append(")를 쓰세요. 영상은 사용자가 스튜디오에서 만들어요: ").append(studioUrl(i.id()));
        return McpTools.Result.ok(sb.toString());
    }

    /** 쇼츠 후보 목록 (#240). 아직 쇼츠가 없는 공개 글 가운데 문제·숫자·코드가 둘 이상 있는 글 */
    McpTools.Result candidates(AccessTokens.Caller caller, JsonNode a) {
        int limit = a.path("limit").canConvertToInt() ? Math.clamp(a.path("limit").asInt(), 1, CANDIDATES_MAX) : CANDIDATES_DEFAULT;
        List<ShortsCandidates.Candidate> list = candidates.of(caller.memberId(), limit);
        if (list.isEmpty()) {
            return McpTools.Result.ok("지금은 쇼츠로 만들 만한 글이 없어요. 아직 쇼츠가 없는 공개 글 가운데 직접 겪은 문제·숫자로 된 결과·코드 중 "
                    + "두 가지 이상이 들어간 글을 찾아요. 그런 글을 쓰면 다시 확인해 주세요.");
        }
        StringBuilder sb = new StringBuilder("쇼츠 후보 (아직 쇼츠가 없는 공개 글, 좋은 점이 많은 순):\n");
        for (ShortsCandidates.Candidate c : list) {
            sb.append("- 글 ").append(c.postId()).append(" · ").append(c.title()).append(" · ").append(DAY.format(c.publishedAt())).append(" 발행")
                    .append(" · 최근 30일 조회 ").append(c.views()).append(" · 좋은 점: ").append(String.join(", ", c.reasons())).append('\n');
        }
        sb.append("사용자에게 목록을 보여 주고 고르게 해 주세요. 고르면 get_post로 글을 읽고 create_shorts_script(post_id)로 대본을 써요. ")
                .append("영상은 사용자가 스튜디오에서 직접 만들어요.");
        return McpTools.Result.ok(sb.toString());
    }

    /** 임시글을 만든 뒤 덧붙일 안내. 쇼츠를 켰고 글이 후보면 발행한 뒤에 물어보라고 알린다 */
    String afterDraft(String content) {
        if (!enabled()) return "";
        ShortsCandidates.Fit f = ShortsCandidates.fit(content);
        if (!f.fit()) return "";
        return "\n쇼츠 후보: 이 글에는 " + String.join(", ", f.reasons()) + "이(가) 있어 60초 쇼츠로 만들 만해요. "
                + "지금 묻지 말고, 사용자가 글을 공개로 발행한 뒤에 쇼츠 대본을 만들지 한 번만 물어봐 주세요.";
    }

    /** 공개로 발행한 뒤 덧붙일 안내. 이미 이 글로 만든 쇼츠가 있으면 묻지 않는다 */
    String afterPublish(long postId, String content, boolean isPublic) {
        if (!enabled() || !isPublic) return "";
        try {
            if (candidates.hasShorts(postId)) return "";
        } catch (RuntimeException e) {
            // 발행은 이미 끝났으니 안내를 빼고 발행 결과만 돌려준다
            log.warn("글 {}의 쇼츠 후보 안내를 만들지 못했습니다: {}", postId, e.getMessage());
            return "";
        }
        ShortsCandidates.Fit f = ShortsCandidates.fit(content);
        if (!f.fit()) return "";
        return "\n쇼츠 후보: 이 글에는 " + String.join(", ", f.reasons()) + "이(가) 있어 60초 쇼츠로 만들 만해요. "
                + "사용자에게 쇼츠 대본을 만들지 한 번 물어보고, 원하면 create_shorts_script에 post_id " + postId + "를 주어 만들어 주세요. "
                + "사용자가 넘기면 다시 묻지 마세요.";
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
