package com.team.blog.search.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.team.blog.search.semantic.EmbeddingClient.EmbedException;
import com.team.blog.search.semantic.EmbeddingClient.Purpose;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.scheduling.JobLock;

/** 글 색인을 작은 요청으로 나눠 끝난 만큼 저장하고, 색인 실패가 검색의 의미 검색을 끄지 않기 */
class EmbeddingIndexJobTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final EmbeddingClient client = mock(EmbeddingClient.class);
    private final SemanticProperties props = mock(SemanticProperties.class);
    private final ContentRenderer renderer = mock(ContentRenderer.class);
    private final JobLock lock = mock(JobLock.class);
    private EmbeddingIndexJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(client.configured()).thenReturn(true);
        when(client.model()).thenReturn("ollama:bge-m3");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(props.enabled()).thenReturn(true);
        when(props.indexBatch()).thenReturn(20);
        when(props.indexChunk()).thenReturn(1);
        when(props.indexTimeout()).thenReturn(Duration.ofSeconds(30));
        when(props.downFor()).thenReturn(Duration.ofSeconds(60));
        when(props.maxChars()).thenReturn(3000);
        when(renderer.searchText(any())).thenReturn("본문");
        // 색인할 공개 글 3개
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            List<Object> rows = new ArrayList<>();
            for (long id = 1; id <= 3; id++) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("id")).thenReturn(id);
                when(rs.getLong("edit_version")).thenReturn(1L);
                when(rs.getString("title")).thenReturn("글 " + id);
                when(rs.getString("content_md")).thenReturn("본문");
                rows.add(mapper.mapRow(rs, (int) id - 1));
            }
            return rows;
        });
        SemanticSearch semantic = new SemanticSearch(jdbc, redis, client, props);
        job = new EmbeddingIndexJob(jdbc, client, semantic, props, renderer, lock);
    }

    @Test
    void 글을_하나씩_보내_끝난_만큼_저장하고_실패하면_색인만_쉰다() throws Exception {
        when(client.embed(any(), eq(Purpose.DOCUMENT), any()))
                .thenReturn(List.of(new float[] {0.1f}))
                .thenReturn(List.of(new float[] {0.2f}))
                .thenThrow(new EmbedException("시간 초과"));

        assertThat(job.runOnce()).isEqualTo(2);

        verify(client, times(3)).embed(any(), eq(Purpose.DOCUMENT), any());
        verify(jdbc, times(2)).update(anyString(), any(), any(), any(), any());
        verify(values).set(SemanticSearch.INDEX_DOWN, "1", Duration.ofSeconds(60));
        verify(values, never()).set(eq(SemanticSearch.DOWN), anyString(), any(Duration.class));
    }

    @Test
    void 색인이_쉬는_동안에는_예약_실행을_건너뛴다() {
        when(redis.hasKey(SemanticSearch.INDEX_DOWN)).thenReturn(true);

        job.scheduled();

        verify(lock, never()).runExclusively(anyString(), any(), any());
    }

    @Test
    void 잠금_시간은_요청_수만큼_잡는다() {
        job.scheduled();

        // 글 20개를 1개씩 = 요청 20번, 여유 1번
        verify(lock).runExclusively(eq("search-embedding"), eq(Duration.ofSeconds(30).multipliedBy(21)), any());
    }
}
