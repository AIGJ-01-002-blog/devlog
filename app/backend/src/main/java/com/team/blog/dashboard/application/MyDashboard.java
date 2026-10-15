package com.team.blog.dashboard.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.team.blog.comment.application.CommentStats;
import com.team.blog.like.application.LikeStats;
import com.team.blog.post.query.PostStats;
import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.view.application.ViewStats;

/**
 * 회원 본인 대시보드 (#239). 관리자 통계(AdminStats)처럼 SQL 없이 각 모듈의 집계를 모아 화면 한 장으로 조립한다.
 * 대상은 내가 발행한 글 전부(비공개 포함)이고, 받은 댓글에서 내가 단 댓글은 뺀다. 날짜는 한국 시각, 기간은 오늘을 포함한 최근 N일이다.
 */
@Service
public class MyDashboard {
    public static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    static final int TOP = 5;

    private final PostStats posts;
    private final ViewStats views;
    private final LikeStats likes;
    private final CommentStats comments;
    private final ShortsService shorts;
    private final Clock clock;

    public MyDashboard(PostStats posts, ViewStats views, LikeStats likes, CommentStats comments, ShortsService shorts, Clock clock) {
        this.posts = posts;
        this.views = views;
        this.likes = likes;
        this.comments = comments;
        this.shorts = shorts;
        this.clock = clock;
    }

    public record Sums(long views, long likes, long comments) {}

    public record Day(LocalDate date, long views, long likes, long comments) {}

    /** @param views 기간 안 조회수, likes·comments는 지금까지 받은 수 */
    public record PostLine(long id, String title, String visibility, long views, long likes, long comments, String link) {}

    /** @param shorts 쇼츠가 꺼져 있으면 null */
    public record Dashboard(int days, LocalDate from, LocalDate to, long posts, Sums totals, Sums current, Sums previous, List<Day> daily,
                            List<PostLine> topPosts, ShortsService.Summary shorts) {}

    public Dashboard of(long memberId, int days) {
        int n = PERIODS.contains(days) ? days : 30;
        LocalDate today = LocalDate.ofInstant(Times.now(clock), Counts.KST);
        LocalDate from = today.minusDays(n - 1L);
        LocalDate prevFrom = from.minusDays(n);
        Instant since = prevFrom.atStartOfDay(Counts.KST).toInstant();

        List<Long> ids = posts.publishedIdsOf(memberId);
        Map<LocalDate, Long> viewed = views.byDayOfPosts(ids, since);
        Map<LocalDate, Long> liked = likes.byDayOfPosts(ids, since);
        Map<LocalDate, Long> commented = comments.byDayOfPosts(ids, since, memberId);

        List<Day> daily = new ArrayList<>(n);
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new Day(d, get(viewed, d), get(liked, d), get(commented, d)));
        }
        Sums current = sum(from, today, viewed, liked, commented);
        Sums previous = sum(prevFrom, from.minusDays(1), viewed, liked, commented);
        Sums totals = new Sums(total(views.byPosts(ids)), total(likes.byPosts(ids)), total(comments.byPosts(ids)));
        return new Dashboard(n, from, today, ids.size(), totals, current, previous, daily,
                topPosts(memberId, ids, from.atStartOfDay(Counts.KST).toInstant()), shorts.summary(memberId).orElse(null));
    }

    /** 기간 안에 많이 읽힌 내 글 */
    private List<PostLine> topPosts(long memberId, List<Long> ids, Instant from) {
        List<Map.Entry<Long, Long>> top = views.topOfPosts(ids, from, TOP);
        List<Long> topIds = top.stream().map(Map.Entry::getKey).toList();
        Map<Long, PostStats.Row> rows = posts.ownByIds(memberId, topIds);
        Map<Long, Long> likeCounts = likes.byPosts(topIds);
        Map<Long, Long> commentCounts = comments.byPosts(topIds);
        return top.stream().filter(e -> rows.containsKey(e.getKey())).map(e -> {
            PostStats.Row r = rows.get(e.getKey());
            return new PostLine(r.id(), r.title(), r.visibility(), e.getValue(), likeCounts.getOrDefault(r.id(), 0L),
                    commentCounts.getOrDefault(r.id(), 0L), "/@" + r.authorHandle() + "/posts/" + r.id());
        }).toList();
    }

    private static Sums sum(LocalDate from, LocalDate to, Map<LocalDate, Long> viewed, Map<LocalDate, Long> liked, Map<LocalDate, Long> commented) {
        long v = 0;
        long l = 0;
        long c = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            v += get(viewed, d);
            l += get(liked, d);
            c += get(commented, d);
        }
        return new Sums(v, l, c);
    }

    private static long total(Map<Long, Long> byPost) {
        return byPost.values().stream().mapToLong(Long::longValue).sum();
    }

    private static long get(Map<LocalDate, Long> m, LocalDate d) {
        return m.getOrDefault(d, 0L);
    }
}
