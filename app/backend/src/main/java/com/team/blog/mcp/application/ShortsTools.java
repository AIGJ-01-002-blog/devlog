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
import com.team.blog.shorts.application.ShortsBatches;
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
            "scenes":{"type":"array","maxItems":16,"description":"장면 5~7개로 30~45초(내레이션 약 180~270자)가 좋고 3~16개까지. 90초를 넘으면 영상으로 못 만들어요","items":{"type":"object","properties":{
                "caption":{"type":"string","description":"화면 큰 자막 (40자까지, 한 줄 요점)"},
                "narration":{"type":"string","description":"읽을 문장 (200자까지, 말하듯이)"},
                "code":{"type":"string","description":"화면에 보일 짧은 코드 (선택, 300자까지)"},
                "image":{"type":"string","description":"장면 배경으로 깔 그림 (선택). get_post 본문에 있는 devlog 그림 주소나 /로 시작하는 사이트 경로만. 그림이 없으면 글 대표 사진이 첫 장면에 들어가요"},
                "visual":{"type":"string","enum":["explain","shock","typing","panic","restore","wait","fast","idea","protect","think","celebrate"],
                  "description":"장면 연출. explain 설명, shock 데이터가 사라짐, typing 모니터에 코드(문제 줄 빨강·고친 줄 초록), panic 요청 폭주, restore 백업으로 복구, wait 느린 로딩, fast 전후 막대와 N배 빨라짐, idea 팁, protect 막아 냄, think 원인 질문, celebrate 결과 축하. 숫자는 내레이션에서 읽으니 '4초에서 100밀리초'처럼 내레이션에 숫자를 넣는다"},
                "effects":{"type":"array","maxItems":3,"description":"장면 안 효과음 (선택, 꼭 필요한 곳에만)","items":{"type":"object","properties":{
                  "sound":{"type":"string","enum":["pop","ding","whoosh","boing","tick","typing"]},
                  "at":{"type":"number","description":"장면이 시작하고 몇 초 뒤"},
                  "volume":{"type":"number","description":"0~1 (선택)"}},"required":["sound","at"]}},
                "diagram":{"type":"object","description":"CS 개념을 풀어 줄 설명 도식 (선택). type은 flow(왼쪽→오른쪽 흐름, 칸 4개까지)·stack(위→아래 층, 5개까지, highlight는 0부터 센 강조 층)·steps(순서, 5개까지)·compare(left·right에 {title, items} 4개까지)·timeline(시간 순 사건, 5개까지, note에 날짜·버전)·define(term에 정의할 말, items에 뜻·특징 3개까지)·number(label·from·to·unit: 이름표를 말할 때 숫자가 from에서 to로 바뀜, 글에 있는 실제 숫자만). 같은 영상 안에서 같은 type을 이어 쓰지 말고 장면마다 내용에 맞게 바꾼다. 칸은 문자열이나 {label, icon, note}, 이름표는 20자까지. 칸은 내레이션이 이름표 첫 낱말을 말할 때 나타나니 내레이션에 이름표 말을 넣는다. 예 {'type':'flow','items':['브라우저','서버',{'label':'DB','icon':'database','note':'디스크'}]}, {'type':'number','label':'응답 시간','from':1200,'to':35,'unit':'ms'}"}},
              "required":["caption","narration"]}}""";

    static final String CHARACTER = "character";
    /** 대본 단위 캐릭터 고르기(#304) */
    static final String CHARACTER_SCHEMA =
            "\"character\":{\"type\":\"string\",\"enum\":[\"lognyang\",\"logduck\",\"dubu\",\"cat\"],\"description\":\"연기할 캐릭터 (선택). lognyang 후드 입은 고양이 로그냥(기본), logduck 스카프 맨 오리 로그덕, dubu 흰 강아지 두부, cat Noto 이모지 고양이. 사용자가 고른 캐릭터가 있을 때만 준다\"},";

    static final String PLAYLIST = "playlist";
    /** 묶음별 재생목록(2026-10-21 블로그 주인 결정). 이것만 주면 대본과 만든 영상은 그대로 둔다 */
    static final String PLAYLIST_SCHEMA =
            "\"playlist\":{\"type\":\"string\",\"enum\":[\"DEPLOY\",\"DB\",\"SECURITY\",\"PERFORMANCE\",\"AI\",\"\"],\"description\":\"넣을 재생목록 묶음 (선택). DEPLOY 배포·운영, DB 데이터·트랜잭션, SECURITY 보안·인증, PERFORMANCE 성능·장애 전파, AI AI협업. 빈 문자열이면 지운다. 이것만 주면 대본과 만든 영상은 그대로 둔다\"},";

    /**
     * 대본 쓰기 안내 (#277). 길이는 30~45초(#431, 2026-10-20 블로그 주인 결정. 전에는 1분~1분 30초).
     * 제목은 "[백엔드 면접] 질문형"(2026-10-21 블로그 주인 결정 "하루 1개, 7주"). 그래서 제목만은 "~하는 이유"로 끝나도 된다. 구조는 블로그 주인이 정한 "잘 되는 쇼츠" 틀(후킹 → 본론 → 반전·정리 → CTA)을
     * 이 길이에 맞게 줄인 것이고, "AI 티" 줄이는 법(내 말투, 후킹 고르기, 상투어 빼기, 실제 경험·숫자)을 함께 둔다.
     * 후킹 종류와 본론 틀은 여러 가지 가운데 최근 영상과 다른 것을 고르게 한다(쇼츠 다양화 2026-10-17: 반복적인 영상은 수익화가 안 된다).
     */
    static final String GUIDE = "길이는 30~45초(장면 5~7개)이고, 한 주제에서 질문 하나에만 답한다. "
            + "두 번째 장면에서 후킹이 던진 질문의 답(결과·원인)을 먼저 말하고, 설명은 그 뒤에 붙인다"
            + "(시청 지속 그래프에서 두 번째 장면이 시작하는 4~7초에 절반이 넘긴다). 구조: "
            + "① 후킹(첫 장면, 3초 안): 후킹 종류 가운데 하나로 스크롤을 멈추게 한다. 질문(\"왜 ~일까요?\"), 결과 먼저(결론·결과 숫자부터), "
            + "반박(\"다들 ~라는데 아니에요\"), 숫자 한 방(글에 있는 놀라운 숫자), 경고(\"이거 모르면 ~ 날려요\"), 고백(\"제가 ~를 날렸어요\", 글에 그런 일이 있을 때만). "
            + "첫 문장은 증상부터 보여 준다: 글에 나온 진짜 에러 문자열·상태 코드·숫자를 그대로 넣는다(예: \"401인데 토큰은 멀쩡해요\"). "
            + "최근 5편 가운데 질문 후킹(\"왜 ~할까요?\")이 2편 이상이면 이번에는 질문 말고 다른 후킹을 쓴다(질문만 이어지면 피드에서 다 같은 영상으로 보인다). "
            + "첫 장면 요점 자막(caption)은 영상의 커버·썸네일이 된다: 15자 안팎으로, 글 속의 구체적인 대상(에러 이름·글자 하나·숫자·도구 이름)을 넣고 "
            + "\"~하는 이유\", \"~하는 법\"처럼 교과서 말로 끝내지 않는다(예: \"s 한 글자에 로그인이 막혔다\", \"ERROR 로그가 딱 1줄?\"). "
            + "첫 장면 visual은 후킹에 맞춘다: 질문이면 think, 결과 먼저면 celebrate나 idea, 숫자 한 방은 글에 전후 숫자가 둘 다 있을 때만 fast(전후 막대)이고 숫자가 하나뿐이면 shock, 경고면 panic, 반박이면 shock. "
            + "제목(title)은 백엔드 면접에서 나올 법한 질문형으로, 구체적인 대상(에러 이름·어노테이션·숫자·도구 이름)을 앞에 두고 40자 안으로 쓴다"
            + "(예: \"@Transactional 붙였는데 롤백이 안 되는 이유\", \"로그인 토큰, 로컬 스토리지에 두면 왜 위험할까\"). "
            + "앞머리 [백엔드 면접]은 올리기 안내가 제목 앞에 붙이니 제목에 넣지 않는다. "
            + "대본을 저장한 뒤 update_shorts_script의 playlist로 재생목록 묶음(배포·DB·보안·성능·AI협업) 가운데 하나를 고른다. "
            + "② 본론(약 20~30초): 핵심은 1~2개만, 짧은 문장으로 빠르게. 내레이션은 한 문장에 생각 하나, 25자 안팎으로 끊고 \"자, 그럼\" 같은 이음말은 뺀다. 본론 틀은 글에 맞는 것 하나를 고른다: "
            + "문제→원인→해결, 오해→진실, 전후 비교, 숫자 반전(예상한 숫자와 실제 숫자), 단계별 따라하기, 실패담(시도→실패→깨달음). "
            + "원리 도식은 1개까지만 쓰고, 요청·데이터가 오가는 원리(로그인 리다이렉트, 소켓 연결 등)는 글 설명 대신 흐름 도식으로 보여 준다. "
            + "두 번째 장면의 답에는 처음 듣는 사람도 따라오게 일상 비유를 하나 붙인다(예: 소켓은 실 전화기). "
            + "실무자가 피식 웃을 공감 한 줄(\"로컬에선 됐는데\", \"금요일 6시 배포\")을 한 번 넣되, 글에 있는 상황일 때만 쓴다. "
            + "③ 반전·정리(끝에서 두 번째 장면): \"근데 진짜 중요한 건…\"처럼 한 번 더 붙잡는 반전이나 한 줄 요약. "
            + "④ CTA(마지막 장면): 행동 하나만 자연스럽게 권한다. 최근 쇼츠와 번갈아 \"전체 코드는 블로그 글에서\"와 \"이 에러 겪는 동료한테 보내 주세요\"(공유는 인스타그램이 비팔로워에게 퍼뜨리는 신호) 가운데 하나를 쓴다. "
            + "여러 행동을 한꺼번에 권하지 않는다. 마지막 내레이션은 첫 장면 문장으로 자연스럽게 이어지게 끝내 다시 보기(루프)를 부른다(예: 끝 \"그래서 결국 다시,\" → 처음 \"401인데 토큰은 멀쩡해요\"). "
            + "내용이 거의 같은 틀의 영상이 이어지면 유튜브가 반복적인 영상으로 보고 수익화에서 뺄 수 있다. 쓰기 전에 list_shorts로 최근 5편의 첫 장면을 보고, "
            + "필요하면 get_shorts로 대본을 읽어서 그 영상들과 다른 후킹 종류와 본론 틀을 고른다. "
            + "AI 티가 나지 않게: 대본을 쓰기 전에 사용자 글(get_post)에서 말투를 읽고 그 말투로 쓴다. "
            + "첫 장면 후킹 문장은 서로 다른 후킹 종류로 후보 5개를 먼저 사용자에게 보여 주고 고르게 한 뒤 저장한다. "
            + "\"여러분\", \"오늘은 ~에 대해 알아볼게요\", \"정말 중요한\", \"꿀팁 대방출\" 같은 뻔한 표현과 상투어는 쓰지 않는다. "
            + "사용자가 직접 겪은 일과 결과 숫자를 한 장면 이상 넣고, 글에 없는 경험이나 수치를 지어내지 않는다. "
            + "글에 전후가 있는 실제 숫자(시간·용량·비용·오류 수 등)가 있으면 number 도식으로 바뀌는 모습을 보여 준다. 글에 없는 숫자로는 만들지 않는다. "
            + "원본 글 문장을 그대로 읽지 말고 말로 풀어 쓰며, 장면마다 새 정보를 하나씩 넣고 같은 말로 시작하지 않는다. "
            + "장면마다 내용에 맞는 visual(연출)을 고르고, 효과음(effects)은 강조할 순간에만 넣는다. "
            + "CS 개념을 설명하는 장면은 diagram으로 도식(흐름·층·순서·비교·연표·정의·숫자 변화)을 주어 그림으로 쉽게 푼다.";

    /** 매매 재현 대본(#265) 다시 쓰기 안내. 장면 순서가 영상의 차트 진행과 맞물린다 */
    static final String REPLAY_GUIDE = "매매 재현 영상이에요. 1번 장면은 짧은 제목 카드(요점 자막이 제목이 돼요) 뒤로 차트가 흐르기 시작하고, 2번 장면부터 체결 하나에 장면 하나씩 "
            + "그 체결 시각에 매수·매도 표시가 찍혀요. 체결 수만큼 체결 장면을 두고, 그 뒤에 결과(결산 화면)·배운 점 장면을 두세요. "
            + "그 시각에 매매하는 사람처럼 \"지금 사는 이유\"를 현재형으로 말하되, 체결 이유와 사용자 메모에 있는 것만 쓰고 지어내지 마세요. "
            + "투자 권유 고지는 대본에 넣지 마세요. 영상 맨 끝에 짧은 고지 카드 한 장이 자동으로 붙어요. 추천·확실한 수익 같은 표현은 쓰지 마세요.";

    private static final String POST_ID = "post_id";
    private static final String TITLE = "title";

    static final int CANDIDATES_DEFAULT = 5;
    static final int CANDIDATES_MAX = 10;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Seoul"));
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    static final String TOPICS_SCHEMA = """
            "topics":{"type":"array","minItems":2,"maxItems":5,"description":"작은 주제 2~5개. 한 편에 한 주제, 편끼리 겹치지 않게","items":{"type":"object","properties":{
                "title":{"type":"string","description":"이 편의 제목 (60자까지)"},
                "hook":{"type":"string","description":"첫 장면에서 던질 질문이나 문제 한 줄 (120자까지)"},
                "scope":{"type":"string","description":"이 편에서만 다룰 것: 원인·시도·결과 가운데 무엇을 (300자까지)"}},
              "required":["title","hook"]}}""";

    static final String PLAN_GUIDE = "근거 하나를 작은 주제로 나눌 때: 한 편은 질문 하나에 답하고 끝낸다(문제 → 원인 → 내가 한 것 → 결과 숫자). "
            + "같은 설명을 두 편에 넣지 않고, 편마다 후킹 종류(질문·결과 먼저·반박·숫자·경고)를 다르게 잡는다. 근거에 없는 경험·수치는 주제로 만들지 않는다. 근거가 작으면 2편, 크면 5편까지.";

    private final ShortsService shorts;
    private final ShortsBatches batches;
    private final ShortsCandidates candidates;
    private final TradeReplays replays;
    private final String baseUrl;

    ShortsTools(ShortsService shorts, ShortsBatches batches, ShortsCandidates candidates, TradeReplays replays, BlogProperties props) {
        this.shorts = shorts;
        this.batches = batches;
        this.candidates = candidates;
        this.replays = replays;
        this.baseUrl = props.site().baseUrl();
    }

    boolean enabled() {
        return shorts.enabled();
    }

    /** 근거(글·맥락)를 작은 주제로 나눈 묶음을 만든다 (#276). 대본은 주제마다 create_shorts_script로 쓴다 */
    McpTools.Result plan(AccessTokens.Caller caller, JsonNode a) {
        Long postId = a.path(POST_ID).canConvertToLong() ? a.path(POST_ID).asLong() : null;
        List<ShortsBatches.Topic> topics = new ArrayList<>();
        for (JsonNode n : a.path("topics")) topics.add(new ShortsBatches.Topic(text(n, TITLE), text(n, "hook"), text(n, "scope")));
        try {
            ShortsBatches.Batch b = batches.plan(caller.memberId(), new ShortsBatches.Plan(postId, text(a, "source_text"), text(a, TITLE), topics));
            StringBuilder sb = new StringBuilder("쇼츠 묶음을 만들었어요 (묶음 번호 " + b.id() + ", 주제 " + b.topics().size() + "개).\n");
            for (ShortsBatches.TopicView t : b.topics()) {
                sb.append(t.number()).append(". ").append(t.title()).append(" · 첫 질문: ").append(t.hook());
                if (!t.scope().isEmpty()) sb.append(" · 범위: ").append(t.scope());
                sb.append('\n');
            }
            sb.append("이제 주제마다 create_shorts_script(batch_id ").append(b.id()).append(", topic 번호)로 대본을 한 편씩 써 주세요. ")
                    .append("첫 장면은 그 주제의 첫 질문으로 시작하고, 범위 밖 이야기는 다른 편에 맡겨요. ")
                    .append("영상은 사용자가 스튜디오에서 확인하고 묶음째 [영상 만들기]를 눌러요: ").append(baseUrl).append("/studio?batch=").append(b.id());
            return McpTools.Result.ok(sb.toString());
        } catch (ApiException e) {
            return McpTools.Result.fail(message(e));
        }
    }

    McpTools.Result create(AccessTokens.Caller caller, JsonNode a) {
        Long postId = a.path(POST_ID).canConvertToLong() ? a.path(POST_ID).asLong() : null;
        Long batchId = a.path("batch_id").canConvertToLong() ? a.path("batch_id").asLong() : null;
        Integer topic = a.path("topic").canConvertToInt() ? a.path("topic").asInt() : null;
        if (badCharacter(a)) return McpTools.Result.fail(CHARACTER_TYPE);
        try {
            ShortsService.Item item = batchId != null
                    ? batches.create(caller.memberId(), batchId, topic, text(a, TITLE), scenes(a), character(a))
                    : shorts.create(caller.memberId(), new ShortsService.Draft(postId, text(a, TITLE), scenes(a), null, null, text(a, "source_text"), character(a)));
            return McpTools.Result.ok("쇼츠 대본을 저장했어요 (쇼츠 번호 " + item.id() + ", 예상 " + Math.round(item.estimatedSeconds()) + "초).\n"
                    + lengthHint(item.estimatedSeconds()) + report(shorts.check(caller.memberId(), item.id()))
                    + "\n영상은 사용자가 이 화면에서 대본을 확인하고 [영상 만들기]를 눌러야 만들어져요: " + studioUrl(item.id()));
        } catch (ApiException e) {
            return McpTools.Result.fail(message(e));
        }
    }

    /**
     * 권장 길이(30~45초)를 벗어나면 AI가 맞추도록 알려 준다. 만들기는 막지 않는다.
     * 반올림한 초로 판단해 안내 문구의 숫자와 어긋나지 않게 한다.
     */
    static String lengthHint(double seconds) {
        long s = Math.round(seconds);
        String target = ShortsScript.SECONDS_TARGET_MIN + "~" + ShortsScript.SECONDS_TARGET_MAX + "초를 권해요";
        if (s < ShortsScript.SECONDS_TARGET_MIN) return "길이가 " + s + "초예요. " + target + ": 근거(숫자나 코드) 장면 하나를 더해 주세요.\n";
        if (s > ShortsScript.SECONDS_TARGET_MAX) return "길이가 " + s + "초예요. " + target + ": 설명 장면을 줄이고 질문 하나의 답에만 집중해 주세요.\n";
        return "";
    }

    McpTools.Result update(AccessTokens.Caller caller, JsonNode a) {
        JsonNode idNode = a.path("shorts_id");
        if (!idNode.canConvertToLong()) return McpTools.Result.fail("쇼츠 번호(shorts_id)가 필요해요.");
        long id = idNode.asLong();
        String title = a.has(TITLE) ? text(a, TITLE) : null;
        List<ShortsScript.Scene> scenes = a.has("scenes") ? scenes(a) : null;
        // null·숫자를 빈 값으로 읽으면 캐릭터를 지우고 만든 영상까지 지우므로 고치기 전에 막는다
        if (badCharacter(a)) return McpTools.Result.fail(CHARACTER_TYPE);
        String character = a.has(CHARACTER) ? text(a, CHARACTER) : null;
        if (a.has(PLAYLIST) && !a.get(PLAYLIST).isString()) return McpTools.Result.fail("재생목록(playlist)은 DEPLOY·DB·SECURITY·PERFORMANCE·AI 가운데 하나를 문자열로 주세요.");
        String playlist = a.has(PLAYLIST) ? text(a, PLAYLIST) : null;
        if (title == null && scenes == null && character == null && playlist == null) {
            return McpTools.Result.fail("바꿀 제목(title)·장면(scenes)·캐릭터(character)·재생목록(playlist)을 주세요.");
        }
        // 재생목록 값은 대본을 고치기 전에 확인한다. 대본 검사가 막히면 재생목록도 바꾸지 않게 대본을 먼저 저장한다
        if (playlist != null && !playlist.isBlank() && ShortsService.Playlist.parse(playlist).isEmpty()) {
            return McpTools.Result.fail("재생목록(playlist)은 DEPLOY·DB·SECURITY·PERFORMANCE·AI 가운데 하나를 주세요.");
        }
        try {
            if (title == null && scenes == null && character == null) {
                ShortsService.Item item = shorts.setPlaylist(caller.memberId(), id, new ShortsService.PlaylistBody(playlist));
                return McpTools.Result.ok("쇼츠 " + id + "번 재생목록을 " + (item.playlist() == null ? "비웠어요" : "'" + item.playlist().label + "'(으)로 정했어요")
                        + ". 대본과 만든 영상은 그대로예요.");
            }
            ShortsService.Item item = shorts.update(caller.memberId(), id, new ShortsService.Edit(title, scenes, null, character));
            if (playlist != null) shorts.setPlaylist(caller.memberId(), id, new ShortsService.PlaylistBody(playlist));
            return McpTools.Result.ok("쇼츠 " + id + "번 대본을 고쳤어요 (예상 " + Math.round(item.estimatedSeconds()) + "초). 만들어 둔 영상이 있었다면 지워졌어요.\n"
                    + lengthHint(item.estimatedSeconds()) + report(shorts.check(caller.memberId(), id)) + "\n확인 화면: " + studioUrl(id));
        } catch (ApiException e) {
            return McpTools.Result.fail(message(e));
        }
    }

    /** 쇼츠 목록에 보일 첫 장면(후킹) 글자 수. 다음 대본이 다른 후킹을 고를 수 있게 */
    static final int HOOK_PREVIEW = 60;

    static String clip(String text, int max) {
        if (text == null) return "";
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
            if (!i.scenes().isEmpty()) sb.append(" · 첫 장면: ").append(clip(i.scenes().get(0).narration(), HOOK_PREVIEW));
            if (i.batchId() != null) sb.append(" · 묶음 ").append(i.batchId()).append("의 ").append(i.topic()).append("번 주제");
            if (i.playlist() != null) sb.append(" · 재생목록 ").append(i.playlist().label);
            if (i.error() != null && i.status() != ShortsService.Status.READY) sb.append(" · 최근 오류: ").append(i.error());
            sb.append('\n');
        }
        for (ShortsBatches.Batch b : batches.list(caller.memberId())) {
            List<String> missing = b.topics().stream().filter(t -> t.shortsId() == null).map(t -> t.number() + "번 " + t.title()).toList();
            if (missing.isEmpty()) continue;
            sb.append("묶음 ").append(b.id()).append(" '").append(b.title()).append("'에 대본이 없는 주제: ").append(String.join(", ", missing)).append('\n');
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
        return "\n쇼츠 후보: 이 글에는 " + String.join(", ", f.reasons()) + "이(가) 있어 30~45초 쇼츠로 만들 만해요. "
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
        return "\n쇼츠 후보: 이 글에는 " + String.join(", ", f.reasons()) + "이(가) 있어 30~45초 쇼츠로 만들 만해요. "
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
        for (JsonNode n : a.path("scenes")) {
            String visual = visual(n.path("visual"));
            // 도식이 빠졌거나 null이면 장면에 도식을 두지 않는다
            JsonNode d = n.path("diagram");
            java.util.Map<String, Object> diagram = d.isMissingNode() || d.isNull() ? null : diagram(d);
            List<ShortsScript.Effect> effects = new ArrayList<>();
            for (JsonNode e : n.path("effects")) {
                effects.add(new ShortsScript.Effect(text(e, "sound"), e.path("at").asDouble(0),
                        e.path("volume").isNumber() ? e.path("volume").asDouble() : null));
            }
            out.add(new ShortsScript.Scene(text(n, "caption"), text(n, "narration"), n.has("code") ? text(n, "code") : null,
                    n.has("image") ? text(n, "image") : null, visual, effects, diagram));
        }
        return out;
    }

    /** visual은 "typing"이나 {"action":"typing"} 둘 다 받는다 (#277 워커와 같은 모양). 그 밖의 값은 null */
    private static String visual(JsonNode v) {
        if (v.isString()) return v.asString();
        if (v.isObject()) return text(v, "action");
        return null;
    }

    /** 설명 도식은 모양 검사(ShortsScript)만 하고 그대로 워커에 넘긴다. 객체가 아닌 값은 빈 도식으로 넘겨 검사에서 거절되게 한다 */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> diagram(JsonNode n) {
        return n.isObject() ? DIAGRAM_JSON.convertValue(n, java.util.Map.class) : java.util.Map.of();
    }

    private static final tools.jackson.databind.json.JsonMapper DIAGRAM_JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static final String CHARACTER_TYPE = "캐릭터(character)는 lognyang·logduck·dubu·cat 가운데 하나를 문자열로 주세요.";

    /** character를 줬는데 문자열이 아니면 true */
    private static boolean badCharacter(JsonNode a) {
        return a.has(CHARACTER) && !a.get(CHARACTER).isString();
    }

    /** 대본 캐릭터(#304). 없으면 null(워커 기본) */
    private static String character(JsonNode a) {
        return a.has(CHARACTER) ? text(a, CHARACTER) : null;
    }

    private static String text(JsonNode a, String field) {
        JsonNode n = a.path(field);
        return n.isString() ? n.asString() : "";
    }

    private static String message(ApiException e) {
        if (e.status().value() == 404) return "쇼츠나 묶음을 찾을 수 없어요 (없는 번호이거나 내 것이 아니에요).";
        if (!e.errors().isEmpty()) return e.errors().stream().map(f -> f.message()).distinct().reduce((x, y) -> x + " " + y).orElse(e.getMessage());
        if ("VERSION_CONFLICT".equals(e.code())) return "그 사이 사용자가 대본을 고쳤어요. list_shorts로 다시 확인해 주세요.";
        return e.getMessage();
    }
}
