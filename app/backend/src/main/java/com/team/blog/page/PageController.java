package com.team.blog.page;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import com.team.blog.comment.application.CommentQuery;
import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.follow.application.FollowQuery;
import com.team.blog.shared.text.TextCleaner;
import com.team.blog.trending.application.TrendingService;
import com.team.blog.discovery.application.PostDetailQuery;
import com.team.blog.post.access.Viewer;
import com.team.blog.post.domain.PostStatus;
import com.team.blog.search.application.SearchQuery;
import com.team.blog.search.web.SearchController;
import com.team.blog.search.web.SearchIpLimit;
import com.team.blog.release.ReleaseNotes;
import com.team.blog.portfolio.application.PortfolioQuery;
import com.team.blog.series.application.SeriesQuery;
import com.team.blog.shorts.application.BlogShorts;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.account.application.MemberAbout;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.tag.application.TagNormalizer;
import com.team.blog.tag.application.TagQuery;
import com.team.blog.tag.web.TagController;
import com.team.blog.post.query.PostCardPage;
import com.team.blog.post.query.PostCard;

/**
 * 화면 주소를 React 앱으로 연결하면서, 링크 미리보기·검색 엔진이 읽을 머리말과 첫 화면 HTML을 서버에서 채운다.
 * 글 주소 처리 순서는 docs/40 §3: 대문자 → 301, 숫자 아님 → 404, 판정(canRead) → 404, 다른 블로그 주소 → 301,
 * 작성자 본인의 임시글 → 302 에디터. 판정을 301보다 먼저 해서 볼 수 없는 글의 작성자가 드러나지 않게 한다.
 */
@Controller
public class PageController {
    private static final Pattern HANDLE_CHARS = Pattern.compile("[A-Za-z0-9_-]{1,23}");
    /** 서비스 슬로건 (051). 첫 화면 설명과 링크 미리보기에 같이 쓴다 */
    static final String SLOGAN = "코딩은 AI와, 기록은 devlog가.";
    /** 이보다 글이 적은 태그 화면은 noindex (spec 085) */
    static final int MIN_INDEXED_TAG_POSTS = 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("Asia/Seoul"));
    private static final String LI_LINK = "<li><a href=\"";
    private static final String LI_END = "</li>";
    private static final String UL_END = "</ul>";
    private static final String MAIN_END = "</main>";
    private static final String MAIN_H1 = "<main><h1>";
    private static final String UL_MAIN_END = "</ul></main>";
    private static final String H1_END = "</h1>";
    private static final String ABOUT_PATH = "/about";
    private static final String PROFILE = "profile";
    private static final String LINK_LI_END = "</a></li>";

    private final SpaShell shell;
    private final FeedQuery feed;
    private final PostDetailQuery details;
    private final BlogProperties.Site site;
    private final TagQuery tags;
    private final TagController tagApi;
    private final CommentQuery comments;
    private final SearchQuery search;
    private final SearchIpLimit searchLimit;
    private final FollowQuery follows;
    private final TrendingService trending;
    private final SeriesQuery series;
    private final MemberAbout about;
    private final ReleaseNotes releaseNotes;
    private final PortfolioQuery portfolios;
    private final BlogShorts blogShorts;
    private final SiteOwner owner;
    private final PolicyDocuments policies;

    public PageController(SpaShell shell, BlogProperties props, PostPageSources posts, TagPageSources tagPages,
                          BlogPageSources blogPages, SitePageSources sitePages) {
        this.shell = shell;
        this.site = props.site();
        this.feed = posts.feed();
        this.details = posts.details();
        this.comments = posts.comments();
        this.trending = posts.trending();
        this.tags = tagPages.tags();
        this.tagApi = tagPages.tagApi();
        this.search = tagPages.search();
        this.searchLimit = tagPages.searchLimit();
        this.follows = blogPages.follows();
        this.series = blogPages.series();
        this.about = blogPages.about();
        this.portfolios = blogPages.portfolios();
        this.blogShorts = blogPages.shorts();
        this.owner = sitePages.owner();
        this.policies = sitePages.policies();
        this.releaseNotes = sitePages.releaseNotes();
    }

    /** 태그 주소: 이름을 경로 조각으로 인코딩한다. #은 %23, +·.은 그대로 (docs/22 §5). */
    public static String tagPath(String name) {
        return "/tags/" + UriUtils.encodePathSegment(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 전체 태그 (spec 089): 서버는 상위 100개 태그 링크를 그리고, 화면이 쓸 첫 태그 줄(검색어·정렬 반영)을 함께 내려준다. */
    @GetMapping("/tags")
    public ResponseEntity<String> tagIndex(@RequestParam(required = false) String q, @RequestParam(required = false) String sort) {
        var top = tags.top(100);
        var browse = tagApi.browse(q, sort, null).getBody();
        StringBuilder body = new StringBuilder("<main><h1>태그</h1>");
        if (top.isEmpty()) body.append("<p>아직 태그가 없어요.</p>");
        else {
            body.append("<ul>");
            top.forEach(t -> body.append(LI_LINK).append(SpaShell.esc(tagPath(t.name()))).append("\">#")
                    .append(SpaShell.esc(t.name())).append("</a> ").append(t.postCount()).append(LI_END));
            body.append(UL_END);
        }
        body.append(MAIN_END);
        HeadMeta meta = HeadMeta.site("태그 - " + site.name(), "공개 글에 쓰인 태그", absolute("/tags"), absolute(site.defaultOgImage()));
        // 검색어로 거른 목록은 /tags의 일부라 따로 수집하지 않는다 (정렬만 바꾼 주소는 정규 주소가 /tags다)
        if (q != null && !q.isBlank()) meta = HeadMeta.privatePage(site.name(), "태그");
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "tags", "tags", top, "browse", browse)),
                CacheControl.noCache());
    }

    /**
     * 태그별 목록 (010 FR-019~FR-022): 형식 안 맞음 404, 정규화 안 된 주소 301.
     * 공개 글이 없는 태그(없는 태그, 글이 지워진 태그)는 404로 내린다. 200 + 빈 상태는 검색 엔진이 Soft 404로 본다 (spec 090).
     * 화면 안에서 옮겨 오면 React가 API로 빈 상태를 그대로 보여 준다.
     */
    @GetMapping("/tags/{name}")
    public ResponseEntity<String> tag(@PathVariable String name) {
        var canonical = TagNormalizer.canonical(name);
        if (canonical.isEmpty()) return notFound();
        if (!canonical.get().equals(name)) return redirect(HttpStatus.MOVED_PERMANENTLY, tagPath(canonical.get()));
        TagController.TagPage page = tagApi.page(name, null);
        if (page.postCount() == null || page.postCount() < 1 || page.items().isEmpty()) return notFound();
        String body = "<main><h1>#" + SpaShell.esc(name) + "</h1><p>공개 글 " + page.postCount() + "</p>"
                + cards(new PostCardPage(page.items(), page.nextCursor())) + MAIN_END;
        HeadMeta meta = HeadMeta.site("#" + name + " - " + site.name(), "#" + name + " 태그가 달린 글", absolute(tagPath(name)),
                absolute(site.defaultOgImage()));
        // 글이 하나뿐인 태그 화면은 그 글과 내용이 겹치는 얇은 화면이라 검색 수집·광고에서 뺀다 (spec 085, 애드센스 "가치가 별로 없는 콘텐츠")
        if (page.postCount() == null || page.postCount() < MIN_INDEXED_TAG_POSTS) meta = HeadMeta.privatePage(site.name(), "#" + name);
        return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "tag", "tag", page)), CacheControl.noCache());
    }

    /**
     * 검색 결과 화면 (014, docs/33 §5). 검색 엔진 수집 거부(FR-022), 저장 안 함. 글 탭은 첫 결과를 함께 내려준다.
     * 비회원이 같은 IP로 너무 자주 열면 결과 없이 빈 화면만 내려준다. 화면이 API로 다시 찾고, 요청 제한 안내는 API가 보여 준다.
     */
    @GetMapping("/search")
    public ResponseEntity<String> search(@RequestParam(required = false) String q, @RequestParam(required = false) String tab,
                                         @RequestParam(required = false) String sort,
                                         @CurrentMember(required = false) MemberPrincipal me, HttpServletRequest request) {
        String title = q == null || q.isBlank() ? "검색" : "'" + truncate(q.strip(), 50) + "' 검색";
        HeadMeta meta = HeadMeta.privatePage(site.name(), title);
        if (q == null || q.isBlank() || "people".equals(tab) || (me == null && !searchLimit.tryAcquire(request))) {
            return html(HttpStatus.OK, shell.render(meta, "<main><h1>검색</h1></main>", Map.of("page", "search")), CacheControl.noStore());
        }
        SearchQuery.PostPage result = search.posts(q, SearchController.parseSort(sort), null, null);
        StringBuilder body = new StringBuilder(MAIN_H1).append(SpaShell.esc(title)).append("</h1><ul>");
        for (SearchQuery.Hit h : result.items()) {
            body.append(LI_LINK).append(SpaShell.esc(h.url())).append("\">").append(SpaShell.esc(h.title()))
                    .append("</a> <span>").append(SpaShell.esc(h.author().nickname())).append("</span>");
            if (h.snippetHtml() != null) body.append("<p>").append(h.snippetHtml()).append("</p>"); // mark 외 태그 없음 (FR-019)
            body.append(LI_END);
        }
        body.append(UL_MAIN_END);
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "search", "result", result)), CacheControl.noStore());
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> home(@RequestParam(required = false) String tab) {
        if ("trending".equals(tab)) {
            // 트렌딩 탭 (017): 첫 9개를 함께 내려준다. 순위표 문제로 바로 계산한 결과면 저장하지 않는다
            TrendingService.Page t = trending.page(null);
            PostCardPage asFeed = new PostCardPage(t.items(), t.nextCursor());
            String body = "<main><h1>트렌딩</h1>" + cards(asFeed) + MAIN_END;
            HeadMeta meta = HeadMeta.site(site.name(), "최근 7일 동안 반응이 많은 글", absolute("/?tab=trending"), absolute(site.defaultOgImage()));
            return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "home", "trending", asFeed)),
                    t.temporary() ? CacheControl.noStore() : CacheControl.noCache());
        }
        PostCardPage first = feed.home(null);
        String body = MAIN_H1 + SpaShell.esc(site.name()) + H1_END + cards(first) + MAIN_END;
        HeadMeta meta = HeadMeta.site(site.name(), SLOGAN + " AI 도구에 연결하면 개발 일지를 써 주는 개발자 블로그", absolute("/"), absolute(site.defaultOgImage()));
        return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "home", "feed", first)), CacheControl.noCache());
    }

    /** AI에 devlog 연결하기 (051): 검색 엔진·링크 미리보기에 보이는 공개 안내 화면. React가 연결 명령과 도구 목록을 그린다. */
    @GetMapping("/mcp")
    public ResponseEntity<String> mcpGuide() {
        String body = "<main><h1>당신의 AI가 개발 일지를 씁니다</h1><p>Claude·Cursor 같은 AI 도구에 devlog MCP를 연결하면, "
                + "오늘 작업한 대화와 커밋을 정리해 개발 일지 임시글을 올려 줍니다. 발행은 기본으로 내가 합니다.</p></main>";
        HeadMeta meta = HeadMeta.site("AI에 devlog 연결하기 - " + site.name(), SLOGAN + " devlog MCP로 AI가 개발 일지를 써 줍니다.",
                absolute("/mcp"), absolute(site.defaultOgImage()));
        return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "mcp")), CacheControl.noCache());
    }

    /**
     * 사이트 소개 (077): 무엇을 하는 곳인지, 누가 운영하는지, 어디로 연락하는지. 애드센스 심사처럼 사람이 아닌 읽기 도구도
     * 내용을 보도록 서버가 본문을 그리고, React가 같은 내용을 다시 그린다.
     */
    @GetMapping(ABOUT_PATH)
    public ResponseEntity<String> siteAbout() {
        String ownerHandle = owner.handle().orElse(null);
        StringBuilder body = new StringBuilder("<main><h1>devlog 소개</h1><p>").append(SpaShell.esc(SLOGAN)).append("</p>")
                .append("<p>devlog는 개발자가 개발 일지와 기술 글을 쓰고 읽는 블로그예요. Claude 같은 AI 도구에 devlog를 연결하면 ")
                .append("그날 작업한 내용을 정리해 개발 일지 임시글로 올려 주고, 발행은 글쓴이가 직접 정해요.</p>")
                .append("<h2>누가 운영하나요</h2><p>학교 팀 프로젝트로 시작했고, 지금은 운영자 한 명이 서버와 서비스를 맡고 있어요.");
        if (ownerHandle != null) {
            body.append(" 운영자 블로그: <a href=\"/@").append(SpaShell.esc(ownerHandle)).append("\">@").append(SpaShell.esc(ownerHandle)).append("</a>");
        }
        body.append("</p><h2>연락하기</h2><p><a href=\"/support\">문의·신고</a>에서 질문, 버그, 권리 침해 신고를 받아요. 답변을 알림으로 드리려고 로그인한 뒤 남기도록 했어요.</p>")
                .append("<p><a href=\"/terms\">이용약관</a> · <a href=\"/privacy\">개인정보 처리방침</a></p></main>");
        HeadMeta meta = HeadMeta.site("소개 - " + site.name(), SLOGAN + " devlog가 어떤 곳이고 누가 운영하는지 알려 드려요.",
                absolute(ABOUT_PATH), absolute(site.defaultOgImage()));
        var data = new HashMap<String, Object>(); // 운영자 주소가 비면 null이라 Map.of를 못 쓴다
        data.put("page", "about");
        data.put("ownerHandle", ownerHandle);
        return html(HttpStatus.OK, shell.render(meta, body.toString(), data), CacheControl.noCache());
    }

    /**
     * 이용약관·개인정보 처리방침 (spec 085): 공개 화면. 애드센스 심사처럼 스크립트를 돌리지 않는 읽기 도구도 읽도록 서버가 본문을 그리고,
     * React가 같은 문안(policy/*.html)으로 다시 그린다.
     */
    @GetMapping({"/terms", "/privacy"})
    public ResponseEntity<String> policy(HttpServletRequest request) {
        PolicyDocuments.Kind kind = request.getRequestURI().endsWith("/terms") ? PolicyDocuments.Kind.TERMS : PolicyDocuments.Kind.PRIVACY;
        PolicyDocuments.Document doc = policies.get(kind);
        boolean terms = kind == PolicyDocuments.Kind.TERMS;
        String body = MAIN_H1 + SpaShell.esc(doc.title()) + H1_END
                + (doc.effectiveDate() == null ? "" : "<p>시행일 " + SpaShell.esc(doc.effectiveDate()) + "</p>")
                + doc.html()
                + "<p><a href=\"" + (terms ? "/privacy\">개인정보 처리방침 보기" : "/terms\">이용약관 보기") + "</a></p></main>";
        HeadMeta meta = HeadMeta.site(doc.title() + " - " + site.name(),
                "devlog " + doc.title() + (terms ? ": 회원 가입·게시물·이용 제한·탈퇴." : ": 모으는 정보, 쓰는 곳, 보관 기간, AI 기능, 광고와 쿠키."),
                absolute("/" + doc.kind()), absolute(site.defaultOgImage()));
        return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "policy", "policy", doc)), CacheControl.noCache());
    }

    /** 릴리스 노트 (054): 누구나 보는 공개 화면. 서버는 최근 버전 제목만 그리고 React가 본문을 그린다. */
    @GetMapping("/releases")
    public ResponseEntity<String> releases() {
        StringBuilder body = new StringBuilder("<main><h1>릴리스 노트</h1><ul>");
        releaseNotes.all().stream().limit(10).forEach(r -> body.append("<li>v").append(SpaShell.esc(r.version()))
                .append(r.date() == null ? "" : " · " + SpaShell.esc(r.date())).append(LI_END));
        body.append(UL_MAIN_END);
        HeadMeta meta = HeadMeta.site("릴리스 노트 - " + site.name(), "devlog가 버전마다 무엇을 더하고 고쳤는지 모았어요.",
                absolute("/releases"), absolute(site.defaultOgImage()));
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "releases")), CacheControl.noCache());
    }

    @GetMapping("/@{handle}")
    public ResponseEntity<String> blog(@PathVariable String handle, @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String q,
                                       @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        String tagQuery = tag == null || tag.isEmpty() ? "" : "?tag=" + java.net.URLEncoder.encode(tag, java.nio.charset.StandardCharsets.UTF_8);
        if (!handle.equals(handle.toLowerCase())) return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + tagQuery);
        // 블로그 안 태그 필터 (010 FR-031): 형식 안 맞음 404, 정규화 안 된 값 301
        String filter = null;
        if (!tagQuery.isEmpty()) {
            var canonical = TagNormalizer.canonical(tag);
            if (canonical.isEmpty()) return notFound();
            if (!canonical.get().equals(tag)) {
                return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle + "?tag="
                        + java.net.URLEncoder.encode(canonical.get(), java.nio.charset.StandardCharsets.UTF_8));
            }
            filter = tag;
        }
        Long viewer = me == null ? null : me.id();
        var profile = feed.profile(handle, viewer);
        if (profile.isEmpty()) return notFound();
        FeedQuery.BlogProfile p = profile.get();
        PostCardPage first = feed.blog(handle, filter, null, viewer);
        return blogPage(p, first, filter, q);
    }

    private ResponseEntity<String> blogPage(FeedQuery.BlogProfile p, PostCardPage first, String filter, String q) {
        var blogTags = tags.blogTags(p.id());
        String body = "<main><header><h1>" + SpaShell.esc(p.nickname()) + "</h1><p>@" + SpaShell.esc(p.handle()) + "</p>"
                + (p.bio() == null ? "" : "<p>" + SpaShell.esc(p.bio()) + "</p>") + "</header>" + cards(first) + MAIN_END;
        String description = p.bio() == null || p.bio().isBlank() ? p.nickname() + "의 블로그" : truncate(p.bio(), 160);
        HeadMeta meta = new HeadMeta(p.nickname() + " (@" + p.handle() + ") - " + site.name(), description,
                absolute("/@" + p.handle()), PROFILE, p.profileImageUrl() != null ? p.profileImageUrl() : absolute(site.defaultOgImage()),
                null, null, q == null || q.isBlank()); // 블로그 안 검색 결과는 수집 거부 (014 FR-022)
        return html(HttpStatus.OK, shell.render(meta, body, filter == null
                        ? Map.of("page", "blog", PROFILE, p, "feed", first, "blogTags", blogTags)
                        : Map.of("page", "blog", PROFILE, p, "feed", first, "blogTags", blogTags, "tag", filter), "/@" + p.handle() + "/rss"),
                first.friendsView() ? CacheControl.noStore().cachePrivate() : CacheControl.noCache().cachePrivate());
    }

    /** 팔로워·팔로잉 목록 (016 US3). 주인이 비공개로 두면 본인·관리자만 본다(079). 보는 사람마다 버튼 상태가 다르고 비공개 목록도 있어 저장하지 않는다. 얇은 목록이라 수집하지 않는다. */
    @GetMapping({"/@{handle}/followers", "/@{handle}/following"})
    public ResponseEntity<String> follows(@PathVariable String handle, HttpServletRequest request,
                                          @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        boolean followers = request.getRequestURI().endsWith("/followers");
        if (!handle.equals(handle.toLowerCase())) {
            return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + (followers ? "/followers" : "/following"));
        }
        var profile = feed.profile(handle, me == null ? null : me.id());
        if (profile.isEmpty()) return notFound();
        FeedQuery.BlogProfile p = profile.get();
        FollowQuery.Page first = follows.list(handle, followers ? FollowQuery.Direction.FOLLOWERS : FollowQuery.Direction.FOLLOWING,
                null, me == null ? null : me.id(), me != null && me.isStaff());
        String title = p.nickname() + (followers ? "님의 팔로워" : "님이 팔로우하는 사람");
        StringBuilder body = new StringBuilder(MAIN_H1).append(SpaShell.esc(title)).append(H1_END);
        if (first.hidden()) body.append("<p>비공개 계정입니다</p>");
        body.append("<ul>");
        first.items().forEach(x -> body.append("<li><a href=\"/@").append(SpaShell.esc(x.handle())).append("\">")
                .append(SpaShell.esc(x.nickname())).append(" @").append(SpaShell.esc(x.handle())).append(LINK_LI_END));
        body.append(UL_MAIN_END);
        return html(HttpStatus.OK, shell.render(HeadMeta.privatePage(site.name(), title), body.toString(),
                Map.of("page", "follows", PROFILE, p, "follows", first)), CacheControl.noStore().cachePrivate());
    }

    /** 블로그의 시리즈 탭 (024 US2-2). 남과 다른 응답(친구·주인)은 저장하지 않는다. */
    @GetMapping("/@{handle}/series")
    public ResponseEntity<String> seriesList(@PathVariable String handle, @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        if (!handle.equals(handle.toLowerCase())) return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + "/series");
        var profile = feed.profile(handle, me == null ? null : me.id());
        var listing = series.list(handle, me == null ? null : me.id());
        if (profile.isEmpty() || listing.isEmpty()) return notFound();
        FeedQuery.BlogProfile p = profile.get();
        String title = p.nickname() + "님의 시리즈";
        StringBuilder body = new StringBuilder(MAIN_H1).append(SpaShell.esc(title)).append("</h1><ul>");
        listing.get().items().forEach(x -> body.append(LI_LINK).append(SpaShell.esc(seriesPath(handle, x.slug()))).append("\">")
                .append(SpaShell.esc(x.name())).append("</a> (").append(x.postCount()).append(")</li>"));
        body.append(UL_MAIN_END);
        HeadMeta meta = new HeadMeta(title + " - " + site.name(), p.nickname() + "의 블로그 시리즈", absolute("/@" + handle + "/series"),
                "website", p.profileImageUrl() != null ? p.profileImageUrl() : absolute(site.defaultOgImage()), null, null, true);
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "series-list")),
                listing.get().personal() ? CacheControl.noStore().cachePrivate() : CacheControl.noCache().cachePrivate());
    }

    /** 블로그 [소개] 탭 (042). 소개 HTML을 담아 수집한다. 비어 있으면 수집하지 않는다. */
    @GetMapping("/@{handle}/about")
    public ResponseEntity<String> about(@PathVariable String handle, @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        if (!handle.equals(handle.toLowerCase())) return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + ABOUT_PATH);
        Long viewer = me == null ? null : me.id();
        var profile = feed.profile(handle, viewer);
        var found = about.of(handle, viewer);
        if (profile.isEmpty() || found.isEmpty()) return notFound();
        FeedQuery.BlogProfile p = profile.get();
        MemberAbout.About a = found.get();
        String title = p.nickname() + "님 소개";
        String body = MAIN_H1 + SpaShell.esc(title) + H1_END + (a.html() == null ? "" : "<article>" + a.html() + "</article>") + MAIN_END;
        HeadMeta meta = new HeadMeta(title + " - " + site.name(), p.bio() != null ? p.bio() : p.nickname() + "의 블로그 소개",
                absolute("/@" + handle + ABOUT_PATH), PROFILE,
                p.profileImageUrl() != null ? p.profileImageUrl() : absolute(site.defaultOgImage()), null, a.updatedAt(), a.html() != null);
        return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "about")), CacheControl.noCache().cachePrivate());
    }

    /** 블로그 [쇼츠] 탭 (spec 103). 쇼츠가 꺼져 있으면 없는 주소다. 누구에게나 같은 공개 정보라 저장해도 된다. */
    @GetMapping("/@{handle}/shorts")
    public ResponseEntity<String> blogShorts(@PathVariable String handle, @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        if (!handle.equals(handle.toLowerCase())) return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + "/shorts");
        var profile = feed.profile(handle, me == null ? null : me.id());
        if (profile.isEmpty()) return notFound();
        List<BlogShorts.Card> cards;
        try {
            cards = blogShorts.ofBlog(handle);
        } catch (NotFoundException e) {
            return notFound();
        }
        FeedQuery.BlogProfile p = profile.get();
        String title = p.nickname() + "님의 쇼츠";
        StringBuilder body = new StringBuilder(MAIN_H1).append(SpaShell.esc(title)).append(H1_END).append("<ul>");
        cards.forEach(c -> body.append(LI_LINK).append(SpaShell.esc(c.links().get(0).url())).append("\" rel=\"nofollow noopener\">")
                .append(SpaShell.esc(c.title())).append(LINK_LI_END));
        body.append(UL_MAIN_END);
        HeadMeta meta = new HeadMeta(title + " - " + site.name(), p.nickname() + "님이 YouTube·Instagram·TikTok에 올린 쇼츠",
                absolute("/@" + handle + "/shorts"), PROFILE,
                p.profileImageUrl() != null ? p.profileImageUrl() : absolute(site.defaultOgImage()), null, null, false);
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "shorts")), CacheControl.noCache().cachePrivate());
    }

    /** 포트폴리오 (072 3단계). 공개 정보만 담겨 누구에게나 같고, 프로젝트 이름과 설명을 담아 수집한다. */
    @GetMapping("/@{handle}/portfolio")
    public ResponseEntity<String> portfolio(@PathVariable String handle) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        if (!handle.equals(handle.toLowerCase())) return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + "/portfolio");
        var found = portfolios.of(handle);
        if (found.isEmpty()) return notFound();
        PortfolioQuery.Portfolio p = found.get();
        String title = p.nickname() + "의 개발 기록";
        StringBuilder body = new StringBuilder(MAIN_H1).append(SpaShell.esc(title)).append(H1_END);
        if (p.bio() != null) body.append("<p>").append(SpaShell.esc(p.bio())).append("</p>");
        body.append("<ul>");
        p.projects().forEach(x -> body.append(LI_LINK).append(SpaShell.esc(x.url())).append("\">").append(SpaShell.esc(x.name()))
                .append("</a>").append(x.summary() == null ? "" : " " + SpaShell.esc(x.summary())).append(LI_END));
        body.append(UL_MAIN_END);
        HeadMeta meta = new HeadMeta(title + " - " + site.name(), p.bio() != null ? p.bio() : p.nickname() + "의 포트폴리오",
                absolute("/@" + handle + "/portfolio"), PROFILE,
                p.profileImageUrl() != null ? p.profileImageUrl() : absolute(site.defaultOgImage()), null, null, !p.projects().isEmpty());
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "portfolio")), CacheControl.noCache());
    }

    /** 시리즈 페이지 (024 US2-2). 글 목록을 순서대로 담아 수집한다. */
    @GetMapping("/@{handle}/series/{slug}")
    public ResponseEntity<String> seriesDetail(@PathVariable String handle, @PathVariable String slug,
                                               @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        if (!handle.equals(handle.toLowerCase())) return redirect(HttpStatus.MOVED_PERMANENTLY, seriesPath(handle.toLowerCase(), slug));
        var found = series.detail(handle, slug, me == null ? null : me.id());
        if (found.isEmpty()) return notFound();
        SeriesQuery.Detail d = found.get();
        StringBuilder body = new StringBuilder(MAIN_H1).append(SpaShell.esc(d.name())).append("</h1><ol>");
        d.posts().forEach(c -> body.append(LI_LINK).append(SpaShell.esc(c.url())).append("\">").append(SpaShell.esc(c.title()))
                .append(LINK_LI_END));
        body.append("</ol></main>");
        String ownerName = d.posts().isEmpty() ? handle : d.posts().getFirst().author().nickname();
        String image = d.posts().stream().map(PostCard::thumbnailUrl).filter(java.util.Objects::nonNull).findFirst()
                .orElse(absolute(site.defaultOgImage()));
        HeadMeta meta = new HeadMeta(d.name() + " - " + ownerName, ownerName + "의 시리즈 · 글 " + d.posts().size() + "개",
                absolute(seriesPath(handle, d.slug())), "website", image, null, d.updatedAt(), !d.posts().isEmpty());
        return html(HttpStatus.OK, shell.render(meta, body.toString(), Map.of("page", "series")),
                d.personal() ? CacheControl.noStore().cachePrivate() : CacheControl.noCache().cachePrivate());
    }

    static String seriesPath(String handle, String slug) {
        return "/@" + handle + "/series/" + UriUtils.encodePathSegment(slug, java.nio.charset.StandardCharsets.UTF_8);
    }

    @GetMapping("/@{handle}/posts/{postId}")
    public ResponseEntity<String> post(@PathVariable String handle, @PathVariable String postId,
                                       @CurrentMember(required = false) MemberPrincipal me) {
        if (!HANDLE_CHARS.matcher(handle).matches()) return notFound();
        if (!handle.equals(handle.toLowerCase())) {
            return redirect(HttpStatus.MOVED_PERMANENTLY, "/@" + handle.toLowerCase() + "/posts/" + postId);
        }
        if (!postId.matches("[1-9]\\d{0,17}")) return notFound();
        var found = details.find(Long.parseLong(postId), Viewer.of(me));
        if (found.isEmpty()) return notFound();
        PostDetailQuery.Detail d = found.get();
        if (!d.author().handle().equals(handle)) return redirect(HttpStatus.MOVED_PERMANENTLY, d.url());
        if (d.status() == PostStatus.DRAFT) return redirect(HttpStatus.FOUND, "/write/" + d.id());

        String body = article(d);
        HeadMeta meta;
        if (d.publiclyVisible()) {
            String description = d.excerpt() == null ? "" : truncate(d.excerpt(), 160);
            meta = new HeadMeta(d.title() + " - " + d.author().nickname(), description, absolute(d.url()), "article",
                    d.thumbnailUrl() != null ? d.thumbnailUrl() : absolute(site.defaultOgImage()),
                    d.firstPublicAt(), d.editedAt(), !d.aiDiary(), d.author().nickname()); // AI 일기는 독자에게 보이되 수집·광고에서 뺀다 (spec 088)
        } else {
            meta = new HeadMeta(d.title() + " - " + site.name(), null, null, null, null, null, null, false);
        }
        CacheControl cc = d.publiclyVisible() ? CacheControl.noCache().cachePrivate() : CacheControl.noStore().cachePrivate();
        return html(HttpStatus.OK, shell.render(meta, body, Map.of("page", "post", "post", d,
                // 글 상세를 열면 첫 20개 댓글이 함께 보인다 (011 FR-026)
                "comments", comments.page(d.id(), Viewer.of(me), null, null, null))), cc);
    }

    /** 로그인이 필요하거나 개인적인 화면: 같은 껍데기, 수집 거부, 저장 안 함. React가 그린다. */
    @GetMapping({"/login", "/signup", "/signup/social", "/forgot-password", "/reset-password", "/verify-email", "/agreements", "/write", "/write/{id}", "/manage/posts", "/settings", "/notifications", "/feed", "/lists/liked", "/lists/read",
            "/settings/{section}", "/oauth/authorize", "/support"})
    public ResponseEntity<String> app(@PathVariable(required = false) String id, @PathVariable(required = false) String section) {
        // 글 번호·설정 칸은 React가 주소에서 읽는다. 서버는 같은 껍데기만 준다
        return appShell();
    }

    private ResponseEntity<String> appShell() {
        return html(HttpStatus.OK, shell.render(HeadMeta.privatePage(site.name(), null), "", Map.of("page", "app")),
                CacheControl.noStore());
    }

    /**
     * 관리자 화면 (019 FR-011, 062: 매니저 포함). 비회원은 있는 주소·없는 주소 모두 로그인으로, 일반 회원은 없는 페이지와 같은 404다.
     */
    @GetMapping({"/admin", "/admin/**"})
    public ResponseEntity<String> admin(HttpServletRequest request, @CurrentMember(required = false) MemberPrincipal me) {
        if (me == null) {
            String back = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            return redirect(HttpStatus.FOUND, "/login?redirect=" + UriUtils.encodeQueryParam(back, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!me.isStaff()) return notFound();
        return appShell();
    }

    private ResponseEntity<String> notFound() {
        return notFoundPage(shell, site.name());
    }

    /** 없는 화면 404: 앱 껍데기에 안내와 [홈으로]를 그린다. 어느 주소에도 맞지 않는 화면 주소(NotFoundPageAdvice)도 이것을 쓴다 */
    static ResponseEntity<String> notFoundPage(SpaShell shell, String siteName) {
        String body = "<main><h1>볼 수 없는 페이지예요</h1><p>주소가 바뀌었거나, 삭제·비공개된 글일 수 있어요.</p><a href=\"/\">홈으로</a></main>";
        return html(HttpStatus.NOT_FOUND, shell.render(HeadMeta.privatePage(siteName, "볼 수 없는 페이지예요"), body,
                Map.of("page", "notFound")), CacheControl.noStore());
    }

    private String article(PostDetailQuery.Detail d) {
        StringBuilder sb = new StringBuilder("<main><article>");
        sb.append("<h1>").append(SpaShell.esc(d.title())).append(H1_END);
        sb.append("<p><a href=\"/@").append(SpaShell.esc(d.author().handle())).append("\">")
                .append(SpaShell.esc(d.author().nickname())).append(" @").append(SpaShell.esc(d.author().handle()))
                .append("</a> · <time datetime=\"").append(d.displayDate()).append("\">")
                .append(DATE.format(d.displayDate())).append("</time>");
        if (d.editedAt() != null) {
            sb.append(" · 수정됨 <time datetime=\"").append(d.editedAt()).append("\">").append(DATE.format(d.editedAt())).append("</time>");
        }
        sb.append("</p>");
        if (!d.tags().isEmpty()) {
            sb.append("<ul class=\"post-tags\">");
            d.tags().forEach(t -> sb.append(LI_LINK).append(SpaShell.esc(tagPath(t))).append("\">#").append(SpaShell.esc(t)).append(LINK_LI_END));
            sb.append(UL_END);
        }
        sb.append("<div class=\"post-body\">").append(d.contentHtml()).append("</div>"); // 발행 때 정화한 HTML
        sb.append("</article></main>");
        return sb.toString();
    }

    private String cards(PostCardPage page) {
        StringBuilder sb = new StringBuilder("<ul>");
        for (PostCard c : page.items()) {
            sb.append(LI_LINK).append(SpaShell.esc(c.url())).append("\">").append(SpaShell.esc(c.title()))
                    .append("</a> <span>").append(SpaShell.esc(c.author().nickname())).append("</span>");
            if (c.excerpt() != null) sb.append("<p>").append(SpaShell.esc(c.excerpt())).append("</p>");
            sb.append(LI_END);
        }
        return sb.append(UL_END).toString();
    }

    private String absolute(String path) {
        if (path == null) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String base = site.baseUrl() == null ? "" : TextCleaner.trimEnd(site.baseUrl(), '/');
        return base + path;
    }

    private static String truncate(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }

    private static ResponseEntity<String> html(HttpStatus status, String body, CacheControl cc) {
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(cc).body(body);
    }

    private static ResponseEntity<String> redirect(HttpStatus status, String location) {
        return ResponseEntity.status(status).location(URI.create(location)).build();
    }

}
