package com.team.blog.search.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.team.blog.search.semantic.EmbeddingClient;
import com.team.blog.search.semantic.EmbeddingClient.Purpose;
import com.team.blog.support.IntegrationTest;

/** 여러 사람이 검색창을 동시에 열어도 /api/search/warm은 모델을 한 번만 부른다 (실제 Redis로 간격 표시를 잡는다) */
class SearchWarmEndpointTest extends IntegrationTest {
    private static final List<String> KEYS = List.of("search:embed:warm-gate", "search:embed:warming", "search:embed:down");

    @MockitoBean EmbeddingClient client;

    @BeforeEach
    void local() {
        redis.delete(KEYS);
        when(client.provider()).thenReturn(EmbeddingClient.Provider.LOCAL);
        when(client.configured()).thenReturn(true);
        when(client.model()).thenReturn("ollama:bge-m3");
    }

    @AfterEach
    void clean() {
        redis.delete(KEYS);
    }

    @Test
    void 동시에_검색창을_열어도_예열은_한_번만_보낸다() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(get("/api/search/warm")).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) assertThat(r.get()).isEqualTo(204);
        } finally {
            pool.shutdownNow();
        }
        verify(client, after(1000).times(1)).embed(eq(List.of("warm")), eq(Purpose.QUERY), any(Duration.class));
    }
}
