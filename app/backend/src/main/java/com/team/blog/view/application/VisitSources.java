package com.team.blog.view.application;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 유입 경로와 화면 주소 정리 (spec 070). 원래 주소는 남기지 않고, 어디서 왔는지(분류)와 화면 모양만 남긴다.
 * 분류는 화면이 보낸 이전 주소(document.referrer)의 호스트로 하고, 이전 주소가 없으면 앱 안 브라우저 표시(User-Agent)로 한다.
 * 카카오톡·인스타그램 앱 안에서 연 링크는 이전 주소가 비어 오는 일이 많기 때문이다.
 */
public final class VisitSources {
    public static final String DIRECT = "direct";
    public static final String OTHER = "other";
    /** 검색으로 온 방문 (대시보드의 '검색 유입') */
    public static final Set<String> SEARCH = Set.of("google", "naver", "daum", "bing");

    /** host는 OTHER일 때만 채운다 */
    public record Source(String source, String host) {}

    private record Rule(String source, List<String> hosts) {
        Rule(String source, String... hosts) {
            this(source, List.of(hosts));
        }
    }

    // 앞에서부터 맞춰 본다. 호스트가 이것과 같거나 ".이것"으로 끝나면 맞다
    private static final List<Rule> HOSTS = List.of(
            new Rule("naver", "naver.com", "naver.me", "naver.net"),
            new Rule("daum", "daum.net", "search.kakao.com"),
            new Rule("kakao", "kakao.com", "kakaocdn.net", "kakaotalk.com", "kakaostory.com"),
            new Rule("bing", "bing.com"),
            new Rule("instagram", "instagram.com"),
            new Rule("facebook", "facebook.com", "fb.com", "fb.me", "messenger.com"),
            new Rule("x", "x.com", "twitter.com", "t.co"),
            new Rule("youtube", "youtube.com", "youtu.be"),
            new Rule("threads", "threads.net", "threads.com"),
            new Rule("linkedin", "linkedin.com", "lnkd.in"),
            new Rule("github", "github.com", "github.io"));

    private static final Pattern GOOGLE = Pattern.compile("(^|\\.)google(\\.[a-z]{2,3}){1,2}$");
    private static final Pattern ANDROID_GOOGLE = Pattern.compile("^com\\.google\\.android\\.(googlequicksearchbox|gm)$");

    private VisitSources() {}

    /**
     * @param referrer 화면이 보낸 이전 주소 (없으면 null). 같은 사이트 주소는 화면이 보내지 않는다
     * @param ownHost 이 사이트 호스트. 이전 주소가 이것이면 직접 들어온 것으로 본다
     */
    public static Source classify(String referrer, String userAgent, String ownHost) {
        String host = host(referrer);
        if (host != null && ownHost != null && host.equals(normalizeHost(ownHost))) host = null;
        if (host == null) return new Source(inApp(userAgent), "");
        if (GOOGLE.matcher(host).find() || ANDROID_GOOGLE.matcher(host).matches()) return new Source("google", "");
        if (host.equals("com.nhn.android.search")) return new Source("naver", "");
        if (host.equals("com.kakao.talk")) return new Source("kakao", "");
        for (Rule r : HOSTS) {
            for (String h : r.hosts()) if (host.equals(h) || host.endsWith("." + h)) return new Source(r.source(), "");
        }
        return new Source(OTHER, host.length() > 100 ? host.substring(0, 100) : host);
    }

    /** 이전 주소가 없을 때: 앱 안 브라우저면 그 앱, 아니면 주소 직접 입력·즐겨찾기 */
    private static String inApp(String ua) {
        if (ua == null) return DIRECT;
        if (ua.contains("KAKAOTALK")) return "kakao";
        if (ua.contains("Instagram")) return "instagram";
        if (ua.contains("FBAN") || ua.contains("FBAV") || ua.contains("FB_IAB")) return "facebook";
        if (ua.contains("NAVER(inapp")) return "naver";
        if (ua.contains("DaumApps")) return "daum";
        if (ua.contains(" Line/")) return "line";
        return DIRECT;
    }

    /** http(s)·android-app 주소의 호스트(소문자, www.·m. 뺌). 읽을 수 없으면 null */
    static String host(String referrer) {
        if (referrer == null || referrer.isBlank() || referrer.length() > 2000) return null;
        try {
            URI uri = new URI(referrer.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("android-app")) return null;
            return uri.getHost() == null ? null : normalizeHost(uri.getHost());
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("www.")) h = h.substring(4);
        else if (h.startsWith("m.")) h = h.substring(2);
        return h;
    }

    private static final String HANDLE = "@[A-Za-z0-9_.-]{1,40}";
    // 남기는 화면 주소 모양. 관리자·글쓰기·인증처럼 순위가 의미 없거나 값이 들어가는 화면은 남기지 않는다
    private static final Pattern PAGE = Pattern.compile(
            "^/$"
            + "|^/" + HANDLE + "(/(posts/[1-9][0-9]{0,17}|series(/[^/]{1,120})?|about|followers|following|rss))?$"
            + "|^/(rss|search|mcp|support|releases|tags|login|signup|feed|terms|privacy|notifications|settings)$"
            + "|^/tags/[^/]{1,120}$"
            + "|^/lists/liked$|^/manage/posts$");

    /** 화면 주소를 순위용으로 정리한다. 남기지 않는 화면이면 null */
    public static String page(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 300) return null;
        String p = raw;
        int cut = indexOfAny(p);
        if (cut >= 0) p = p.substring(0, cut);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!PAGE.matcher(p).matches()) return null;
        if (p.startsWith("/@")) {
            int slash = p.indexOf('/', 2);
            p = slash < 0 ? p.toLowerCase(Locale.ROOT) : p.substring(0, slash).toLowerCase(Locale.ROOT) + p.substring(slash);
        }
        return p.length() > 200 ? null : p;
    }

    private static int indexOfAny(String s) {
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        if (q < 0) return h;
        if (h < 0) return q;
        return Math.min(q, h);
    }
}
