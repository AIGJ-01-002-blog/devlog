package com.team.blog.shared.markdown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.team.blog.shared.config.BlogProperties;

/**
 * 본문 HTML을 만드는 유일한 곳 (docs/12 §2). 발행·미리보기·다시 렌더링 배치가 모두 이것을 쓴다.
 * ① commonmark + GFM 파싱 → ② AST 변환(제목 한 단계 낮춤, 우리 사진 판별, 외부·남의 사진은 링크로, 중첩 깊이 검사)
 * → ③ HTML 렌더링(직접 쓴 HTML은 글자로) → ④ OWASP 허용 목록 정화. 전체 1초 제한.
 * scripts/sanitize/Pipeline.java(검증된 참고 구현)와 같은 규칙이다.
 */
@Component
public class ContentRenderer {
    /** 렌더링·정화 규칙을 바꾸면 올린다. 배치가 render_version이 낮은 글을 다시 렌더링한다 (docs/12 §7-7). */
    public static final int RENDER_VERSION = 3; // 2: GIF는 첫 장면 + 원본 링크 (009), 3: 사진 가로·세로 (032)

    private static final String INPUT = "input";
    private static final List<Extension> EXT = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(),
            AutolinkExtension.create(), HeadingAnchorExtension.builder().idPrefix("h-").build());
    private static final Parser PARSER = Parser.builder().extensions(EXT).build();
    private static final Pattern HEADING_ID = Pattern.compile("h-[\\p{L}\\p{N}_-]{1,100}");
    private static final Pattern LANG = Pattern.compile("language-[a-z0-9+#-]{1,20}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    static final String GIF_LABEL = "움직이는 이미지 재생";
    /** 릴리스 노트 사진 경로 (093). 빌드가 저장소의 assets/releases/를 jar의 static/release-assets/로 옮긴다 */
    public static final String RELEASE_ASSETS = "/release-assets/";

    private final ImageUrls imageUrls;
    private final ImageOwnership ownership;
    private final String siteBase;
    private final PolicyFactory policy;
    /** 릴리스 노트 사진(093)으로 받는 주소: 이 사이트의 /release-assets/<폴더>/<파일>. 폴더·파일 이름은 영숫자로 시작해 ..이 될 수 없다 */
    private final Pattern releaseAsset;
    private final int maxDepth;
    private final long timeoutMillis;
    private final int excerptLength;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ContentRenderer(ImageUrls imageUrls, ImageOwnership ownership, BlogProperties props,
                           @Value("${blog.site.base-url:http://localhost:8080}") String siteBase) {
        this.imageUrls = imageUrls;
        this.ownership = ownership;
        this.siteBase = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        this.maxDepth = props.post().maxNestingDepth();
        this.timeoutMillis = props.post().renderTimeout().toMillis();
        this.excerptLength = props.post().excerptLength();
        String imgPrefix = imageUrls.publicBaseUrl() + "/";
        // 릴리스 노트 사진(093)은 jar에 함께 실려 이 사이트의 /release-assets/에서 나간다
        this.releaseAsset = Pattern.compile(Pattern.quote(this.siteBase + RELEASE_ASSETS)
                + "[A-Za-z0-9][\\w.-]{0,40}/[A-Za-z0-9][\\w.-]{0,80}\\.(?:webp|png|jpe?g|gif)");
        this.policy = new HtmlPolicyBuilder()
                .allowElements("p", "br", "hr", "blockquote", "h2", "h3", "h4", "h5", "h6", "strong", "em", "del",
                        "ul", "ol", "li", INPUT, "code", "pre", "table", "thead", "tbody", "tr", "th", "td", "a", "img")
                .allowAttributes("id").matching(HEADING_ID).onElements("h2", "h3", "h4", "h5", "h6")
                .allowAttributes("start").matching(Pattern.compile("\\d{1,6}")).onElements("ol")
                .allowAttributes("type").matching(Pattern.compile("checkbox")).onElements(INPUT)
                .allowAttributes("checked", "disabled").onElements(INPUT)
                .allowAttributes("class").matching(LANG).onElements("code")
                .allowAttributes("align").matching(Pattern.compile("left|center|right")).onElements("th", "td")
                .allowUrlProtocols("http", "https", "mailto")
                .allowAttributes("href", "title").onElements("a")
                .allowAttributes("target").matching(Pattern.compile("_blank")).onElements("a")
                .allowAttributes("rel").matching(Pattern.compile("noopener noreferrer nofollow ugc")).onElements("a")
                .allowAttributes("src").matching((el, attr, v) -> v.startsWith(imgPrefix) || releaseAsset.matcher(v).matches() ? v : null).onElements("img")
                .allowAttributes("alt", "title").onElements("img")
                .allowAttributes("loading").matching(Pattern.compile("lazy")).onElements("img")
                .allowAttributes("decoding").matching(Pattern.compile("async")).onElements("img")
                .allowAttributes("width", "height").matching(Pattern.compile("[1-9]\\d{0,4}")).onElements("img")
                .toFactory();
    }

    /**
     * 사진 주소별 원본 크기를 받아 렌더러를 만든다. 가로·세로를 적어 두면 브라우저가 사진을 받기 전에 자리를 잡아
     * 글을 읽는 중에 아래 내용이 밀려 내려가지 않는다(032). 크기를 모르는 사진(남의 사진 링크, GIF 첫 장면)은 적지 않는다.
     */
    private HtmlRenderer htmlRenderer(Map<String, ImageOwnership.OwnedImage> sizeByUrl) {
        AttributeProvider attrs = (node, tag, a) -> {
            if (node instanceof Link && isExternal(a.get("href"))) {
                a.put("target", "_blank");
                a.put("rel", "noopener noreferrer nofollow ugc");
            }
            if (node instanceof Image) {
                a.put("loading", "lazy");
                a.put("decoding", "async");
                ImageOwnership.OwnedImage size = sizeByUrl.get(a.get("src"));
                if (size != null && size.hasSize()) {
                    a.put("width", String.valueOf(size.width()));
                    a.put("height", String.valueOf(size.height()));
                }
            }
        };
        return HtmlRenderer.builder().extensions(EXT)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .attributeProviderFactory(ctx -> attrs)
                .build();
    }

    /**
     * @param authorId 사진 주인 판별 기준 (발행·다시 렌더링은 글 작성자, 미리보기는 로그인한 본인)
     * @throws ContentTooComplexException 중첩 20단계 초과 또는 1초 초과
     */
    public RenderedContent render(String markdown, long authorId) {
        String md = markdown == null ? "" : markdown;
        Future<RenderedContent> f = executor.submit(() -> doRender(md, authorId));
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ContentTooComplexException();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ContentTooComplexException c) throw c;
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentTooComplexException();
        }
    }

    /** 목록 요약: 작성자가 쓴 짧은 소개(spec 045)가 있으면 그대로, 없으면 원문 앞부분으로 계산한다. */
    public String summary(String written, String markdownHead) {
        return written != null ? written : excerpt(markdownHead);
    }

    /**
     * 목록 요약만 만든다 (V3: 요약을 저장하지 않고 content_md 앞부분으로 계산). HTML을 만들지 않아 가볍다.
     * 잘린 원문이라 코드 블록이 열린 채 끝나도 코드는 요약에서 빠지므로 결과가 안전하다.
     */
    public String excerpt(String markdownHead) {
        if (markdownHead == null || markdownHead.isBlank()) return null;
        String text = excerptOf(PARSER.parse(markdownHead));
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * 검색 결과 문장용 본문 글자 (014 FR-018). 요약과 달리 코드 블록·표 글자도 넣는다(개발 글은 코드에서도 찾는다, FR-010).
     * 사진은 빼고 공백은 하나로 줄인다. 결과는 HTML이 아닌 글자라 화면에 쓰기 전에 이스케이프해야 한다.
     */
    public String searchText(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        PARSER.parse(markdown).accept(new AbstractVisitor() {
            @Override public void visit(FencedCodeBlock n) { sb.append(' ').append(n.getLiteral()).append(' '); }
            @Override public void visit(IndentedCodeBlock n) { sb.append(' ').append(n.getLiteral()).append(' '); }
            @Override public void visit(Image n) { /* 사진은 뺀다 */ }
            @Override public void visit(HtmlBlock n) { sb.append(' ').append(n.getLiteral()).append(' '); }
            @Override public void visit(HtmlInline n) { sb.append(n.getLiteral()); }
            @Override public void visit(Text t) { sb.append(t.getLiteral()); }
            @Override public void visit(Code c) { sb.append(c.getLiteral()); }
            @Override public void visit(SoftLineBreak n) { sb.append(' '); }
            @Override public void visit(HardLineBreak n) { sb.append(' '); }
            @Override public void visit(Paragraph p) { visitChildren(p); sb.append(' '); }
            @Override public void visit(Heading h) { visitChildren(h); sb.append(' '); }
            @Override public void visit(CustomBlock b) { visitChildren(b); sb.append(' '); }
        });
        return SPACES.matcher(sb).replaceAll(" ").strip();
    }

    private RenderedContent doRender(String md, long authorId) {
        Node doc = PARSER.parse(md);
        // 본문의 우리 사진 키를 모아 작성자가 올린 것인지 한 번에 확인한다
        List<String> keysInOrder = new ArrayList<>();
        doc.accept(new AbstractVisitor() {
            @Override
            public void visit(Image img) {
                String key = imageUrls.keyOf(img.getDestination());
                if (key != null) keysInOrder.add(key);
                visitChildren(img);
            }
        });
        Map<String, ImageOwnership.OwnedImage> owned = keysInOrder.isEmpty() ? Map.of() : ownership.ownedBy(authorId, new LinkedHashSet<>(keysInOrder));
        List<String> ownedInOrder = new ArrayList<>(new LinkedHashSet<>(keysInOrder.stream().filter(owned::containsKey).toList()));

        transform(doc, owned);
        String excerpt = excerptOf(doc);
        Map<String, ImageOwnership.OwnedImage> sizeByUrl = new HashMap<>();
        owned.forEach((key, image) -> sizeByUrl.put(imageUrls.urlOf(key), image));
        String html = policy.sanitize(htmlRenderer(sizeByUrl).render(doc));
        return new RenderedContent(html, excerpt, List.copyOf(ownedInOrder), RENDER_VERSION);
    }

    private void transform(Node doc, Map<String, ImageOwnership.OwnedImage> owned) {
        doc.accept(new AbstractVisitor() {
            int depth;

            void nested(Node n) {
                if (++depth > maxDepth) throw new ContentTooComplexException();
                visitChildren(n);
                depth--;
            }

            @Override public void visit(BlockQuote n) { nested(n); }
            @Override public void visit(BulletList n) { nested(n); }
            @Override public void visit(OrderedList n) { nested(n); }

            @Override
            public void visit(Heading h) {
                h.setLevel(Math.min(h.getLevel() + 1, 6));
                visitChildren(h);
            }

            @Override
            public void visit(Image img) {
                String key = imageUrls.keyOf(img.getDestination());
                if (key != null && owned.containsKey(key)) {
                    String original = imageUrls.urlOf(key); // 옛 주소로 쓴 사진도 지금 공개 주소로
                    String thumb = owned.get(key).thumbKey();
                    if (key.endsWith(".gif") && thumb != null && !(img.getParent() instanceof Link)) {
                        // GIF: 처음엔 첫 장면 + 원본 링크. 화면 스크립트가 누르면 재생으로 바꾸고, 스크립트가 없으면 새 탭에서 원본 (FR-030)
                        String alt = textOf(img);
                        String label = alt.isBlank() ? GIF_LABEL : alt;
                        Link link = new Link(original, label);
                        Image still = new Image(imageUrls.urlOf(thumb), null);
                        still.appendChild(new Text(label));
                        link.appendChild(still);
                        img.insertAfter(link);
                        img.unlink();
                        return;
                    }
                    img.setDestination(original);
                    return;
                }
                if (releaseAsset.matcher(img.getDestination()).matches()) return; // 릴리스 노트 사진은 그대로 그린다
                Link link = new Link(img.getDestination(), img.getTitle());
                String alt = textOf(img);
                link.appendChild(new Text("[이미지] " + (alt.isBlank() ? img.getDestination() : alt)));
                img.insertAfter(link);
                img.unlink();
            }
        });
    }

    /** 요약: 코드 블록·이미지·표를 빼고 글자만, 공백 정리 후 앞 200자(단어 중간이면 그 앞에서). */
    private String excerptOf(Node doc) {
        StringBuilder sb = new StringBuilder();
        doc.accept(new AbstractVisitor() {
            @Override public void visit(FencedCodeBlock n) { /* 코드 블록은 요약에서 뺀다 */ }
            @Override public void visit(IndentedCodeBlock n) { /* 코드 블록은 요약에서 뺀다 */ }
            @Override public void visit(Image n) { /* 사진은 뺀다 */ }
            @Override public void visit(HtmlBlock n) { sb.append(' ').append(n.getLiteral()).append(' '); }
            @Override public void visit(HtmlInline n) { sb.append(n.getLiteral()); }
            @Override public void visit(Text t) { sb.append(t.getLiteral()); }
            @Override public void visit(Code c) { sb.append(c.getLiteral()); }
            @Override public void visit(SoftLineBreak n) { sb.append(' '); }
            @Override public void visit(HardLineBreak n) { sb.append(' '); }
            @Override public void visit(Paragraph p) { visitChildren(p); sb.append(' '); }
            @Override public void visit(Heading h) { visitChildren(h); sb.append(' '); }
            @Override public void visit(CustomBlock b) { if (!(b instanceof TableBlock)) { visitChildren(b); sb.append(' '); } }
        });
        String text = SPACES.matcher(sb).replaceAll(" ").strip();
        if (text.length() <= excerptLength) return text;
        String cut = text.substring(0, excerptLength);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > excerptLength / 2 && !Character.isWhitespace(text.charAt(excerptLength))) cut = cut.substring(0, lastSpace);
        return cut.strip();
    }

    private boolean isExternal(String href) {
        if (href == null) return false;
        return !(href.startsWith("/") || href.startsWith("#") || href.startsWith("mailto:")
                || href.equals(siteBase) || href.startsWith(siteBase + "/"));
    }

    static String textOf(Node n) {
        StringBuilder sb = new StringBuilder();
        n.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                sb.append(t.getLiteral());
            }
        });
        return sb.toString();
    }
}
