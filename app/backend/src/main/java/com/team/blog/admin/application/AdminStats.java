package com.team.blog.admin.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.team.blog.account.application.MemberStats;
import com.team.blog.comment.application.CommentStats;
import com.team.blog.shorts.application.RenderWorkerStatus;
import com.team.blog.inquiry.application.InquiryService;
import com.team.blog.like.application.LikeStats;
import com.team.blog.moderation.application.AdminReportQuery;
import com.team.blog.post.query.PostStats;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.stats.Counts;
import com.team.blog.shared.time.Times;
import com.team.blog.view.application.ViewStats;

/**
 * 관리자 페이지 통계 (062). SQL 없이 각 모듈의 집계 서비스를 모아 화면 한 장 분량으로 조립한다(헌법 IV).
 * 날짜는 한국 시각 기준이고, 기간은 오늘을 포함한 최근 N일이다.
 */
@Service
public class AdminStats {
    public static final Set<Integer> PERIODS = Set.of(7, 30, 90);
    static final int TOP = 5;
    static final int TOP_PAGES = 10;

    private final MemberStats members;
    private final PostStats posts;
    private final CommentStats comments;
    private final LikeStats likes;
    private final ViewStats views;
    private final AdminReportQuery reports;
    private final InquiryService inquiries;
    private final RenderWorkerStatus worker;
    private final Clock clock;

    public AdminStats(MemberStats members, PostStats posts, CommentStats comments, LikeStats likes, ViewStats views,
                      AdminReportQuery reports, InquiryService inquiries, RenderWorkerStatus worker, Clock clock) {
        this.members = members;
        this.posts = posts;
        this.comments = comments;
        this.likes = likes;
        this.views = views;
        this.reports = reports;
        this.inquiries = inquiries;
        this.worker = worker;
        this.clock = clock;
    }

    public record Totals(MemberStats.Summary members, PostStats.Summary posts, long comments, long likes, long views, long pendingReports,
                         long openInquiries) {}

    /** 기간 합계. activeMembers는 기간 동안 하루라도 활동한 회원 수(066) */
    /** visitors는 기간 동안 온 사람 수(여러 날 와도 한 명), visits는 들어온 횟수 합 (064) */
    /** searchVisits는 검색(구글·네이버·다음·빙)으로 온 방문 수 (070) */
    public record Sums(long signups, long posts, long comments, long likes, long views, long reports, long visitors, long visits,
                       long activeMembers, long searchVisits) {}

    public record Day(LocalDate date, long signups, long posts, long comments, long likes, long views, long reports, long visitors,
                      long visits, long activeMembers, long searchVisits) {}

    /** 많이 본 화면 (070). title은 글 화면이고 공개 글일 때만 */
    public record PageLine(String path, long views, String title) {}

    /** 오늘·어제 순방문자와, 기간 방문자 중 회원 수 (064) */
    public record VisitorSummary(long today, long yesterday, long members) {}

    /** @param title 공개 글만 (비공개면 null) */
    public record PostLine(long id, String title, String authorHandle, String visibility, boolean hidden, long views, long likes,
                           long comments, String link) {}

    public record AuthorLine(String handle, String nickname, long posts) {}

    /** @param worker 쇼츠 렌더 워커 상태 (#241). 쇼츠가 꺼져 있으면 null */
    public record Dashboard(int days, LocalDate from, LocalDate to, Totals totals, Sums current, Sums previous, long activeMembers,
                            VisitorSummary visitors, List<Day> daily, List<PostLine> topPosts, List<AuthorLine> topAuthors,
                            List<ViewStats.SourceLine> sources, List<PageLine> topPages, RenderWorkerStatus.Status worker) {}

    public Dashboard dashboard(int days) {
        int n = PERIODS.contains(days) ? days : 30;
        Instant now = Times.now(clock);
        LocalDate today = LocalDate.ofInstant(now, Counts.KST);
        LocalDate from = today.minusDays(n - 1L);
        LocalDate prevFrom = from.minusDays(n);
        Instant since = prevFrom.atStartOfDay(Counts.KST).toInstant();

        Map<LocalDate, Long> signups = members.signupsByDay(since);
        Map<LocalDate, Long> published = posts.publishedByDay(since);
        Map<LocalDate, Long> commented = comments.byDay(since);
        Map<LocalDate, Long> liked = likes.byDay(since);
        Map<LocalDate, Long> viewed = views.byDay(since);
        Map<LocalDate, Long> reported = reports.reportsByDay(since);
        Map<LocalDate, Long> visitorsByDay = views.visitorsByDay(since);
        Map<LocalDate, Long> visitsByDay = views.visitsByDay(since);
        Map<LocalDate, Long> activeByDay = members.activeByDay(since);
        Map<LocalDate, Long> searchByDay = views.searchVisitsByDay(since);

        List<Day> daily = new ArrayList<>(n);
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new Day(d, get(signups, d), get(published, d), get(commented, d), get(liked, d), get(viewed, d), get(reported, d),
                    get(visitorsByDay, d), get(visitsByDay, d), get(activeByDay, d), get(searchByDay, d)));
        }
        ViewStats.Visitors inPeriod = views.visitors(from, today);
        ViewStats.Visitors before = views.visitors(prevFrom, from.minusDays(1));
        LocalDate prevTo = from.minusDays(1);
        Sums current = sum(from, today, inPeriod, members.activeBetween(from, today),
                signups, published, commented, liked, viewed, reported, searchByDay);
        Sums previous = sum(prevFrom, prevTo, before, members.activeBetween(prevFrom, prevTo),
                signups, published, commented, liked, viewed, reported, searchByDay);
        VisitorSummary visitors = new VisitorSummary(get(visitorsByDay, today), get(visitorsByDay, today.minusDays(1)), inPeriod.members());

        Instant periodStart = from.atStartOfDay(Counts.KST).toInstant();
        PostStats.Summary postSummary = posts.summary();
        Totals totals = new Totals(members.summary(), postSummary, comments.total(), likes.total(), views.total(),
                reports.pendingCount(), inquiries.openCount());
        return new Dashboard(n, from, today, totals, current, previous, members.activeSince(periodStart), visitors, daily,
                topPosts(periodStart), topAuthors(periodStart), views.sources(from, today, TOP), topPages(from, today),
                worker.status().orElse(null));
    }

    private static final java.util.regex.Pattern POST_PAGE = java.util.regex.Pattern.compile("^/@[^/]+/posts/(\\d{1,18})$");

    /** 많이 본 화면. 글 화면에는 공개 글 제목을 붙인다(비공개·숨김·지운 글은 주소만) */
    private List<PageLine> topPages(LocalDate from, LocalDate to) {
        List<ViewStats.PageLine> top = views.topPages(from, to, TOP_PAGES);
        List<Long> ids = new ArrayList<>();
        for (ViewStats.PageLine p : top) {
            java.util.regex.Matcher m = POST_PAGE.matcher(p.path());
            if (m.matches()) ids.add(Long.parseLong(m.group(1)));
        }
        Map<Long, PostStats.Row> rows = ids.isEmpty() ? Map.of() : posts.byIds(ids);
        return top.stream().map(p -> {
            java.util.regex.Matcher m = POST_PAGE.matcher(p.path());
            PostStats.Row r = m.matches() ? rows.get(Long.parseLong(m.group(1))) : null;
            return new PageLine(p.path(), p.views(), r == null || r.hidden() ? null : r.title());
        }).toList();
    }

    /** 기간 안에 많이 본 공개 글. 비공개·숨김·휴지통 글은 뺀다 */
    private List<PostLine> topPosts(Instant from) {
        List<Map.Entry<Long, Long>> top = views.top(from, TOP * 4);
        List<Long> ids = top.stream().map(Map.Entry::getKey).toList();
        Map<Long, PostStats.Row> rows = posts.byIds(ids);
        List<Long> shown = ids.stream().filter(id -> {
            PostStats.Row r = rows.get(id);
            return r != null && r.title() != null && !r.hidden();
        }).limit(TOP).toList();
        Map<Long, Long> likeCounts = likes.byPosts(shown);
        Map<Long, Long> commentCounts = comments.byPosts(shown);
        Map<Long, Long> periodViews = top.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return shown.stream().map(id -> line(rows.get(id), periodViews.getOrDefault(id, 0L), likeCounts, commentCounts)).toList();
    }

    private List<AuthorLine> topAuthors(Instant from) {
        List<Map.Entry<Long, Long>> top = posts.topAuthors(from, TOP);
        Map<Long, MemberStats.Row> who = members.byIds(top.stream().map(Map.Entry::getKey).toList());
        return top.stream().filter(e -> who.containsKey(e.getKey()))
                .map(e -> new AuthorLine(who.get(e.getKey()).handle(), who.get(e.getKey()).nickname(), e.getValue())).toList();
    }

    // --- 회원 ---

    public record MemberLine(MemberStats.Row member, long posts, long drafts, long hiddenPosts, long comments) {}

    public record MemberPage(List<MemberLine> items, int page, long total, int pageSize) {}

    public MemberPage memberPage(String q, String role, String status, int page) {
        MemberStats.Page p = members.page(q, role, status, page);
        List<Long> ids = p.items().stream().map(MemberStats.Row::id).toList();
        Map<Long, PostStats.AuthorCounts> postCounts = posts.countsByAuthors(ids);
        Map<Long, Long> commentCounts = comments.byAuthors(ids);
        List<MemberLine> lines = p.items().stream().map(m -> {
            PostStats.AuthorCounts c = postCounts.getOrDefault(m.id(), new PostStats.AuthorCounts(0, 0, 0, 0));
            return new MemberLine(m, c.published(), c.drafts(), c.hidden(), commentCounts.getOrDefault(m.id(), 0L));
        }).toList();
        return new MemberPage(lines, p.page(), p.total(), p.pageSize());
    }

    public record Month(String month, long posts) {}

    /** 회원 한 명의 활동 통계. 받은 조회수·좋아요는 발행한 글 전체(비공개 포함) 기준 */
    public record MemberDetail(MemberStats.Row member, PostStats.AuthorCounts posts, long comments, long viewsReceived, long likesReceived,
                               long commentsReceived, List<Month> monthly, List<PostLine> topPosts) {}

    public MemberDetail memberDetail(String handle) {
        MemberStats.Row m = members.byHandle(handle).orElseThrow(NotFoundException::new);
        PostStats.AuthorCounts counts = posts.countsByAuthors(List.of(m.id())).getOrDefault(m.id(), new PostStats.AuthorCounts(0, 0, 0, 0));
        List<Long> ids = posts.publishedIdsOf(m.id());
        Map<Long, Long> viewCounts = views.byPosts(ids);
        Map<Long, Long> likeCounts = likes.byPosts(ids);
        Map<Long, Long> commentCounts = comments.byPosts(ids);

        YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(Times.now(clock), Counts.KST));
        YearMonth first = thisMonth.minusMonths(11);
        Map<String, Long> monthly = posts.monthlyOf(m.id(), first.atDay(1).atStartOfDay(Counts.KST).toInstant());
        List<Month> months = new ArrayList<>(12);
        for (YearMonth ym = first; !ym.isAfter(thisMonth); ym = ym.plusMonths(1)) {
            months.add(new Month(ym.toString(), monthly.getOrDefault(ym.toString(), 0L)));
        }

        List<Long> best = ids.stream().sorted(Comparator.comparing((Long id) -> viewCounts.getOrDefault(id, 0L)).reversed()
                .thenComparing(Comparator.reverseOrder())).limit(TOP).toList();
        Map<Long, PostStats.Row> rows = posts.byIds(best);
        List<PostLine> top = best.stream().filter(rows::containsKey)
                .map(id -> line(rows.get(id), viewCounts.getOrDefault(id, 0L), likeCounts, commentCounts)).toList();
        return new MemberDetail(m, counts, comments.byAuthors(List.of(m.id())).getOrDefault(m.id(), 0L), total(viewCounts),
                total(likeCounts), total(commentCounts), months, top);
    }

    // --- 글 ---

    public record PostPage(List<PostLine> items, int page, long total, int pageSize) {}

    public PostPage postPage(String q, String filter, String author, int page) {
        Long authorId = null;
        if (author != null && !author.isBlank()) {
            authorId = members.byHandle(author).map(MemberStats.Row::id).orElse(-1L);
        }
        PostStats.Page p = posts.page(q, filter, authorId, page);
        List<Long> ids = p.items().stream().map(PostStats.Row::id).toList();
        Map<Long, Long> viewCounts = views.byPosts(ids);
        Map<Long, Long> likeCounts = likes.byPosts(ids);
        Map<Long, Long> commentCounts = comments.byPosts(ids);
        List<PostLine> lines = p.items().stream().map(r -> line(r, viewCounts.getOrDefault(r.id(), 0L), likeCounts, commentCounts)).toList();
        return new PostPage(lines, p.page(), p.total(), p.pageSize());
    }

    private static PostLine line(PostStats.Row r, long viewCount, Map<Long, Long> likeCounts, Map<Long, Long> commentCounts) {
        return new PostLine(r.id(), r.title(), r.authorHandle(), r.visibility(), r.hidden(), viewCount, likeCounts.getOrDefault(r.id(), 0L),
                commentCounts.getOrDefault(r.id(), 0L), "/@" + r.authorHandle() + "/posts/" + r.id());
    }

    private static long get(Map<LocalDate, Long> m, LocalDate d) {
        return m.getOrDefault(d, 0L);
    }

    private static long total(Map<Long, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    @SafeVarargs
    private static Sums sum(LocalDate from, LocalDate to, ViewStats.Visitors v, long active,
                            Map<LocalDate, Long>... series) {
        long[] t = new long[series.length];
        for (int i = 0; i < series.length; i++) {
            for (Map.Entry<LocalDate, Long> e : series[i].entrySet()) {
                if (!e.getKey().isBefore(from) && !e.getKey().isAfter(to)) t[i] += e.getValue();
            }
        }
        return new Sums(t[0], t[1], t[2], t[3], t[4], t[5], v.visitors(), v.visits(), active, t[6]);
    }
}
