package com.team.blog.mcp.application;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.team.blog.media.ImageInspector;
import com.team.blog.media.PostImages;
import com.team.blog.shared.error.ApiException;

/**
 * AI가 올리는 본문 사진 (060). 웹은 브라우저가 사진을 줄이고 사진 정보를 지운 원본과 썸네일을 보내지만, AI는 파일을 그대로 보낸다.
 * 그래서 서버가 브라우저 몫을 대신한다: 긴 변 1920px로 줄이고 다시 저장해 사진 정보(EXIF 등)를 지우고, 가로 640px 썸네일을 만든 뒤
 * 웹과 같은 {@link PostImages#upload}로 올린다. 형식·용량·장수·저장 공간 규칙은 웹과 같다.
 * <p>
 * 큰 파일을 도구 인자(base64)로 보내기 어려운 AI를 위해 한 번만 쓰는 올리기 주소(10분)도 만든다. 주소의 표는 Redis에 두고 쓰는 즉시 지운다.
 */
@Service
public class McpImages {
    private static final Logger log = LoggerFactory.getLogger(McpImages.class);
    static final Duration TICKET_TTL = Duration.ofMinutes(10);
    /** base64로 받는 사진 상한. 더 크면 올리기 주소를 쓰라고 안내한다 */
    static final int MAX_INLINE_BYTES = 2 * 1024 * 1024;
    private static final String IMAGE_TYPE = "IMAGE_TYPE";
    private static final String TICKET_KEY = "mcp:upload:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PostImages images;
    private final StringRedisTemplate redis;

    public McpImages(PostImages images, StringRedisTemplate redis) {
        this.images = images;
        this.redis = redis;
    }

    public record Uploaded(String url, String markdown, int width, int height) {}

    public record Ticket(long memberId, String alt) {}

    /** base64 사진을 올린다. data: 주소 머리(data:image/png;base64,)가 붙어 있어도 받는다. */
    public Uploaded uploadBase64(long memberId, String base64, String alt) {
        String raw = base64.strip();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
        if (raw.length() > MAX_INLINE_BYTES / 3 * 4 + 8) throw inlineTooLarge();
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("IMAGE_BASE64", "image_base64를 읽을 수 없어요. 사진 파일을 base64로 바꾼 값을 넣어 주세요.");
        }
        if (bytes.length > MAX_INLINE_BYTES) throw inlineTooLarge();
        return upload(memberId, bytes, alt);
    }

    /** 사진 바이트를 브라우저처럼 다듬어 올리고 본문에 넣을 Markdown을 돌려준다. */
    public Uploaded upload(long memberId, byte[] original, String alt) {
        if (original.length > PostImages.MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_TOO_LARGE", "사진은 10MB까지 올릴 수 있어요.");
        }
        ImageInspector.ImageInfo info = ImageInspector.inspect(original)
                .orElseThrow(() -> ApiException.badRequest(IMAGE_TYPE, "jpg·png·gif 사진만 올릴 수 있어요."));
        if (info.contentType().equals("image/webp")) {
            throw ApiException.badRequest(IMAGE_TYPE, "AI 연결로는 jpg·png·gif만 올릴 수 있어요. webp는 png로 바꿔 올리거나 devlog 편집 화면에서 올려 주세요.");
        }
        Prepared p = prepare(original, info);
        PostImages.Uploaded up = images.upload(memberId, p.image(), p.thumb());
        return new Uploaded(up.url(), "![" + cleanAlt(alt) + "](" + up.url() + ")", up.width(), up.height());
    }

    /** 한 번만 쓰는 올리기 표. Redis에 둘 수 없으면 만들지 않는다. */
    public String createTicket(long memberId, String alt) {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        try {
            redis.opsForValue().set(TICKET_KEY + ticket, memberId + "\n" + cleanAlt(alt), TICKET_TTL);
        } catch (RuntimeException e) {
            log.warn("사진 올리기 주소를 만들지 못했습니다: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_TICKET_UNAVAILABLE",
                    "지금은 올리기 주소를 만들 수 없어요. 잠시 뒤 다시 시도하거나 작은 사진은 upload_image로 바로 보내 주세요.");
        }
        return ticket;
    }

    /** 표를 꺼내면서 지운다. 없거나 이미 쓴 표면 빈 값 */
    public Optional<Ticket> useTicket(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{32}")) return Optional.empty();
        String v;
        try {
            v = redis.opsForValue().getAndDelete(TICKET_KEY + ticket);
        } catch (RuntimeException e) {
            log.warn("사진 올리기 주소를 확인하지 못했습니다: {}", e.getMessage());
            return Optional.empty();
        }
        if (v == null) return Optional.empty();
        int nl = v.indexOf('\n');
        try {
            return Optional.of(new Ticket(Long.parseLong(nl < 0 ? v : v.substring(0, nl)), nl < 0 ? "" : v.substring(nl + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private record Prepared(byte[] image, byte[] thumb) {
        // 배열은 기본 record 비교가 주소만 보므로 내용으로 비교한다
        @Override
        public boolean equals(Object o) {
            return o instanceof Prepared(byte[] otherImage, byte[] otherThumb) && Arrays.equals(image, otherImage) && Arrays.equals(thumb, otherThumb);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(image) + Arrays.hashCode(thumb);
        }

        @Override
        public String toString() {
            return "Prepared[" + image.length + " bytes, thumb " + thumb.length + " bytes]";
        }
    }

    private static Prepared prepare(byte[] original, ImageInspector.ImageInfo info) {
        boolean gif = info.contentType().equals("image/gif");
        if (gif && (info.width() > PostImages.MAX_EDGE || info.height() > PostImages.MAX_EDGE)) {
            // 움직이는 GIF를 줄이면 장면이 사라지니 웹과 같이 거절한다
            throw ApiException.badRequest("GIF_DIMENSION", "GIF는 가로·세로 1920px까지 올릴 수 있어요.");
        }
        if (info.width() > PostImages.MAX_PIXELS_EDGE || info.height() > PostImages.MAX_PIXELS_EDGE) {
            throw ApiException.badRequest("IMAGE_DIMENSION", "사진 해상도가 너무 커요.");
        }
        BufferedImage decoded = decode(original, Math.max(info.width(), info.height()));
        boolean png = info.contentType().equals("image/png");
        byte[] thumb = encode(scale(decoded, PostImages.THUMB_WIDTH, Integer.MAX_VALUE), png || gif ? "png" : "jpg");
        // GIF는 원본 그대로 둔다(사진 정보가 없고 장면을 지켜야 한다). 나머지는 다시 저장해 사진 정보를 지운다
        byte[] image = original;
        if (!gif) image = encode(scale(decoded, PostImages.MAX_EDGE, PostImages.MAX_EDGE), png ? "png" : "jpg");
        return new Prepared(image, thumb);
    }

    /** 큰 사진은 읽을 때부터 건너뛰며 읽어(subsampling) 메모리를 아낀다. 첫 장면만 읽는다. */
    private static BufferedImage decode(byte[] bytes, int longEdge) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw unreadable();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                // 읽은 그림의 긴 변이 1920px의 두 배를 넘지 않게 건너뛴다
                int step = Math.max(1, longEdge / PostImages.MAX_EDGE);
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage img = reader.read(0, param);
                if (img == null) throw unreadable();
                return img;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) throw api;
            throw unreadable();
        }
    }

    /** 가로 maxW, 세로 maxH 안에 들어가게 비율대로 줄인다. 이미 작으면 그대로. 투명도는 지킨다. */
    static BufferedImage scale(BufferedImage src, int maxW, int maxH) {
        double ratio = Math.min(1.0, Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * ratio));
        int h = Math.max(1, (int) Math.round(src.getHeight() * ratio));
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 사진 정보 없이 다시 저장한다. ImageIO 기본 저장은 EXIF·XMP·텍스트 덩어리를 쓰지 않는다. */
    static byte[] encode(BufferedImage img, String format) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (format.equals("png")) {
                if (!ImageIO.write(img, "png", bytes)) throw unreadable();
                return bytes.toByteArray();
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.9f);
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw unreadable();
        }
    }

    /** 대체 문구는 한 줄, 대괄호 없이, 100자까지 */
    static String cleanAlt(String alt) {
        if (alt == null) return "";
        String s = alt.replaceAll("[\\r\\n\\[\\]]", " ").replaceAll("\\s+", " ").strip();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static ApiException unreadable() {
        return ApiException.badRequest(IMAGE_TYPE, "사진 파일을 읽을 수 없어요. 손상되지 않은 jpg·png·gif인지 확인해 주세요.");
    }

    private static ApiException inlineTooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMAGE_INLINE_TOO_LARGE",
                "image_base64로는 2MB까지 보낼 수 있어요. 더 큰 사진은 create_image_upload_link로 올리기 주소를 받아 올려 주세요.");
    }
}
