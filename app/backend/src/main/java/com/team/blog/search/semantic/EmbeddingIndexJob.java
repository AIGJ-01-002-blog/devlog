package com.team.blog.search.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.search.semantic.EmbeddingClient.EmbedException;
import com.team.blog.search.semantic.EmbeddingClient.Purpose;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.scheduling.JobLock;

/**
 * 글 임베딩 채우기 (spec 054). 따로 대기열을 두지 않고, 간격마다 "임베딩이 없거나 그 뒤로 고쳐진 공개 글"을 최신 글부터
 * indexBatch개씩 찾아 한 번의 요청으로 묶어 임베딩한다. 발행·수정한 글도, 처음 켰을 때의 기존 글도 같은 길로 채워지고,
 * 공급자가 꺼져 있던 동안 밀린 글은 켜진 뒤 이어서 채워진다. 같은 글을 두 번 처리해도 덮어쓰기라 결과가 같다.
 * <p>
 * 공개 글만 임베딩한다(비공개·친구 공개 글 내용은 공급자에게 보내지 않는다). 공개였다가 바뀐 글의 벡터는 남아도 검색에서
 * 공개 조건으로 걸러지고, 글을 영구 삭제하면 함께 지워진다.
 */
@Component
public class EmbeddingIndexJob {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexJob.class);
    /** 한 번 실행에서 처리할 최대 묶음 수 (기존 글이 많아도 한 차례가 너무 길지 않게) */
    static final int MAX_BATCHES_PER_RUN = 10;

    private final JdbcTemplate jdbc;
    private final EmbeddingClient client;
    private final SemanticSearch semantic;
    private final SemanticProperties props;
    private final ContentRenderer renderer;
    private final JobLock lock;

    public EmbeddingIndexJob(JdbcTemplate jdbc, EmbeddingClient client, SemanticSearch semantic, SemanticProperties props,
                             ContentRenderer renderer, JobLock lock) {
        this.jdbc = jdbc;
        this.client = client;
        this.semantic = semantic;
        this.props = props;
        this.renderer = renderer;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${blog.search.semantic.index-interval:60s}", initialDelayString = "${blog.search.semantic.index-interval:60s}")
    public void scheduled() {
        if (!semantic.enabled() || semantic.isDown()) return;
        lock.runExclusively("search-embedding", props.indexTimeout().multipliedBy(MAX_BATCHES_PER_RUN + 1L), this::runOnce);
    }

    private record Source(long id, long version, String title, String contentMd, String tags) {}

    /** @return 이번에 임베딩한 글 수 */
    public int runOnce() {
        if (!semantic.enabled()) return 0;
        int done = 0;
        boolean more = true;
        for (int i = 0; more && i < MAX_BATCHES_PER_RUN; i++) {
            List<Source> batch = pending();
            Optional<List<float[]>> vectors = batch.isEmpty() ? Optional.empty() : embed(batch);
            if (vectors.isPresent()) {
                for (int j = 0; j < batch.size(); j++) save(batch.get(j), vectors.get().get(j));
                done += batch.size();
                // 한 묶음을 다 채우지 못했으면 남은 글이 없다
                more = batch.size() >= props.indexBatch();
            } else {
                more = false;
            }
        }
        if (done > 0 && log.isInfoEnabled()) log.info("글 {}개를 임베딩했습니다 ({})", done, client.model());
        return done;
    }

    /** @return 임베딩 서버가 실패하면 비어 있다 (내려간 것으로 표시한다) */
    private Optional<List<float[]>> embed(List<Source> batch) {
        List<String> texts = new ArrayList<>(batch.size());
        for (Source s : batch) texts.add(text(s));
        try {
            return Optional.of(client.embed(texts, Purpose.DOCUMENT, props.indexTimeout()));
        } catch (EmbedException e) {
            semantic.markDown(e);
            return Optional.empty();
        }
    }

    private List<Source> pending() {
        return jdbc.query("""
                SELECT p.id, p.edit_version, p.title, p.content_md,
                       (SELECT string_agg(g.name, ' ' ORDER BY g.name) FROM post_tag pt JOIN tag g ON g.id = pt.tag_id WHERE pt.post_id = p.id) AS tags
                FROM post p JOIN member m ON m.id = p.author_id
                LEFT JOIN post_embedding e ON e.post_id = p.id
                WHERE\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION + """
                 AND (e.post_id IS NULL OR e.model <> ? OR e.source_version <> p.edit_version)
                ORDER BY p.first_public_at DESC, p.id DESC LIMIT ?
                """, (rs, n) -> new Source(rs.getLong("id"), rs.getLong("edit_version"), rs.getString("title"),
                rs.getString("content_md"), rs.getString("tags")), client.model(), props.indexBatch());
    }

    /** 제목 + 태그 + 본문 글자(마크다운 기호·사진 주소 제외) 앞부분 */
    String text(Source s) {
        StringBuilder sb = new StringBuilder(s.title());
        if (s.tags() != null && !s.tags().isBlank()) sb.append('\n').append(s.tags());
        sb.append('\n').append(renderer.searchText(s.contentMd()).strip());
        String t = sb.toString();
        int max = props.maxChars();
        return t.codePointCount(0, t.length()) <= max ? t : t.substring(0, t.offsetByCodePoints(0, max));
    }

    private void save(Source s, float[] vector) {
        jdbc.update("""
                INSERT INTO post_embedding (post_id, model, source_version, embedding, updated_at)
                VALUES (?, ?, ?, ?::vector, CURRENT_TIMESTAMP)
                ON CONFLICT (post_id) DO UPDATE SET model = EXCLUDED.model, source_version = EXCLUDED.source_version,
                    embedding = EXCLUDED.embedding, updated_at = EXCLUDED.updated_at
                """, s.id(), client.model(), s.version(), EmbeddingClient.literal(vector));
    }
}
