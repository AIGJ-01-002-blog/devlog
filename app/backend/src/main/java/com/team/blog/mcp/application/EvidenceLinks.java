package com.team.blog.mcp.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * AI가 일지·메모에 함께 남기는 근거 링크 (#240). 커밋·PR·이슈 주소를 받아 "[PR #12](주소)"처럼 읽을 수 있는 이름을 붙인다.
 * https 주소만 받고, 계정 정보·포트가 들어간 주소나 Markdown 링크를 깨뜨리는 글자(공백·괄호·꺾쇠)가 있는 주소는 버린다.
 * 그 주소가 정말 있는지는 확인하지 않는다(외부 API를 부르지 않는다).
 */
final class EvidenceLinks {
    static final int URL_MAX = 300;
    static final int DRAFT_MAX = 5;
    static final int NOTE_MAX = 3;

    /** 경로 앞부분만 본다(lookingAt). 뒤에 붙은 나머지는 상관없고, ++로 되돌아가지 않게 해서 긴 경로에도 한 번만 훑는다 */
    private static final Pattern GITHUB = Pattern.compile("^/[^/]++/([^/]++)/(commit|pull|issues|compare|releases/tag)/([^/?#]++)");
    private static final Pattern GITLAB = Pattern.compile("^/.+?/-/(commit|merge_requests|issues)/([^/?#]++)");

    record Link(String label, String url) {
        String markdown() {
            return "[" + label + "](" + url + ")";
        }
    }

    /** 받은 링크와 버린 링크 수 */
    record Parsed(List<Link> links, int dropped) {}

    private EvidenceLinks() {}

    /** links 배열(문자열)을 읽는다. 같은 주소는 한 번만, max개까지. 배열이 아니면 비어 있다 */
    static Parsed parse(JsonNode raw, int max) {
        List<Link> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int dropped = 0;
        if (raw == null || !raw.isArray()) return new Parsed(out, 0);
        for (JsonNode n : raw) {
            Link link = n.isString() ? of(n.asString()) : null;
            if (link == null || out.size() >= max) {
                dropped++;
                continue;
            }
            if (seen.add(link.url())) out.add(link);
        }
        return new Parsed(out, dropped);
    }

    /** 저장해 둔 주소 목록(줄바꿈으로 이은 값)에서 다시 읽는다 */
    static List<Link> fromStored(String stored) {
        List<Link> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) return out;
        for (String url : stored.split("\n")) {
            Link link = of(url);
            if (link != null) out.add(link);
        }
        return out;
    }

    static String toStored(List<Link> links) {
        return String.join("\n", links.stream().map(Link::url).toList());
    }

    /** 쓸 수 있는 근거 주소면 이름을 붙여 돌려주고, 아니면 null */
    static Link of(String raw) {
        if (raw == null) return null;
        String url = raw.strip();
        if (url.isEmpty() || url.length() > URL_MAX) return null;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || "()<>[]\"'`\\".indexOf(c) >= 0) return null;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1) return null;
        String host = uri.getHost();
        if (host == null || host.isBlank()) return null;
        return new Link(label(host.toLowerCase(Locale.ROOT), uri.getRawPath() == null ? "" : uri.getRawPath()), url);
    }

    /** github.com·gitlab.com 주소는 종류와 번호로, 그 밖에는 사이트 이름으로 */
    static String label(String host, String path) {
        String site = host.startsWith("www.") ? host.substring(4) : host;
        if (site.equals("github.com")) {
            Matcher m = GITHUB.matcher(path);
            if (m.lookingAt()) return kind(m.group(2), m.group(3), m.group(1));
        }
        if (site.equals("gitlab.com")) {
            Matcher m = GITLAB.matcher(path);
            if (m.lookingAt()) return kind(m.group(1), m.group(2), null);
        }
        return site;
    }

    private static String kind(String type, String ref, String repo) {
        String suffix = repo == null ? "" : " (" + repo + ")";
        return switch (type) {
            case "commit" -> "커밋 " + (ref.length() > 7 ? ref.substring(0, 7) : ref) + suffix;
            case "pull", "merge_requests" -> "PR #" + ref + suffix;
            case "issues" -> "이슈 #" + ref + suffix;
            case "compare" -> "변경 비교" + suffix;
            default -> "릴리스 " + ref + suffix;
        };
    }
}
