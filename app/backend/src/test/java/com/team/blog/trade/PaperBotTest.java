package com.team.blog.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.trade.application.PaperBot;

/** spec 102: /trade에서 켜면 devlog 서버가 모의 봇을 돌려 계좌가 바로 채워지고, 회원 컴퓨터 봇의 알림과 섞이지 않는다 */
class PaperBotTest extends TradeBotSimTest {
    @Autowired
    PaperBot paper;

    @Test
    void 켜면_서버_모의_계좌가_바로_생기고_끄면_멈춘다() throws Exception {
        Session me = signup(uniqueLogin("pbot"));
        assertThat(read(me.http().perform(get("/api/me/trade-bot/paper")).andExpect(status().isOk()).andReturn())
                .path("enabled").asBoolean()).isFalse();

        me.http().perform(asJson(put("/api/me/trade-bot/paper"), Map.of("enabled", true))).andExpect(status().isOk());
        JsonNode v = read(me.http().perform(get("/api/me/trade-bot")).andExpect(status().isOk()).andReturn());
        assertThat(v.path("status").path("reportedAt").isNull()).isFalse();
        assertThat(v.path("accounts").findValuesAsString("account"))
                .containsExactly("server-paper-binance-spot-1h", "server-paper-upbit-spot-1h");
        assertThat(v.path("accounts").get(0).path("cash").decimalValue()).isEqualByComparingTo("1000");
        assertThat(v.path("accounts").get(0).path("rules").findValuesAsString("value")).contains("devlog 서버 (모의)");

        // 회원 컴퓨터 봇이 계좌 목록을 통째로 보내도 서버 계좌는 남는다
        String token = token(me);
        call(token, Map.of("accounts", List.of(Map.of("account", "trade-state-binance-spot-1h.json", "currency", "USDT", "cash", 900))));
        assertThat(read(me.http().perform(get("/api/me/trade-bot")).andReturn()).path("accounts").size()).isEqualTo(3);
        JsonNode taken = call(token, Map.of("accounts", List.of(Map.of("account", "server-paper-x", "currency", "USDT", "cash", 1))));
        assertThat(taken.path("isError").asBoolean()).isTrue();

        me.http().perform(asJson(put("/api/me/trade-bot/paper"), Map.of("enabled", false))).andExpect(status().isOk());
        assertThat(read(me.http().perform(get("/api/me/trade-bot/paper")).andReturn()).path("enabled").asBoolean()).isFalse();
        // 끈 회원은 돌지 않는다: 이 회원의 마지막 알림 시각이 그대로다
        String reported = "SELECT reported_at FROM trade_bot_status WHERE member_id = ?";
        java.sql.Timestamp before = jdbc.queryForObject(reported, java.sql.Timestamp.class, me.memberId());
        paper.runAll();
        assertThat(jdbc.queryForObject(reported, java.sql.Timestamp.class, me.memberId())).isEqualTo(before);
    }
}
