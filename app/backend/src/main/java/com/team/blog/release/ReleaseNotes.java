package com.team.blog.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.team.blog.shared.markdown.ContentRenderer;

/**
 * 릴리스 노트 (054). 빌드 때 jar의 release/CHANGELOG.md로 들어온 저장소의 변경 기록을 버전별로 나눠 보여 준다.
 * 맨 위 버전이 지금 돌고 있는 devlog 버전이다(문의 접수 때 함께 적는다). 파일이 없으면(테스트·로컬 일부) 빈 목록이다.
 * 본문은 글과 같은 렌더러로 정화한 HTML이다. 처음 부를 때 한 번 만들어 둔다(배포마다 바뀌는 파일이라 다시 읽을 일이 없다).
 * 화면 전/후 사진(093)은 CHANGELOG에 GitHub 원본 주소로 적혀 있다. GitHub Release에서는 그 주소로 보이고,
 * 이 화면에서는 jar에 함께 실린 사본(/release-assets/…)으로 바꿔 보여 준다. 사이트 밖 사진 주소는 정화가 지운다.
 */
@Service
public class ReleaseNotes {
    static final String RESOURCE = "release/CHANGELOG.md";
    private static final Pattern HEADING = Pattern.compile("^## \\[(\\d+\\.\\d+\\.\\d+)](?:\\s*-\\s*(\\S+))?.*$");
    /** 저장소 assets/releases/ 사진의 GitHub 원본 주소. 브랜치 이름(main 등)은 무엇이든 받는다 */
    private static final Pattern RAW_ASSET = Pattern.compile(
            "https://raw\\.githubusercontent\\.com/AIGJ-01-002-blog/devlog/[\\w.-]+/assets/releases/", Pattern.CASE_INSENSITIVE);
    /** 릴리스 노트 틀의 안내 주석. 지우지 않고 남아도 화면에 글자로 보이지 않게 뺀다 */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** @param html 그 버전의 본문(### 추가·고침 …)을 정화한 HTML */
    public record Release(String version, String date, String html) {}

    record Section(String version, String date, String markdown) {}

    private final ContentRenderer renderer;
    private final String assetBase;
    private final List<Section> sections;
    private final AtomicReference<List<Release>> rendered = new AtomicReference<>();

    public ReleaseNotes(ContentRenderer renderer, @Value("${blog.site.base-url:http://localhost:8080}") String siteBase) {
        this.renderer = renderer;
        String base = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        this.assetBase = base + ContentRenderer.RELEASE_ASSETS;
        this.sections = parse(load());
    }

    /** 지금 버전 ("1.29.0"). 변경 기록이 없으면 null */
    public String currentVersion() {
        return sections.isEmpty() ? null : sections.getFirst().version();
    }

    public boolean exists(String version) {
        return sections.stream().anyMatch(s -> s.version().equals(version));
    }

    public List<Release> all() {
        List<Release> r = rendered.get();
        if (r == null) {
            r = sections.stream().map(s -> new Release(s.version(), s.date(), renderer.render(forSite(s.markdown(), assetBase), 0L).html())).toList();
            rendered.set(r);
        }
        return r;
    }

    public Optional<Release> find(String version) {
        return all().stream().filter(r -> r.version().equals(version)).findFirst();
    }

    /** 안내 주석을 빼고, GitHub 원본 사진 주소를 이 사이트의 사본 주소로 바꾼다 */
    static String forSite(String markdown, String assetBase) {
        String noComments = HTML_COMMENT.matcher(markdown).replaceAll("");
        return RAW_ASSET.matcher(noComments).replaceAll(Matcher.quoteReplacement(assetBase));
    }

    private static String load() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        if (!res.exists()) return "";
        try (InputStream in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** "## [1.2.3] - 2026-10-08" 머리말마다 나눈다. 첫 머리말 앞(파일 소개)은 버린다. */
    static List<Section> parse(String changelog) {
        List<Section> out = new ArrayList<>();
        String version = null;
        String date = null;
        StringBuilder body = new StringBuilder();
        for (String line : changelog.split("\\R", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                if (version != null) out.add(new Section(version, date, body.toString().strip()));
                version = m.group(1);
                date = m.group(2);
                body.setLength(0);
            } else if (version != null) {
                body.append(line).append('\n');
            }
        }
        if (version != null) out.add(new Section(version, date, body.toString().strip()));
        return List.copyOf(out);
    }
}
