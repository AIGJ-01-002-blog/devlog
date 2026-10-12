package com.team.blog.media;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 올라온 사진의 실제 형식과 크기를 파일 머리에서 읽는다 (docs/23 §3, 005 FR-012). 선언된 Content-Type은 믿지 않는다.
 * 디코딩하지 않고 머리만 읽으므로 큰 픽셀 수로 메모리를 터뜨리는 사진에도 안전하다.
 * 위치 정보 같은 사진 정보(EXIF·XMP)가 들어 있는지도 알려준다.
 */
public final class ImageInspector {
    private static final String WEBP = "image/webp";

    private ImageInspector() {}

    public record ImageInfo(String contentType, String extension, int width, int height, boolean hasMetadata) {}

    public static Optional<ImageInfo> inspect(byte[] b) {
        if (b == null || b.length < 16) return Optional.empty();
        try {
            if (startsWith(b, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) return png(b);
            if (startsWith(b, 0, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(b, 0, "GIF89a".getBytes(StandardCharsets.US_ASCII))) return gif(b);
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return jpeg(b);
            if (startsWith(b, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && startsWith(b, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) return webp(b);
        } catch (ArrayIndexOutOfBoundsException e) {
            return Optional.empty(); // 머리가 잘린 파일
        }
        return Optional.empty();
    }

    private static Optional<ImageInfo> png(byte[] b) {
        if (!startsWith(b, 12, "IHDR".getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        int w = be32(b, 16);
        int h = be32(b, 20);
        boolean meta = false;
        int pos = 8;
        while (pos + 8 <= b.length) {
            int len = be32(b, pos);
            if (len < 0) return Optional.empty();
            String type = new String(b, pos + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("eXIf") || type.equals("iTXt") || type.equals("tEXt") || type.equals("zTXt")) meta = true;
            if (type.equals("IEND")) break;
            pos += 12 + len;
        }
        return valid("image/png", "png", w, h, meta);
    }

    private static Optional<ImageInfo> gif(byte[] b) {
        return valid("image/gif", "gif", le16(b, 6), le16(b, 8), false);
    }

    private static Optional<ImageInfo> jpeg(byte[] b) {
        int pos = 2;
        boolean meta = false;
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) return Optional.empty();
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // 채움 바이트
            } else if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
            } else {
                int len = be16(b, pos + 2);
                if (len < 2) return Optional.empty();
                if (marker == 0xE1) meta = true; // APP1: EXIF·XMP
                boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) return valid("image/jpeg", "jpg", be16(b, pos + 7), be16(b, pos + 5), meta);
                if (marker == 0xDA || marker == 0xD9) return Optional.empty(); // 크기 정보 전에 그림이 시작됨
                pos += 2 + len;
            }
        }
        return Optional.empty();
    }

    private static Optional<ImageInfo> webp(byte[] b) {
        String chunk = new String(b, 12, 4, StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8 " -> {
                if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) return Optional.empty();
                return valid(WEBP, "webp", le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF, false);
            }
            case "VP8L" -> {
                if ((b[20] & 0xFF) != 0x2F) return Optional.empty();
                int bits = (b[21] & 0xFF) | (b[22] & 0xFF) << 8 | (b[23] & 0xFF) << 16 | (b[24] & 0xFF) << 24;
                return valid(WEBP, "webp", (bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1, false);
            }
            case "VP8X" -> {
                int flags = b[20] & 0xFF;
                boolean meta = (flags & 0x08) != 0 || (flags & 0x04) != 0; // EXIF·XMP
                int w = ((b[24] & 0xFF) | (b[25] & 0xFF) << 8 | (b[26] & 0xFF) << 16) + 1;
                int h = ((b[27] & 0xFF) | (b[28] & 0xFF) << 8 | (b[29] & 0xFF) << 16) + 1;
                return valid(WEBP, "webp", w, h, meta);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * GIF 프레임 수 (009 FR-029). 그림을 풀지 않고 블록 머리만 따라가며 이미지 서술자를 센다.
     * @return 프레임 수, 형식이 깨졌으면 -1. limit을 넘으면 그 즉시 limit + 1을 돌려준다
     */
    public static int gifFrames(byte[] b, int limit) {
        try {
            int pos = 13;
            int flags = b[10] & 0xFF;
            if ((flags & 0x80) != 0) pos += 3 * (1 << ((flags & 0x07) + 1));
            int frames = 0;
            while (pos < b.length) {
                int block = b[pos] & 0xFF;
                if (block == 0x3B) return frames; // 끝
                switch (block) {
                    case 0x21 -> pos = skipSubBlocks(b, pos + 2); // 확장 블록
                    case 0x2C -> { // 이미지 서술자 = 한 장면
                        if (++frames > limit) return frames;
                        int f = b[pos + 9] & 0xFF;
                        pos += 10;
                        if ((f & 0x80) != 0) pos += 3 * (1 << ((f & 0x07) + 1));
                        pos = skipSubBlocks(b, pos + 1); // LZW 최소 코드 크기 다음부터 데이터
                    }
                    default -> {
                        return -1;
                    }
                }
                if (pos < 0) return -1;
            }
            return frames > 0 ? frames : -1; // 끝 표시가 없어도 읽은 장면까지는 인정한다
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static int skipSubBlocks(byte[] b, int pos) {
        while (true) {
            int len = b[pos] & 0xFF;
            pos += 1;
            if (len == 0) return pos;
            pos += len;
            if (pos > b.length) return -1;
        }
    }

    private static Optional<ImageInfo> valid(String type, String ext, int w, int h, boolean meta) {
        if (w <= 0 || h <= 0) return Optional.empty();
        return Optional.of(new ImageInfo(type, ext, w, h, meta));
    }

    private static boolean startsWith(byte[] b, int offset, byte[] prefix) {
        if (b.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[offset + i] != prefix[i]) return false;
        return true;
    }

    private static int be32(byte[] b, int p) {
        return (b[p] & 0xFF) << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | (b[p + 3] & 0xFF);
    }

    private static int be16(byte[] b, int p) {
        return (b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF);
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
    }
}
