package com.team.blog.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;
import com.team.blog.view.application.VisitRecorder;

/** spec 064: 사이트 방문자. 같은 날 같은 사람은 한 명, 30분 넘게 쉬면 방문 한 번 더, 로봇은 세지 않고 운영진은 센다. */
class VisitTest extends IntegrationTest {
    @Autowired VisitRecorder visits;

    MockHttpServletResponse visit(Browser b, String ua) throws Exception {
        var req = post("/api/visits").with(csrf());
        if (ua != null) req.header("User-Agent", ua);
        return b.perform(req).andReturn().getResponse();
    }

    long rows() {
        return jdbc.queryForObject("SELECT count(*) FROM site_visit", Long.class);
    }

    long totalVisits() {
        return jdbc.queryForObject("SELECT coalesce(sum(visits), 0) FROM site_visit", Long.class);
    }

    @Test
    void 같은_사람은_하루에_한_명이고_쉬었다_오면_방문_수만_는다() throws Exception {
        long before = rows();
        long visitsBefore = totalVisits();
        Browser guest = browser().from("203.0.113.61");
        MockHttpServletResponse first = visit(guest, ViewTest.CHROME);
        assertThat(first.getStatus()).isEqualTo(204);
        assertThat(first.getHeader("Set-Cookie")).contains("vid=").contains("HttpOnly");
        // 처음 받은 쿠키로 다시 와도 같은 사람이다
        assertThat(visit(guest, ViewTest.CHROME).getStatus()).isEqualTo(204);
        assertThat(rows()).isEqualTo(before + 1);
        assertThat(totalVisits()).isEqualTo(visitsBefore + 1);

        Session member = signup(uniqueLogin("vis"));
        visit(member.http(), ViewTest.CHROME);
        assertThat(rows()).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE member AND last_at > now() - interval '1 minute'",
                Long.class)).isPositive();

        // 30분 넘게 쉬었다 오면 방문 수가 하나 는다
        jdbc.update("UPDATE site_visit SET last_at = last_at - interval '31 minutes' WHERE NOT member AND last_at > now() - interval '1 minute'");
        visit(guest, ViewTest.CHROME);
        assertThat(rows()).isEqualTo(before + 2);
        assertThat(totalVisits()).isEqualTo(visitsBefore + 3);
    }

    @Test
    void 늦게_끝난_요청이_마지막_방문_시각을_되돌리지_않는다() throws Exception {
        Browser guest = browser().from("203.0.113.66");
        visit(guest, ViewTest.CHROME);
        // 더 나중 시각의 요청이 먼저 반영된 상황: 저장된 시각이 지금보다 뒤다
        jdbc.update("UPDATE site_visit SET last_at = now() + interval '10 minutes' WHERE NOT member AND first_at > now() - interval '1 minute'");
        long visitsBefore = totalVisits();
        visit(guest, ViewTest.CHROME);
        assertThat(totalVisits()).isEqualTo(visitsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE last_at > now() + interval '9 minutes'", Long.class))
                .isPositive();
    }

    @Test
    void 방문자_쿠키를_바꿔_가며_보내도_IP마다_1분_120번까지만_센다() throws Exception {
        long before = rows();
        for (int i = 0; i < 125; i++) {
            browser().from("203.0.113.67").perform(post("/api/visits").with(csrf()).header("User-Agent", ViewTest.CHROME)
                    .cookie(new jakarta.servlet.http.Cookie("vid", java.util.UUID.randomUUID().toString())));
        }
        assertThat(rows()).isEqualTo(before + 120);
    }

    @Test
    void 운영진은_세고_로봇과_미리_불러오기는_세지_않는다() throws Exception {
        Session admin = signup(uniqueLogin("vsa"));
        jdbc.update("UPDATE member SET role = 'MANAGER' WHERE id = ?", admin.memberId());
        Browser staff = relogin(admin);
        long before = rows();

        assertThat(visit(staff, ViewTest.CHROME).getStatus()).isEqualTo(204);
        assertThat(rows()).isEqualTo(before + 1);
        before = rows();
        assertThat(visit(browser().from("203.0.113.62"), "Googlebot/2.1 (+http://www.google.com/bot.html)").getStatus()).isEqualTo(204);
        assertThat(visit(browser().from("203.0.113.63"), null).getStatus()).isEqualTo(204);
        assertThat(browser().from("203.0.113.64").perform(post("/api/visits").with(csrf()).header("User-Agent", ViewTest.CHROME)
                .header("Sec-Purpose", "prefetch")).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void 대시보드에_기간_방문자와_오늘_방문자가_나온다() throws Exception {
        Session admin = signup(uniqueLogin("vsd"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        Browser a = relogin(admin);
        JsonNode before = read(a.perform(get("/api/admin/dashboard?days=7")).andExpect(status().isOk()).andReturn());

        visit(browser().from("203.0.113.65"), ViewTest.CHROME);
        visit(signup(uniqueLogin("vsm")).http(), ViewTest.CHROME);
        // 어제 왔던 사람이 오늘도 오면 기간 방문자는 한 명이다
        jdbc.update("""
                INSERT INTO site_visit (day, visitor, member, visits, first_at, last_at)
                SELECT day - 1, visitor, member, 2, first_at - interval '1 day', last_at - interval '1 day'
                FROM site_visit WHERE day = (now() AT TIME ZONE 'Asia/Seoul')::date AND member ORDER BY first_at DESC LIMIT 1
                """);

        JsonNode d = read(a.perform(get("/api/admin/dashboard?days=7")).andExpect(status().isOk()).andReturn());
        assertThat(d.path("visitors").path("today").asLong()).isEqualTo(before.path("visitors").path("today").asLong() + 2);
        assertThat(d.path("current").path("visitors").asLong()).isEqualTo(before.path("current").path("visitors").asLong() + 2);
        assertThat(d.path("current").path("visits").asLong()).isEqualTo(before.path("current").path("visits").asLong() + 4);
        assertThat(d.path("visitors").path("members").asLong()).isEqualTo(before.path("visitors").path("members").asLong() + 1);
        JsonNode last = d.path("daily").get(d.path("daily").size() - 1);
        assertThat(last.path("visitors").asLong()).isEqualTo(d.path("visitors").path("today").asLong());
    }

    @Test
    void 사백일이_지난_방문_기록은_지운다() {
        jdbc.update("""
                INSERT INTO site_visit (day, visitor, member, visits, first_at, last_at)
                VALUES (current_date - 401, 'old0000000000000000000000000000x', false, 1, now() - interval '401 days', now() - interval '401 days')
                """);
        assertThat(visits.purge()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_visit WHERE day < current_date - 400", Long.class)).isZero();
    }
}
