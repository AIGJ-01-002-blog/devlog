package com.team.blog.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/**
 * #243 매매 일지: 봇이 MCP record_trade로 체결을 남기고, 회원이 날별로 보고, 메모를 남겨 요약 임시글과 쇼츠 대본을 만든다.
 */
class TradeJournalTest extends IntegrationTest {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    String token(Session s) throws Exception {
        return read(s.http().perform(asJson(post("/api/me/tokens"), Map.of("name", "trade-bot", "scope", "WRITE")))
                .andExpect(status().isCreated()).andReturn()).path("secret").asString();
    }

    JsonNode rpc(String token, String method, Object params) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        if (params != null) body.put("params", params);
        return read(mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn()).path("result");
    }

    JsonNode call(String token, String tool, Map<String, Object> args) throws Exception {
        return rpc(token, "tools/call", Map.of("name", tool, "arguments", args));
    }

    static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asString();
    }

    static String at(LocalDate day, String time) {
        return day + "T" + time + "+09:00";
    }

    JsonNode trade(String token, String id, LocalDate day, String time, String symbol, String name, String side, int qty, int price,
                   Integer pnl, String reason) throws Exception {
        Map<String, Object> a = new HashMap<>(Map.of("external_id", id, "symbol", symbol, "symbol_name", name, "side", side,
                "quantity", qty, "price", price, "fee", 150, "reason", reason, "executed_at", at(day, time)));
        if (pnl != null) a.put("realized_pnl", pnl);
        return call(token, "record_trade", a);
    }

    @Test
    void 봇이_남긴_체결을_날별로_보고_메모로_요약글과_쇼츠_대본을_만든다() throws Exception {
        Session me = signup(uniqueLogin("trade"));
        String t = token(me);
        LocalDate d1 = LocalDate.now(KST).minusDays(2);
        LocalDate d2 = LocalDate.now(KST).minusDays(1);

        // 도구가 보이고, 같은 체결은 한 번만 남는다
        assertThat(rpc(t, "tools/list", null).path("tools").findValuesAsString("name")).contains("record_trade", "get_trade_day");
        JsonNode first = trade(t, "o-1", d1, "09:05:00", "005930", "삼성전자", "BUY", 10, 71000, null, "20일 이동평균선 위로 올라섬");
        assertThat(first.path("isError").asBoolean()).as(text(first)).isFalse();
        assertThat(text(first)).contains("체결을 남겼어요", "/trades?date=" + d1);
        assertThat(text(trade(t, "o-1", d1, "09:05:00", "005930", "삼성전자", "BUY", 10, 71000, null, "같은 주문"))).contains("이미 남긴 체결");
        trade(t, "o-2", d1, "13:40:00", "005930", "삼성전자", "SELL", 10, 72500, 14600, "목표 수익 2% 도달");
        trade(t, "o-3", d1, "14:10:00", "035720", "카카오", "BUY", 20, 41000, null, "거래량이 평소의 3배");

        // 잘못된 기록은 이유를 알려 주고 남기지 않는다
        JsonNode bad = call(t, "record_trade", Map.of("external_id", "bad-1", "symbol", "삼성", "side", "BUY", "quantity", 1, "price", 100,
                "realized_pnl", 10));
        assertThat(bad.path("isError").asBoolean()).isTrue();
        assertThat(text(bad)).contains("종목 코드", "실현 손익은 매도");

        trade(t, "o-4", d2, "09:30:00", "035720", "카카오", "SELL", 20, 39800, -24300, "손절선 -3% 도달");
        trade(t, "o-5", d2, "10:15:00", "000660", "SK하이닉스", "BUY", 3, 182000, null, "갭 상승 뒤 첫 눌림");
        trade(t, "o-6", d2, "15:10:00", "000660", "SK하이닉스", "SELL", 3, 185500, 10200, "장 마감 전 정리");

        // 날별 요약과 누적 실현 손익
        JsonNode overview = read(me.http().perform(get("/api/me/trades?days=7")).andExpect(status().isOk()).andReturn());
        assertThat(overview.path("days")).hasSize(2);
        JsonNode latest = overview.path("days").get(0);
        assertThat(latest.path("date").asString()).isEqualTo(d2.toString());
        assertThat(latest.path("summary").path("trades").asInt()).isEqualTo(3);
        assertThat(latest.path("summary").path("realizedPnl").decimalValue()).isEqualByComparingTo("-14100");
        assertThat(latest.path("cumulativePnl").decimalValue()).isEqualByComparingTo("500");
        assertThat(overview.path("total").path("wins").asInt()).isEqualTo(2);
        assertThat(overview.path("total").path("losses").asInt()).isEqualTo(1);
        assertThat(overview.path("live").asBoolean()).isFalse();

        // 메모가 없으면 쇼츠 대본을 만들지 않는다 (숫자만 읽는 영상은 양산형)
        me.http().perform(post("/api/me/trades/" + d1 + "/shorts").with(csrf())).andExpect(status().isConflict());

        // 요약 임시글: 고지·체결 표·메모가 들어간 비공개 임시글. 다시 눌러도 같은 글
        me.http().perform(asJson(put("/api/me/trades/" + d1 + "/memo"), Map.of("memo",
                "삼성전자는 계획대로 2%에서 팔았는데, 카카오는 거래량만 보고 따라 샀어요. 다음에는 거래량과 함께 추세도 확인하려고 해요.")))
                .andExpect(status().isOk());
        JsonNode drafted = read(me.http().perform(post("/api/me/trades/" + d1 + "/draft").with(csrf())).andExpect(status().isOk()).andReturn());
        long postId = drafted.path("postId").asLong();
        assertThat(postId).isPositive();
        assertThat(read(me.http().perform(post("/api/me/trades/" + d1 + "/draft").with(csrf())).andReturn()).path("postId").asLong()).isEqualTo(postId);
        Map<String, Object> post = jdbc.queryForMap("SELECT title, content_md, status FROM post WHERE id = ?", postId);
        assertThat(post.get("status")).isEqualTo("DRAFT");
        assertThat((String) post.get("title")).isEqualTo("매매 일지 " + d1 + " (모의투자): 실현 손익 +14,600원");
        assertThat((String) post.get("content_md")).contains("투자 권유가 아니며", "## 오늘 메모", "거래량과 함께 추세도",
                "| 09:05 | 삼성전자 | 매수 | 10 | 71,000원 |  | 20일 이동평균선 위로 올라섬 |",
                "| 13:40 | 삼성전자 | 매도 | 10 | 72,500원 | +14,600원 | 목표 수익 2% 도달 |",
                "- 지금까지 실현 손익: +14,600원");

        // 쇼츠 대본: 요약 글을 원본으로 잇고, 양산형 검사를 통과한다
        JsonNode s1 = read(me.http().perform(post("/api/me/trades/" + d1 + "/shorts").with(csrf())).andExpect(status().isOk()).andReturn());
        long shorts1 = s1.path("shortsId").asLong();
        JsonNode item = read(me.http().perform(get("/api/me/shorts/" + shorts1)).andExpect(status().isOk()).andReturn());
        assertThat(item.path("postId").asLong()).isEqualTo(postId);
        assertThat(item.path("estimatedSeconds").asDouble()).isLessThanOrEqualTo(60);
        assertThat(item.path("scenes").findValuesAsString("narration")).anyMatch(n -> n.startsWith("제가 오늘 느낀 건,"))
                .anyMatch(n -> n.contains("투자 권유가 아니라"));
        JsonNode check1 = read(me.http().perform(post("/api/me/shorts/" + shorts1 + "/check").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(check1.path("passed").asBoolean()).as(check1.toString()).isTrue();

        // 다음 날 대본도 앞날 대본과 겹친다고 막히지 않는다 (메모와 체결이 다르다)
        me.http().perform(asJson(put("/api/me/trades/" + d2 + "/memo"), Map.of("memo",
                "카카오 손절은 아팠지만 -3% 규칙을 지켰고, SK하이닉스는 갭 상승 뒤 눌림을 기다린 덕분에 만회했어요.")))
                .andExpect(status().isOk());
        long shorts2 = read(me.http().perform(post("/api/me/trades/" + d2 + "/shorts").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("shortsId").asLong();
        JsonNode check2 = read(me.http().perform(post("/api/me/shorts/" + shorts2 + "/check").with(csrf())).andExpect(status().isOk()).andReturn());
        assertThat(check2.path("passed").asBoolean()).as(check2.toString()).isTrue();

        // AI가 읽는 하루 기록
        String day = text(call(t, "get_trade_day", Map.of("date", d2.toString())));
        assertThat(day).contains("체결 3건", "카카오(035720) 매도 20주 @ 39800원", "손익 -24300원", "사용자 메모: 카카오 손절은", "투자 권유");

        // 잘못 보낸 체결을 지우면 요약도 바뀐다. 남의 체결은 지울 수 없다
        long fill = read(me.http().perform(get("/api/me/trades/" + d2)).andReturn()).path("fills").get(1).path("id").asLong();
        Session other = signup(uniqueLogin("trade2"));
        other.http().perform(delete("/api/me/trades/fills/" + fill).with(csrf())).andExpect(status().isNotFound());
        assertThat(read(other.http().perform(get("/api/me/trades")).andReturn()).path("days")).isEmpty();
        me.http().perform(delete("/api/me/trades/fills/" + fill).with(csrf())).andExpect(status().isNoContent());
        assertThat(read(me.http().perform(get("/api/me/trades/" + d2)).andReturn()).path("summary").path("trades").asInt()).isEqualTo(2);
    }

    @Test
    void 하루_기록_상한을_넘으면_봇을_멈추라고_알린다() throws Exception {
        Session me = signup(uniqueLogin("tradecap"));
        String t = token(me);
        LocalDate d = LocalDate.now(KST).minusDays(1);
        for (int i = 0; i < 8; i++) {
            JsonNode r = trade(t, "cap-" + i, d, "10:00:0" + i, "005930", "삼성전자", "BUY", 1, 70000, null, "");
            assertThat(r.path("isError").asBoolean()).as(text(r)).isFalse();
        }
        JsonNode over = trade(t, "cap-9", d, "10:00:09", "005930", "삼성전자", "BUY", 1, 70000, null, "");
        assertThat(over.path("isError").asBoolean()).isTrue();
        assertThat(text(over)).contains("하루 8건까지");
        // 이미 남긴 체결을 다시 보내는 것은 상한과 상관없이 알려 준다
        assertThat(text(trade(t, "cap-0", d, "10:00:00", "005930", "삼성전자", "BUY", 1, 70000, null, ""))).contains("이미 남긴 체결");
    }
}
