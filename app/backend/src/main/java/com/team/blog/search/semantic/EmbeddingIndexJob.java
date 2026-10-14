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
 * indexBatch개씩 찾아 indexChunk개씩 나눠 임베딩한다. 발행·수정한 글도, 처음 켰을 때의 기존 글도 같은 길로 채워지고,
 * 공급자가 꺼져 있던 동안 밀린 글은 켜진 뒤 이어서 채워진다. 같은 글을 두 번 처리해도 덮어쓰기라 결과가 같다.
 * <p>
 * CPU로 도는 서버 안 Ollama는 글 하나에 10초쯤 걸려서, 20개를 한 요청에 묶으면 시간 제한을 넘겨 한 개도 저장하지 못했다.
 * 그래서 작은 요청으로 나눠 끝난 만큼 바로 저장하고, 색인이 실패해도 검색어 임베딩(의미 검색)은 끄지 않는다.
 * <p>
 * 공개 글만 임베딩한다(비공개·친구 공개 글 내용은 공급자에게 보내지 않는다). 공개였다가 바뀐 글의 벡터는 남아도 검색에서
 * 공개 조건으로 걸러지고, 글을 영구 삭제하면 함께 지워진다.
 */
@Component
public class EmbeddingIndexJob {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexJob.class);
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
        if (!semantic.enabled() || semantic.isDown() || semantic.isIndexDown()) return;
        lock.runExclusively("search-embedding", props.indexTimeout().multipliedBy(requestsPerRun() + 1L), this::runOnce);
    }

    private record Source(long id, long version, String title, String contentMd, String tags) {}

    /** 한 번 실행에서 보낼 최대 요청 수 (잠금 시간을 이만큼 잡는다) */
    private int requestsPerRun() {
        int chunk = Math.max(1, props.indexChunk());
        return (Math.max(1, props.indexBatch()) + chunk - 1) / chunk;
    }

    /** @return 이번에 임베딩한 글 수. 실패하면 그때까지 저장한 수 */
    public int runOnce() {
        if (!semantic.enabled()) return 0;
        List<Source> batch = pending();
        int chunk = Math.max(1, props.indexChunk());
        int done = 0;
        for (int from = 0; from < batch.size(); from += chunk) {
            List<Source> part = batch.subList(from, Math.min(from + chunk, batch.size()));
            Optional<List<float[]>> vectors = embed(part);
            if (vectors.isEmpty()) break;
            for (int j = 0; j < part.size(); j++) save(part.get(j), vectors.get().get(j));
            done += part.size();
        }
        if (done > 0 && log.isInfoEnabled()) log.info("글 {}개를 임베딩했습니다 ({})", done, client.model());
        return done;
    }

    /** @return 임베딩 서버가 실패하면 비어 있다 (색인만 잠시 쉰다. 검색어 임베딩은 따로 판단한다) */
    private Optional<List<float[]>> embed(List<Source> batch) {
        List<String> texts = new ArrayList<>(batch.size());
        for (Source s : batch) texts.add(text(s));
        try {
            return Optional.of(client.embed(texts, Purpose.DOCUMENT, props.indexTimeout()));
        } catch (EmbedException e) {
            semantic.markIndexDown(e);
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
