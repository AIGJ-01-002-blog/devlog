package com.team.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.team.blog.post.infra.AutosaveStore;
import com.team.blog.support.IntegrationTest;

/**
 * 자동 저장 버퍼(Redis)가 멈췄을 때 (docs/04 §2-6): 저장은 DB로 바로 이어지고, 버전 판정은 DB 버전으로 한다.
 * 세션은 그대로 쓰도록 자동 저장 저장소만 장애를 흉내 낸다.
 */
class RedisOutageTest extends IntegrationTest {
    @MockitoSpyBean AutosaveStore autosave;

    @AfterEach
    void heal() {
        reset(autosave);
    }

    private void breakAutosave() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("테스트: Redis 장애");
        doThrow(down).when(autosave).save(any(), any(), any(), anyBoolean());
        doThrow(down).when(autosave).read(anyLong());
        doThrow(down).when(autosave).deleteIfVersionAtMost(anyLong(), anyLong());
    }

    @Test
    void 자동_저장은_DB에_바로_쓰고_오래된_버전은_막는다() throws Exception {
        Session s = signup(uniqueLogin("down"));
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        breakAutosave();
        s.http().perform(asJson(put("/api/posts/" + id + "/autosave"), Map.of("title", "t", "contentMd", "장애 중 저장", "baseVersion", 0)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id)).isEqualTo("장애 중 저장");
        s.http().perform(asJson(put("/api/posts/" + id + "/autosave"), Map.of("title", "t", "contentMd", "늦은 탭", "baseVersion", 0)))
                .andExpect(status().isConflict());
        assertThat(read(s.http().perform(get("/api/posts/" + id + "/edit")).andExpect(status().isOk()).andReturn())
                .path("version").asLong()).isEqualTo(1);
    }

    @Test
    void 발행도_DB_버전으로_판정해_한_번만_반영된다() throws Exception {
        Session s = signup(uniqueLogin("downpub"));
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        breakAutosave();
        var body = Map.of("title", "제목", "contentMd", "본문", "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of());
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT edit_version FROM post WHERE id = ?", Long.class, id)).isEqualTo(1L);
    }
}
