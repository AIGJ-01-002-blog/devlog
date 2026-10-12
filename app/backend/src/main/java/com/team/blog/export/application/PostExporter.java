package com.team.blog.export.application;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.team.blog.account.application.MemberQueryService;
import com.team.blog.post.query.AuthorPostQuery;
import com.team.blog.series.application.SeriesQuery;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.tag.application.TagQuery;

/**
 * 내 글 내보내기 (059). 휴지통에 없는 내 글을 글마다 Markdown 파일 하나로 묶어 zip으로 준다.
 * 파일 맨 위에 YAML 머리말(제목·날짜·태그·시리즈·주소)을 붙여 다른 블로그(Hugo·Jekyll·Gatsby)로 옮기기 쉽게 한다.
 * 발행한 글은 독자가 보는 발행본을, 임시글은 마지막으로 저장한 내용을 담는다. 사진은 주소 그대로 둔다.
 */
@Service
public class PostExporter {
    private final AuthorPostQuery posts;
    private final TagQuery tags;
    private final SeriesQuery series;
    private final MemberQueryService members;
    private final String baseUrl;

    public PostExporter(AuthorPostQuery posts, TagQuery tags, SeriesQuery series, MemberQueryService members, BlogProperties props) {
        this.posts = posts;
        this.tags = tags;
        this.series = series;
        this.members = members;
        String b = props.site().baseUrl() == null ? "" : props.site().baseUrl();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    record Row(long id, String title, String contentMd, String summary, String status, String visibility,
               Instant createdAt, Instant publishedAt, Instant editedAt, String series) {}

    /** 내보낼 글이 몇 개인지 (화면 안내용). */
    public int count(long memberId) {
        return posts.liveCount(memberId);
    }

    public void writeZip(long memberId, OutputStream out) throws IOException {
        String handle = members.findById(memberId).map(MemberQueryService.MemberSummary::handle).orElseThrow(NotFoundException::new);
        List<AuthorPostQuery.Source> sources = posts.all(memberId);
        List<Long> ids = sources.stream().map(AuthorPostQuery.Source::id).toList();
        Map<Long, String> seriesNames = series.seriesNamesOf(ids);
        Map<Long, List<String>> tagNames = tags.tagsOf(ids);
        List<Row> rows = sources.stream().map(p -> new Row(p.id(), p.title(), p.contentMd(), p.summary(), p.status(), p.visibility(),
                p.createdAt(), p.publishedAt(), p.editedAt(), seriesNames.get(p.id()))).toList();

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            Set<String> used = new HashSet<>();
            List<String> index = new ArrayList<>();
            for (Row r : rows) {
                boolean published = "PUBLISHED".equals(r.status());
                String name = unique((published ? "posts/" : "drafts/") + fileName(r), used);
                put(zip, name, markdown(r, tagNames.getOrDefault(r.id(), List.of()), handle, published));
                // 파일 이름에 괄호·공백이 들어가도 링크가 끊기지 않게 <주소>로 감싼다.
                index.add("- [" + (r.title().isBlank() ? "(제목 없음)" : r.title().replace("]", "\\]")) + "](<" + name + ">)"
                        + (published ? "" : " — 임시글"));
            }
            put(zip, "README.md", readme(handle, rows.size(), index));
        }
    }

    String markdown(Row r, List<String> tags, String handle, boolean published) {
        StringBuilder b = new StringBuilder("---\n");
        b.append("title: ").append(yaml(r.title())).append('\n');
        if (r.publishedAt() != null) b.append("date: ").append(r.publishedAt()).append('\n');
        else b.append("date: ").append(r.createdAt()).append('\n');
        if (r.editedAt() != null) b.append("updated: ").append(r.editedAt()).append('\n');
        b.append("status: ").append(published ? "published" : "draft").append('\n');
        b.append("visibility: ").append(r.visibility().toLowerCase()).append('\n');
        b.append("tags: [").append(tags.stream().map(PostExporter::yaml).collect(Collectors.joining(", "))).append("]\n");
        if (r.series() != null) b.append("series: ").append(yaml(r.series())).append('\n');
        if (r.summary() != null) b.append("summary: ").append(yaml(r.summary())).append('\n');
        if (published) b.append("url: ").append(yaml(baseUrl + "/@" + handle + "/posts/" + r.id())).append('\n');
        b.append("---\n\n");
        b.append(r.contentMd());
        if (!r.contentMd().endsWith("\n")) b.append('\n');
        return b.toString();
    }

    private static String readme(String handle, int count, List<String> index) {
        return "# @" + handle + " 글 모음\n\n"
                + "devlog에서 내보낸 글 " + count + "개예요. 발행한 글은 posts/, 임시글은 drafts/에 있어요.\n"
                + "발행한 글을 고치는 중이면 독자가 보는 발행본을 담았어요. 사진은 블로그 주소를 그대로 가리켜요.\n\n"
                + String.join("\n", index) + (index.isEmpty() ? "" : "\n");
    }

    /** 날짜-번호-제목.md. 운영체제가 막는 글자는 빼고, 제목은 40자까지만 쓴다. */
    static String fileName(Row r) {
        Instant at = r.publishedAt() != null ? r.publishedAt() : r.createdAt();
        String title = r.title().replaceAll("[\\\\/:*?\"<>|#%\\p{Cntrl}]", "").strip().replaceAll("\\s+", "-");
        if (title.codePointCount(0, title.length()) > 40) title = title.substring(0, title.offsetByCodePoints(0, 40));
        title = title.replaceAll("^[.-]+", "").replaceAll("[.-]+$", "");
        return at.toString().substring(0, 10) + "-" + r.id() + (title.isEmpty() ? "" : "-" + title) + ".md";
    }

    private static String unique(String name, Set<String> used) {
        String n = name;
        for (int i = 2; !used.add(n); i++) n = name.replaceFirst("\\.md$", "-" + i + ".md");
        return n;
    }

    /** YAML 큰따옴표 문자열 (JSON 문자열과 같은 이스케이프). */
    static String yaml(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
