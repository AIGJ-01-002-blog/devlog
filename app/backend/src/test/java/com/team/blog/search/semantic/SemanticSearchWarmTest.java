package com.team.blog.search.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import com.team.blog.search.semantic.EmbeddingClient.EmbedException;
import com.team.blog.search.semantic.EmbeddingClient.Purpose;

/** 검색창을 열 때 임베딩 모델 예열, 예열 중 늦은 검색어를 공급자 장애로 보지 않기 */
class SemanticSearchWarmTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final SemanticProperties props = mock(SemanticProperties.class);
    private SemanticSearch semantic;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(client.configured()).thenReturn(true);
        when(client.provider()).thenReturn(EmbeddingClient.Provider.LOCAL);
        when(client.model()).thenReturn("ollama:bge-m3");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(props.warmEvery()).thenReturn(Duration.ofSeconds(60));
        when(props.warmTimeout()).thenReturn(Duration.ofSeconds(30));
        when(props.queryTimeout()).thenReturn(Duration.ofSeconds(3));
        when(props.downFor()).thenReturn(Duration.ofSeconds(60));
        semantic = new SemanticSearch(jdbc, redis, client, props);
    }

    @Test
    void 예열은_간격_안에_한_번만_모델을_부른다() throws Exception {
        when(values.setIfAbsent(SemanticSearch.WARM_GATE, "1", Duration.ofSeconds(60))).thenReturn(true, false);
        semantic.warmUp();
        semantic.warmUp();
        verify(client, timeout(2000)).embed(List.of("warm"), Purpose.QUERY, Duration.ofSeconds(30));
        verify(values, never()).set(eq(SemanticSearch.DOWN), anyString(), any(Duration.class));
    }

    @Test
    void 예열이_끝나면_진행_표시를_지운다() throws Exception {
        when(values.setIfAbsent(SemanticSearch.WARM_GATE, "1", Duration.ofSeconds(60))).thenReturn(true);
        semantic.warmUp();
        verify(values).set(SemanticSearch.WARMING, "1", Duration.ofSeconds(30));
        verify(redis, timeout(2000)).delete(SemanticSearch.WARMING);
    }

    @Test
    void 공급자가_내려가_있으면_예열하지_않는다() throws Exception {
        when(redis.hasKey(SemanticSearch.DOWN)).thenReturn(true);
        semantic.warmUp();
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(client, never()).embed(any(), any(), any());
    }

    @Test
    void 예열_중에_검색어_임베딩이_늦어도_내려간_것으로_표시하지_않는다() throws Exception {
        when(redis.hasKey(SemanticSearch.WARMING)).thenReturn(true);
        when(client.embed(any(), eq(Purpose.QUERY), any())).thenThrow(new EmbedException("시간 초과"));
        assertThat(semantic.nearest("트랜잭션", null)).isEmpty();
        verify(values, never()).set(eq(SemanticSearch.DOWN), anyString(), any(Duration.class));
    }

    @Test
    void 예열_중이_아니면_실패한_공급자를_건너뛴다() throws Exception {
        when(client.embed(any(), eq(Purpose.QUERY), any())).thenThrow(new EmbedException("연결 실패"));
        assertThat(semantic.nearest("트랜잭션", null)).isEmpty();
        verify(values).set(SemanticSearch.DOWN, "1", Duration.ofSeconds(60));
    }

    @Test
    void 여러_사람이_동시에_검색창을_열어도_예열은_한_번() throws Exception {
        // Redis SETNX처럼 처음 한 번만 true
        AtomicBoolean taken = new AtomicBoolean();
        when(values.setIfAbsent(SemanticSearch.WARM_GATE, "1", Duration.ofSeconds(60))).thenAnswer(i -> taken.compareAndSet(false, true));
        int n = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        try (var pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        semantic.warmUp();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        // 예열 작업이 늦게 시작해도 기다린 뒤, 더 불리지 않는지 확인한다
        verify(client, timeout(2000)).embed(any(), any(), any());
        verify(client, after(300).times(1)).embed(any(), any(), any());
    }

    @Test
    void Gemini에는_예열을_보내지_않는다() throws Exception {
        when(client.provider()).thenReturn(EmbeddingClient.Provider.GEMINI);
        semantic.warmUp();
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(client, after(200).never()).embed(any(), any(), any());
    }
}
