package com.team.blog.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.team.blog.support.IntegrationTest;

/** #266: POINTS_ENABLED가 꺼져 있으면(2.0 묶음 릴리스 전 운영 기본) 메뉴·API가 없는 것과 같고 첫 지급도 하지 않는다 */
// 기능을 끈 시험 셋은 같은 설정으로 Spring 문맥 하나를 함께 쓴다(문맥마다 DB 연결 풀이 생겨 시험 DB 연결 수를 넘지 않게, #266)
@TestPropertySource(properties = {"blog.shorts.enabled=false", "blog.trades.enabled=false", "blog.points.enabled=false"})
class PointsDisabledTest extends IntegrationTest {

    @Test
    void 꺼져_있으면_어디에도_보이지_않는다() throws Exception {
        Session me = signup(uniqueLogin("ptoff"));
        assertThat(read(me.http().perform(get("/api/auth/me")).andReturn()).path("features").path("points").asBoolean()).isFalse();
        me.http().perform(get("/api/me/points")).andExpect(status().isNotFound());
        me.http().perform(get("/api/me/points/history")).andExpect(status().isNotFound());
        me.http().perform(post("/api/me/points/attendance").with(csrf())).andExpect(status().isNotFound());
        me.http().perform(get("/api/me/points/today")).andExpect(status().isNotFound());
        mvc.perform(get("/api/members/" + me.handle() + "/badges")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE member_id = ?", Integer.class, me.memberId())).isZero();
    }
}
