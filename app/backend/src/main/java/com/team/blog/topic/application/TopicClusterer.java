package com.team.blog.topic.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 주제 브랜치 묶기 (072). DB를 모르는 순수 계산이라 따로 시험한다.
 * <p>
 * 비슷한 쌍(태그·임베딩)을 비슷한 순서대로 이어 붙이되(크러스컬), 한 묶음이 maxSize를 넘게 되는 쌍은 건너뛴다.
 * 그래서 "다들 쓰는 주제" 하나로 모든 글이 이어지지 않고, 가장 가까운 글끼리 작은 브랜치가 생긴다.
 */
public final class TopicClusterer {
    /** 태그 후보 쌍을 만들 때 이보다 많은 글에 쓰인 태그는 건너뛴다(희소도가 낮아 어차피 묶는 힘이 약하다) */
    static final int MAX_TAG_FANOUT = 60;
    static final int NAME_MAX = 30;
    /** 이름으로 쓸 태그의 최소 희소도. ln 2 = 전체 글의 절반 이하가 쓰는 태그 */
    static final double NAME_MIN_IDF = Math.log(2);

    private TopicClusterer() {}

    public enum Method { EMBEDDING, TAG }

    /** @param tags 정규화한 태그 이름 */
    public record Doc(long id, String title, List<String> tags) {}

    /** @param similarity 0~1, 클수록 비슷하다 */
    public record Edge(long a, long b, double similarity, Method method) {}

    /** @param key 묶음에서 가장 작은 글 번호 @param method EMBEDDING·TAG·MIXED @param members 글 번호 오름차순 */
    public record Topic(long key, String name, String method, List<Long> members) {}

    /**
     * 태그가 비슷한 쌍. 유사도 = 겹치는 태그 희소도 합 ÷ 두 글 태그 전체 희소도 합 (희소도 = ln(전체 글 수 ÷ 그 태그를 쓴 글 수)).
     * 모든 글이 쓰는 태그는 희소도가 0이라 그것만 겹쳐서는 묶이지 않는다.
     */
    public static List<Edge> tagEdges(List<Doc> docs, double minSimilarity) {
        int n = docs.size();
        if (n < 2) return List.of();
        Map<String, List<Integer>> byTag = indexByTag(docs);
        Map<String, Double> idf = new HashMap<>();
        byTag.forEach((t, list) -> idf.put(t, Math.log((double) n / list.size())));
        Map<Long, Double> shared = new HashMap<>(); // i * n + j → 겹치는 희소도 합
        byTag.forEach((t, list) -> {
            double w = idf.get(t);
            if (w <= 0 || list.size() > MAX_TAG_FANOUT) return;
            addPairs(shared, list, w, n);
        });
        double[] total = new double[n];
        for (int i = 0; i < n; i++) {
            for (String t : Set.copyOf(docs.get(i).tags())) total[i] += idf.get(t);
        }
        List<Edge> out = new ArrayList<>();
        shared.forEach((pair, s) -> {
            int i = (int) (pair / n);
            int j = (int) (pair % n);
            double union = total[i] + total[j] - s;
            double sim = union <= 0 ? 0 : s / union;
            if (sim >= minSimilarity) out.add(new Edge(docs.get(i).id(), docs.get(j).id(), sim, Method.TAG));
        });
        return out;
    }

    /** 태그 → 그 태그를 쓴 글의 자리(docs 안 순번) */
    private static Map<String, List<Integer>> indexByTag(List<Doc> docs) {
        Map<String, List<Integer>> byTag = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            for (String t : Set.copyOf(docs.get(i).tags())) byTag.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        return byTag;
    }

    /** 같은 태그를 쓴 글 쌍마다 희소도 w를 더한다 */
    private static void addPairs(Map<Long, Double> shared, List<Integer> list, double w, int n) {
        for (int x = 0; x < list.size(); x++) {
            for (int y = x + 1; y < list.size(); y++) shared.merge((long) list.get(x) * n + list.get(y), w, Double::sum);
        }
    }

    public static List<Topic> cluster(List<Doc> docs, List<Edge> edges, int maxSize) {
        Map<Long, Doc> byId = new HashMap<>();
        docs.forEach(d -> byId.put(d.id(), d));
        // 같은 쌍은 가장 비슷한 값 하나로, 근거는 모두 남긴다
        Map<String, Edge> best = new HashMap<>();
        Map<String, EnumSet<Method>> methods = new HashMap<>();
        for (Edge e : edges) {
            if (e.a() == e.b() || !byId.containsKey(e.a()) || !byId.containsKey(e.b())) continue;
            String k = Math.min(e.a(), e.b()) + ":" + Math.max(e.a(), e.b());
            best.merge(k, e, (x, y) -> x.similarity() >= y.similarity() ? x : y);
            methods.computeIfAbsent(k, x -> EnumSet.noneOf(Method.class)).add(e.method());
        }
        List<Map.Entry<String, Edge>> sorted = new ArrayList<>(best.entrySet());
        // 같은 유사도면 번호 순으로 정해 매번 같은 결과가 나온다
        sorted.sort(Comparator.comparingDouble((Map.Entry<String, Edge> x) -> -x.getValue().similarity())
                .thenComparing(Map.Entry::getKey));

        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> size = new HashMap<>();
        Map<Long, EnumSet<Method>> used = new HashMap<>();
        union(sorted, methods, maxSize, parent, size, used);

        Map<Long, List<Long>> groups = new TreeMap<>();
        for (Doc d : docs) {
            if (!parent.containsKey(d.id()) && !size.containsKey(d.id())) continue;
            groups.computeIfAbsent(find(parent, d.id()), k -> new ArrayList<>()).add(d.id());
        }
        return nameTopics(docs, byId, groups, used);
    }

    /** 비슷한 쌍부터 이어 붙인다. 이미 같은 브랜치이거나 합치면 maxSize를 넘는 쌍은 건너뛴다 */
    private static void union(List<Map.Entry<String, Edge>> sorted, Map<String, EnumSet<Method>> methods, int maxSize,
            Map<Long, Long> parent, Map<Long, Integer> size, Map<Long, EnumSet<Method>> used) {
        for (Map.Entry<String, Edge> x : sorted) {
            long ra = find(parent, x.getValue().a());
            long rb = find(parent, x.getValue().b());
            int sa = size.getOrDefault(ra, 1);
            int sb = size.getOrDefault(rb, 1);
            // 이미 같은 브랜치이거나 합치면 너무 커지면 넘어간다
            if (ra == rb || sa + sb > maxSize) continue;
            long root = Math.min(ra, rb);
            long child = Math.max(ra, rb);
            parent.put(child, root);
            size.put(root, sa + sb);
            EnumSet<Method> m = used.computeIfAbsent(root, k -> EnumSet.noneOf(Method.class));
            m.addAll(methods.get(x.getKey()));
            EnumSet<Method> mc = used.remove(child);
            if (mc != null) m.addAll(mc);
        }
    }

    /** 두 글 이상인 묶음마다 이름과 근거를 붙인다 */
    private static List<Topic> nameTopics(List<Doc> docs, Map<Long, Doc> byId, Map<Long, List<Long>> groups,
            Map<Long, EnumSet<Method>> used) {
        // 큰 브랜치부터 이름을 정하고, 앞에서 쓴 이름은 피한다(모두가 쓰는 태그로 이름이 겹치지 않게)
        List<Map.Entry<Long, List<Long>>> ordered = new ArrayList<>();
        groups.forEach((root, members) -> {
            if (members.size() >= 2) ordered.add(Map.entry(root, members));
        });
        ordered.sort(Comparator.comparingInt((Map.Entry<Long, List<Long>> e) -> -e.getValue().size()).thenComparing(Map.Entry::getKey));
        Map<String, Double> idf = idf(docs);
        Set<String> taken = new HashSet<>();
        List<Topic> topics = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : ordered) {
            List<Long> members = e.getValue();
            members.sort(Long::compare);
            EnumSet<Method> m = used.getOrDefault(e.getKey(), EnumSet.of(Method.TAG));
            String method = m.size() > 1 ? "MIXED" : m.iterator().next().name();
            String name = name(members, byId, idf, taken);
            taken.add(name);
            topics.add(new Topic(members.get(0), name, method, List.copyOf(members)));
        }
        topics.sort(Comparator.comparingLong(Topic::key));
        return topics;
    }

    /**
     * 두 글 이상이 쓴 태그 중 (쓴 글 수 × 희소도)가 가장 큰 것(같으면 가나다순 앞). 전체 글의 절반 넘게 쓰는 태그(devlog처럼
     * 거의 모든 글에 붙는 것)는 브랜치를 설명하지 못해 이름으로 쓰지 않는다. 다른 브랜치가 이미 쓴 이름도 건너뛴다.
     * 쓸 태그가 없으면 가장 오래된 글 제목 앞부분이다.
     */
    static String name(List<Long> members, Map<Long, Doc> byId, Map<String, Double> idf, Set<String> taken) {
        Map<String, Integer> count = new TreeMap<>();
        for (long id : members) {
            for (String t : Set.copyOf(byId.get(id).tags())) count.merge(t, 1, Integer::sum);
        }
        String top = null;
        double topScore = 0;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            double w = idf.getOrDefault(e.getKey(), 0.0);
            double score = e.getValue() * w;
            if (e.getValue() >= 2 && w >= NAME_MIN_IDF && score > topScore && !taken.contains(e.getKey())) {
                top = e.getKey();
                topScore = score;
            }
        }
        if (top != null) return top;
        String title = byId.get(members.get(0)).title().strip();
        if (title.codePointCount(0, title.length()) <= NAME_MAX) return title.isEmpty() ? "비슷한 글" : title;
        return title.substring(0, title.offsetByCodePoints(0, NAME_MAX)).strip() + "…";
    }

    /** 태그 희소도 ln(전체 글 수 ÷ 그 태그를 쓴 글 수) */
    static Map<String, Double> idf(List<Doc> docs) {
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) {
            for (String t : Set.copyOf(d.tags())) df.merge(t, 1, Integer::sum);
        }
        Map<String, Double> out = new HashMap<>();
        df.forEach((t, c) -> out.put(t, Math.log((double) docs.size() / c)));
        return out;
    }

    private static long find(Map<Long, Long> parent, long x) {
        long r = x;
        while (parent.containsKey(r)) r = parent.get(r);
        // 경로 줄이기
        long c = x;
        while (parent.containsKey(c)) {
            long next = parent.get(c);
            if (next != r) parent.put(c, r);
            c = next;
        }
        return r;
    }
}
