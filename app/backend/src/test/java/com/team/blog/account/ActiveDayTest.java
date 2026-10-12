package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.ActivityTracker;
import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/** spec 066: 날짜별 활동한 회원. 로그인한 채로 쓰면 그날 한 행이 생기고, 대시보드가 날짜별·기간별로 센다. */
class ActiveDayTest extends IntegrationTest {
    @Autowired ActivityTracker tracker;

    long days(long memberId) {
        return jdbc.queryForObject("SELECT count(*) FROM member_active_day WHERE member_id = ?", Long.class, memberId);
    }

    @Test
    void 로그인한_채로_쓰면_그날_한_번만_남는다() throws Exception {
        Session s = signup(uniqueLogin("act"));
        s.http().perform(get("/api/auth/me")).andExpect(status().isOk());
        s.http().perform(get("/api/auth/me")).andExpect(status().isOk());
        assertThat(days(s.memberId())).isEqualTo(1);
        // 같은 날 다시 와도(Redis 키가 사라져도) 한 행이다
        redis.delete(redis.keys("active-day:" + s.memberId() + ":*"));
        tracker.touch(s.memberId());
        assertThat(days(s.memberId())).isEqualTo(1);
    }

    @Test
    void 대시보드는_날짜별_활동한_회원과_기간_합계를_준다() throws Exception {
        Session admin = signup(uniqueLogin("acta"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        Browser a = relogin(admin);
        JsonNode before = read(a.perform(get("/api/admin/dashboard?days=7")).andExpect(status().isOk()).andReturn());

        Session m = signup(uniqueLogin("actm"));
        m.http().perform(get("/api/auth/me")).andExpect(status().isOk());
        // 이 회원은 어제도 왔다: 기간 합계에서는 한 명이다
        jdbc.update("INSERT INTO member_active_day (member_id, day) VALUES (?, (now() AT TIME ZONE 'Asia/Seoul')::date - 1)", m.memberId());

        JsonNode d = read(a.perform(get("/api/admin/dashboard?days=7")).andExpect(status().isOk()).andReturn());
        JsonNode today = d.path("daily").get(d.path("daily").size() - 1);
        JsonNode beforeToday = before.path("daily").get(before.path("daily").size() - 1);
        assertThat(today.path("activeMembers").asLong()).isEqualTo(beforeToday.path("activeMembers").asLong() + 1);
        assertThat(d.path("current").path("activeMembers").asLong())
                .isEqualTo(before.path("current").path("activeMembers").asLong() + 1);
    }

    @Test
    void 사백일이_지난_활동_기록은_지운다() throws Exception {
        Session s = signup(uniqueLogin("actp"));
        jdbc.update("INSERT INTO member_active_day (member_id, day) VALUES (?, current_date - 401)", s.memberId());
        assertThat(tracker.purge()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_active_day WHERE day < current_date - 400", Long.class)).isZero();
    }
}
