package com.team.blog.search.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.query.PostCard.Author;
import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.post.infra.PostSql;
import com.team.blog.search.semantic.SemanticProperties;
import com.team.blog.search.semantic.SemanticSearch;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.cursor.CursorCodec;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.markdown.ContentRenderer;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shared.text.BioText;
import com.team.blog.shared.time.Times;

/**
 * 글·사람 검색 (spec 014, docs/33). 글 검색은 홈 목록과 같은 공용 공개 조건만 쓰므로 친구 공개·비공개·휴지통·숨김·
 * 탈퇴 신청 작성자의 글은 나오지 않는다(FR-002). 점수는 매기지 않고 단계(제목 → 제목·태그 → 본문) 안에서 최신순이다.
 * <p>
 * 단계마다 ① 최근 공개 글 N개(설정 recent-window) 안에서 먼저 찾고, ② 모자라면 가장 긴 단어로 제목·태그·본문 후보를
 * 각각 인덱스로 모아(UNION) 전체 조건을 확인한다. 태그 EXISTS를 OR로 섞으면 본문 인덱스를 못 쓴다 (docs/33 §4·§8).
 * 찾은 번호로 카드(글 + 작성자 + 수)를 한 번에 읽는다.
 * <p>
 * 하이브리드 (spec 054): 관련도순이고 의미 검색을 쓸 수 있으면, 위 키워드 결과 앞쪽(keywordCandidates개)과 의미 검색 결과를
 * 순위 섞기(RRF)로 합친다. 검색어가 그대로 없어도 뜻이 가까운 글이 나오고, 제목·태그에 그대로 있는 글은 계속 위에 남는다.
 * 섞은 목록이 끝나면 남은 키워드 결과가 키워드 순서대로 이어진다. 의미 검색을 쓸 수 없으면 키워드 결과 그대로다.
 */
@Service
public class SearchQuery {
    public enum Sort { RELEVANCE, LATEST }

    /** 최신순은 단계 0 하나다 (커서 {단계, 시각, 번호}). */
    static final int LATEST_STAGE = 0;
    /** 섞은 목록 안 위치를 가리키는 커서 단계 (커서 {단계, 위치, 0}) */
    static final int HYBRID_STAGE = 9;
    static final int PEOPLE_LIMIT = 20;

    private static final String TITLE = "p.title ILIKE ?";
    private static final String TAG = "EXISTS (SELECT 1 FROM post_tag pt JOIN tag g ON g.id = pt.tag_id WHERE pt.post_id = p.id AND g.name ILIKE ?)";
    private static final String BODY = "p.content_md ILIKE ?";
    private static final String FIRST_PUBLIC_AT = "first_public_at";

    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final ImageUrls imageUrls;
    private final ContentRenderer renderer;
    private final int pageSize;
    private final int recentWindow;
    private final SemanticSearch semantic;
    private final SemanticProperties semanticProps;

    public SearchQuery(JdbcTemplate jdbc, CursorCodec cursors, ImageUrls imageUrls, ContentRenderer renderer, BlogProperties props,
                       SemanticSearch semantic, SemanticProperties semanticProps) {
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.imageUrls = imageUrls;
        this.renderer = renderer;
        this.pageSize = props.feed().pageSize();
        this.recentWindow = props.search().recentWindow();
        this.semantic = semantic;
        this.semanticProps = semanticProps;
    }

    /**
     * 목록 카드(docs/10 §2)와 같은 모양에서 미리보기 자리만 검색어 주변 문장이다. snippetHtml에는 mark 외 태그가 없다.
     * @param similar 검색어가 그대로 들어 있지 않고 뜻이 가까워 찾은 글 (054). 화면은 "비슷한 글" 표시를 단다
     */
    public record Hit(long id, String url, String title, String snippetHtml, String thumbnailUrl, Instant firstPublicAt,
                      int commentCount, int likeCount, Author author, boolean similar) {}

    /** @param notice TWO_CHAR_TITLE_TAG_ONLY(2글자 단어 안내), TOO_SHORT(남는 단어 없음) 또는 null */
    public record PostPage(String query, List<Hit> items, String nextCursor, String notice) {}

    public record Person(long id, String handle, String nickname, String bioFirstLine, String profileImageUrl) {}

    public record PeoplePage(String query, List<Person> items, String notice) {}

    private record Found(int stage, long id, Instant at) {}

    /** 한 조건 조각과 그 인자. */
    private record Sql(String text, List<Object> args) {
        static Sql and(List<Sql> parts) {
            return join(parts, " AND ");
        }

        static Sql or(List<Sql> parts) {
            return join(parts, " OR ");
        }

        private static Sql join(List<Sql> parts, String op) {
            StringBuilder sb = new StringBuilder("(");
            List<Object> args = new ArrayList<>();
            for (Sql p : parts) {
                if (sb.length() > 1) sb.append(op);
                sb.append(p.text);
                args.addAll(p.args);
            }
            return new Sql(sb.append(')').toString(), args);
        }

        Sql andNot(Sql other) {
            List<Object> all = new ArrayList<>(args);
            all.addAll(other.args);
            return new Sql("(" + text + " AND NOT " + other.text + ")", all);
        }
    }

    /**
     * @param authorId 블로그 안 검색이면 그 블로그 주인 (FR-003). 본인이 봐도 공개 글만이다
     */
    public PostPage posts(String rawQuery, Sort sort, String cursor, Long authorId) {
        SearchTerms terms = SearchTerms.parse(rawQuery);
        if (terms.isEmpty()) return new PostPage(terms.normalized(), List.of(), null, "TOO_SHORT");
        String notice = terms.hasShortWord() ? "TWO_CHAR_TITLE_TAG_ONLY" : null;
        String listName = "search:" + (sort == Sort.LATEST ? "l" : "r") + ":" + (authorId == null ? "all" : authorId) + ":" + terms.fingerprint();
        List<Integer> stages = stages(sort, terms);

        long[] k = decodeCursor(cursor, listName);
        if (sort == Sort.RELEVANCE && (k.length == 0 || k[0] == HYBRID_STAGE)) {
            Optional<PostPage> hybrid = hybrid(terms, stages, authorId, k.length == 0 ? 0 : (int) k[1], listName, notice);
            if (hybrid.isPresent()) return hybrid.get();
            if (k.length > 0) throw invalidCursor();
        }
        return keywordPage(terms, stages, authorId, k, listName, notice, sort == Sort.RELEVANCE);
    }

    private static List<Integer> stages(Sort sort, SearchTerms terms) {
        if (sort == Sort.LATEST) return List.of(LATEST_STAGE);
        if (terms.hasBodyWord()) return List.of(1, 2, 3);
        return List.of(1, 2);
    }

    private long[] decodeCursor(String cursor, String listName) {
        return cursor == null || cursor.isBlank() ? new long[0] : cursors.decode(cursor, listName, 3);
    }

    /** 키워드 단계 순서대로 채운 한 페이지. k는 이어 볼 위치(비었으면 처음) */
    private PostPage keywordPage(SearchTerms terms, List<Integer> stages, Long authorId, long[] k, String listName, String notice,
                                 boolean relevance) {
        int fromStage = stages.get(0);
        Instant cursorAt = null;
        Long cursorId = null;
        if (k.length > 0) {
            if (!stages.contains((int) k[0])) throw invalidCursor();
            fromStage = (int) k[0];
            cursorAt = Times.fromEpochMicros(k[1]);
            cursorId = k[2];
        }

        // 하이브리드 목록 뒤를 잇는 페이지면, 섞은 목록에서 이미 보여 준 의미 검색 글은 다시 내지 않는다
        Set<Long> shown = relevance && k.length > 0
                ? new HashSet<>(semantic.nearest(terms.normalized(), authorId).orElse(List.of())) : Set.of();

        List<Found> found = fillStages(terms, stages, authorId, fromStage, cursorAt, cursorId, shown);
        CursorCodec.Page<Found> page = CursorCodec.page(found, pageSize,
                f -> cursors.encode(listName, f.stage(), Times.toEpochMicros(f.at()), f.id()));
        return new PostPage(terms.normalized(), hits(page.items(), terms, null), page.nextCursor(), notice);
    }

    /** 9개 + 다음 페이지 확인용 1개. 한 단계가 모자라면 같은 페이지에서 다음 단계로 이어 채운다 (FR-013) */
    private List<Found> fillStages(SearchTerms terms, List<Integer> stages, Long authorId, int fromStage, Instant cursorAt, Long cursorId,
                                   Set<Long> shown) {
        List<Found> found = new ArrayList<>();
        for (int stage : stages.stream().filter(s -> s >= fromStage).toList()) {
            int need = pageSize + 1 - found.size();
            if (need <= 0) break;
            boolean continuing = stage == fromStage && cursorAt != null;
            List<Found> got = new ArrayList<>(findStage(terms, stage, authorId, continuing ? cursorAt : null,
                    continuing ? cursorId : null, need + shown.size()));
            got.removeIf(f -> shown.contains(f.id()));
            found.addAll(got.subList(0, Math.min(need, got.size())));
        }
        return found;
    }

    private static ApiException invalidCursor() {
        return ApiException.badRequest("INVALID_CURSOR", "목록 위치 값이 올바르지 않아요. 처음부터 다시 불러와 주세요.");
    }

    /**
     * 하이브리드 한 페이지 (054). 페이지마다 키워드 앞쪽과 의미 검색 결과를 다시 구해 섞는다(검색어 벡터는 보관돼 있어 빠르다).
     * @param offset 섞은 목록 안 위치
     * @return 의미 검색을 쓸 수 없으면 비어 있다 (부르는 쪽이 키워드 결과로 답한다)
     */
    private Optional<PostPage> hybrid(SearchTerms terms, List<Integer> stages, Long authorId, int offset, String listName, String notice) {
        Optional<List<Long>> near = semantic.nearest(terms.normalized(), authorId);
        if (near.isEmpty()) return Optional.empty();
        int keywordLimit = semanticProps.keywordCandidates();
        // 섞을 앞쪽 + 그 뒤에 이어질 글이 있는지 볼 만큼. 뒤쪽 글이 모두 의미 검색 결과에 이미 있으면 이어질 글이 없다
        int fetch = keywordLimit + 1 + near.get().size();
        List<Found> keyword = new ArrayList<>();
        for (int stage : stages) {
            int need = fetch - keyword.size();
            if (need <= 0) break;
            keyword.addAll(findStage(terms, stage, authorId, null, null, need));
        }
        Set<Long> nearIds = new HashSet<>(near.get());
        boolean keywordOverflow = keyword.stream().skip(keywordLimit).anyMatch(f -> !nearIds.contains(f.id()));
        if (keyword.size() > keywordLimit) keyword = keyword.subList(0, keywordLimit);
        List<Long> keywordIds = keyword.stream().map(Found::id).toList();
        List<Long> fused = fuse(keywordIds, near.get(), semanticProps.rrfK(), semanticProps.semanticWeight());
        if (offset < 0 || offset > fused.size()) throw invalidCursor();

        int end = Math.min(offset + pageSize, fused.size());
        List<Found> page = new ArrayList<>();
        for (Long id : fused.subList(offset, end)) page.add(new Found(HYBRID_STAGE, id, null));
        String next = null;
        if (end < fused.size()) {
            next = cursors.encode(listName, HYBRID_STAGE, end, 0);
        } else if (keywordOverflow) {
            // 섞은 목록 뒤로는 키워드 결과가 키워드 순서대로 이어진다. 의미 검색에만 나온 글은 키워드 결과에 다시 나오지 않는다
            Found last = keyword.get(keyword.size() - 1);
            next = cursors.encode(listName, last.stage(), Times.toEpochMicros(last.at()), last.id());
        }
        return Optional.of(new PostPage(terms.normalized(), hits(page, terms, new HashSet<>(keywordIds)), next, notice));
    }

    /**
     * 순위 섞기 (Reciprocal Rank Fusion). 글마다 1/(k + 키워드 순위) + 무게 × 1/(k + 의미 순위)를 더해 큰 순서로 놓는다.
     * 점수가 같으면 키워드 순위가 앞선 글, 그다음 의미 순위가 앞선 글이 먼저다.
     */
    static List<Long> fuse(List<Long> keyword, List<Long> semantic, int k, double semanticWeight) {
        Map<Long, double[]> score = new LinkedHashMap<>();
        for (int i = 0; i < keyword.size(); i++) {
            score.computeIfAbsent(keyword.get(i), id -> new double[] {0, Integer.MAX_VALUE, Integer.MAX_VALUE});
            double[] s = score.get(keyword.get(i));
            s[0] += 1.0 / (k + i + 1);
            s[1] = Math.min(s[1], i);
        }
        for (int i = 0; i < semantic.size(); i++) {
            score.computeIfAbsent(semantic.get(i), id -> new double[] {0, Integer.MAX_VALUE, Integer.MAX_VALUE});
            double[] s = score.get(semantic.get(i));
            s[0] += semanticWeight / (k + i + 1);
            s[2] = Math.min(s[2], i);
        }
        return score.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, double[]>>comparingDouble(e -> -e.getValue()[0])
                        .thenComparingDouble(e -> e.getValue()[1])
                        .thenComparingDouble(e -> e.getValue()[2]))
                .map(Map.Entry::getKey).toList();
    }

    /** 사람 검색 (FR-004): 탈퇴 신청 제외, 닉네임·주소가 정확히 같으면 먼저, 최대 20명. */
    public PeoplePage people(String rawQuery) {
        String q = SearchTerms.parse(rawQuery).normalized();
        if (q.startsWith("@")) q = q.substring(1).strip();
        if (SearchTerms.length(q) < 2) return new PeoplePage(q, List.of(), "TOO_SHORT");
        String like = SearchTerms.likePattern(q);
        List<Person> people = jdbc.query("SELECT m.id, m.handle, m.nickname, m.bio," + PostSql.PROFILE_IMAGE_KEY + """

                FROM member m
                """ + PostSql.PROFILE_IMAGE_JOIN + """

                WHERE m.withdrawn_at IS NULL AND m.deleted_at IS NULL AND (m.nickname ILIKE ? OR m.handle ILIKE ?)
                ORDER BY (lower(m.nickname) = lower(?) OR m.handle = lower(?)) DESC, m.nickname, m.id
                LIMIT ?
                """, (rs, i) -> new Person(rs.getLong("id"), rs.getString("handle"), rs.getString("nickname"),
                BioText.firstLine(rs.getString("bio")), imageUrls.urlOf(rs.getString("profile_image_key"))),
                like, like, q, q, PEOPLE_LIMIT);
        return new PeoplePage(q, people, null);
    }

    private List<Found> findStage(SearchTerms terms, int stage, Long authorId, Instant cursorAt, Long cursorId, int need) {
        Sql condition = stageCondition(terms, stage);
        List<Object> scope = new ArrayList<>();
        StringBuilder scopeSql = new StringBuilder(PostAccessPolicy.PUBLIC_LIST_CONDITION);
        if (authorId != null) {
            scopeSql.append(" AND p.author_id = ?");
            scope.add(authorId);
        }
        if (cursorAt != null) {
            scopeSql.append(" AND (p.first_public_at, p.id) < (?, ?)");
            scope.add(Timestamp.from(cursorAt));
            scope.add(cursorId);
        }

        // ① 최근창: 커서 이후 최근 공개 글 N개 안에서
        List<Object> args = new ArrayList<>(scope);
        args.add(recentWindow);
        args.addAll(condition.args());
        args.add(need);
        List<Found> recent = jdbc.query("""
                SELECT p.id, p.first_public_at FROM (
                    SELECT p.id FROM post p JOIN member m ON m.id = p.author_id
                    WHERE\s""" + scopeSql + """

                    ORDER BY p.first_public_at DESC, p.id DESC LIMIT ?) w
                JOIN post p ON p.id = w.id
                WHERE\s""" + condition.text() + """

                ORDER BY p.first_public_at DESC, p.id DESC LIMIT ?
                """, (rs, i) -> new Found(stage, rs.getLong("id"), rs.getTimestamp(FIRST_PUBLIC_AT).toInstant()), args.toArray());
        if (recent.size() >= need) return recent;

        // ② 모자라면 가장 긴 단어의 인덱스 후보에서 전체를 찾는다. 최근창 결과는 이 결과에 모두 들어 있다
        String word = terms.longest();
        String pattern = SearchTerms.likePattern(word);
        List<Object> all = new ArrayList<>();
        StringBuilder candidates = new StringBuilder("SELECT id FROM post WHERE title ILIKE ?");
        all.add(pattern);
        if (stage != 1) {
            candidates.append(" UNION SELECT pt.post_id FROM post_tag pt JOIN tag g ON g.id = pt.tag_id WHERE g.name ILIKE ?");
            all.add(pattern);
        }
        if ((stage == 3 || stage == LATEST_STAGE) && SearchTerms.searchesBody(word)) {
            candidates.append(" UNION SELECT id FROM post WHERE content_md ILIKE ?");
            all.add(pattern);
        }
        all.addAll(scope);
        all.addAll(condition.args());
        all.add(need);
        return jdbc.query("SELECT p.id, p.first_public_at FROM post p JOIN member m ON m.id = p.author_id WHERE p.id IN ("
                + candidates + ") AND " + scopeSql + " AND " + condition.text()
                + " ORDER BY p.first_public_at DESC, p.id DESC LIMIT ?",
                (rs, i) -> new Found(stage, rs.getLong("id"), rs.getTimestamp(FIRST_PUBLIC_AT).toInstant()), all.toArray());
    }

    /**
     * 단계 조건 (FR-012). ① 모든 단어가 제목에 → ② ①이 아니고 모든 단어가 제목 또는 태그에 → ③ ②까지가 아니고 모든 단어가
     * 제목·태그·본문(3글자 이상 단어만) 어딘가에. 최신순(0)은 "어딘가에"만.
     */
    private Sql stageCondition(SearchTerms terms, int stage) {
        List<Sql> inTitle = new ArrayList<>();
        List<Sql> inTitleOrTag = new ArrayList<>();
        List<Sql> anywhere = new ArrayList<>();
        for (String w : terms.words()) {
            String p = SearchTerms.likePattern(w);
            Sql title = new Sql(TITLE, List.of(p));
            Sql tag = new Sql(TAG, List.of(p.toLowerCase()));
            inTitle.add(title);
            inTitleOrTag.add(Sql.or(List.of(title, tag)));
            anywhere.add(SearchTerms.searchesBody(w) ? Sql.or(List.of(title, tag, new Sql(BODY, List.of(p)))) : Sql.or(List.of(title, tag)));
        }
        return switch (stage) {
            case 1 -> Sql.and(inTitle);
            case 2 -> Sql.and(inTitleOrTag).andNot(Sql.and(inTitle));
            case 3 -> Sql.and(anywhere).andNot(Sql.and(inTitleOrTag));
            default -> Sql.and(anywhere);
        };
    }

    /** 찾은 글 번호들의 결과 카드 */
    private static final String HITS = """
            SELECT p.id, p.title, p.content_md, p.first_public_at, s.comment_count, s.like_count,
                   m.id AS author_id, m.handle, m.nickname, """ + PostSql.THUMBNAIL_KEY + ", " + PostSql.PROFILE_IMAGE_KEY + """

            FROM post p JOIN member m ON m.id = p.author_id
            """ + PostSql.STAT_JOIN + " " + PostSql.PROFILE_IMAGE_JOIN + """

            WHERE p.id = ANY (?) AND\s""" + PostAccessPolicy.PUBLIC_LIST_CONDITION;

    /** @param keywordIds 하이브리드에서 키워드로도 찾은 글. null이면 키워드 검색이라 모두 키워드 결과다 */
    private List<Hit> hits(List<Found> found, SearchTerms terms, Set<Long> keywordIds) {
        if (found.isEmpty()) return List.of();
        Long[] ids = found.stream().map(Found::id).toArray(Long[]::new);
        Pattern words = terms.highlightPattern();
        Map<Long, Hit> byId = new HashMap<>();
        jdbc.query(HITS, ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)), rs -> {
            long id = rs.getLong("id");
            String handle = rs.getString("handle");
            String title = rs.getString("title");
            byId.put(id, new Hit(id, "/@" + handle + "/posts/" + id, title,
                    Snippet.of(renderer.searchText(rs.getString("content_md")), title, words),
                    imageUrls.urlOf(rs.getString("thumbnail_key")), rs.getTimestamp(FIRST_PUBLIC_AT).toInstant(),
                    rs.getInt("comment_count"), rs.getInt("like_count"),
                    new Author(rs.getLong("author_id"), handle, rs.getString("nickname"), imageUrls.urlOf(rs.getString("profile_image_key"))),
                    keywordIds != null && !keywordIds.contains(id)));
        });
        // 두 쿼리 사이에 비공개·휴지통으로 바뀐 글은 빠진다. 순서는 찾은 순서 그대로
        return found.stream().map(f -> byId.get(f.id())).filter(java.util.Objects::nonNull).toList();
    }
}
