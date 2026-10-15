package com.team.blog.topic.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.team.blog.post.access.Viewer;
import com.team.blog.series.application.SeriesProjects;
import com.team.blog.series.application.SeriesQuery;
import com.team.blog.tag.application.TagNormalizer;
import com.team.blog.tag.application.TagQuery;

/**
 * 발행 창 브랜치 추천 (072 2단계). 발행하려는 글의 태그와 가장 많이 겹치는 내 시리즈·주제 브랜치를 고른다.
 * 겹침 = 함께 쓴 태그의 희소도 합 ÷ 이 글 태그의 희소도 합. 모두가 쓰는 태그 하나만 겹쳐서는 추천하지 않는다.
 */
@Service
public class BranchSuggester {
    private final SeriesQuery series;
    private final TopicQuery topics;
    private final TagQuery tags;
    private final TopicProperties props;
    private final SeriesProjects projects;

    public BranchSuggester(SeriesQuery series, TopicQuery topics, TagQuery tags, TopicProperties props, SeriesProjects projects) {
        this.projects = projects;
        this.series = series;
        this.topics = topics;
        this.tags = tags;
        this.props = props;
    }

    /** @param kind SERIES·TOPIC @param shared 함께 쓴 태그 */
    public record Suggestion(String kind, String key, Long seriesId, String name, String url, List<String> shared) {}

    /**
     * @param current 이미 들어 있는 브랜치(시리즈 또는 지난 계산의 주제)
     * @param optedOut 작성자가 [묶지 않기]를 골랐다
     * @param portfolio 지금 시리즈가 포트폴리오 프로젝트라 이 글도 포트폴리오에 보인다 (3단계)
     */
    public record Result(Suggestion current, Suggestion series, Suggestion topic, boolean optedOut, boolean portfolio) {
        Result(Suggestion current, Suggestion series, Suggestion topic, boolean optedOut) {
            this(current, series, topic, optedOut, false);
        }
    }

    public Result suggest(long memberId, long postId, Collection<String> rawTags) {
        Set<String> mine = new LinkedHashSet<>();
        for (String raw : rawTags == null ? List.<String>of() : rawTags) TagNormalizer.canonical(raw).ifPresent(mine::add);
        boolean optedOut = topics.optedOut(postId);

        var inSeries = series.forPost(postId, new Viewer(memberId, false));
        if (inSeries.isPresent()) {
            var n = inSeries.get();
            return new Result(new Suggestion("SERIES", "s" + n.id(), n.id(), n.name(), "/@" + n.handle() + "/series/" + n.slug(), List.of()),
                    null, null, optedOut, projects.postInProject(postId).orElse(false));
        }
        Suggestion current = topics.forPost(postId)
                .map(n -> new Suggestion("TOPIC", n.key(), null, n.name(), n.url(), List.of())).orElse(null);
        if (mine.isEmpty()) return new Result(current, null, null, optedOut);

        Map<String, Long> usage = tags.publicUsage(mine);
        double total = tags.publicPostTotal();
        Scorer scorer = new Scorer(mine, usage, total);

        Suggestion bestSeries = bestSeries(memberId, scorer);
        Suggestion bestTopic = current == null && !optedOut ? bestTopic(scorer) : null;
        return new Result(current, bestSeries, bestTopic, optedOut);
    }

    private Suggestion bestSeries(long memberId, Scorer scorer) {
        Suggestion best = null;
        double bestScore = props.suggestMinScore();
        for (SeriesQuery.TagProfile p : series.tagProfiles(memberId)) {
            double s = scorer.score(p.tags());
            if (s >= bestScore && (best == null || s > bestScore)) {
                bestScore = s;
                best = new Suggestion("SERIES", "s" + p.id(), p.id(), p.name(), null, scorer.shared(p.tags()));
            }
        }
        return best;
    }

    private Suggestion bestTopic(Scorer scorer) {
        Suggestion best = null;
        double bestScore = props.suggestMinScore();
        for (TopicQuery.TagProfile p : topics.tagProfiles()) {
            if (p.postCount() >= props.maxSize()) continue; // 가득 찬 브랜치에는 더 붙지 않는다
            double s = scorer.score(p.tags());
            if (s >= bestScore && (best == null || s > bestScore)) {
                bestScore = s;
                best = new Suggestion("TOPIC", "t" + p.key(), null, p.name(), "/?branch=t" + p.key(), scorer.shared(p.tags()));
            }
        }
        return best;
    }

    /** 희소도 = ln((공개 글 수 + 1) ÷ (그 태그를 쓴 공개 글 수 + 1)) + 1. 처음 쓰는 태그가 가장 무겁다 */
    record Scorer(Set<String> mine, Map<String, Long> usage, double total) {
        double weight(String tag) {
            return Math.log((total + 1) / (usage.getOrDefault(tag, 0L) + 1)) + 1;
        }

        double score(List<String> other) {
            double all = 0;
            double hit = 0;
            for (String t : mine) {
                all += weight(t);
                if (other.contains(t)) hit += weight(t);
            }
            return all == 0 ? 0 : hit / all;
        }

        List<String> shared(List<String> other) {
            List<String> out = new ArrayList<>();
            for (String t : mine) if (other.contains(t)) out.add(t);
            return out;
        }
    }
}
