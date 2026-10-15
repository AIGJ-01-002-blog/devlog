package com.team.blog.media;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 첨부파일 형식 검사 (022 FR-002). 확장자와 실제 내용(파일 머리·압축 목록)이 모두 맞아야 받는다.
 * 문서(docx·xlsx·pptx)는 zip 안에 [Content_Types].xml과 종류별 폴더(word/·xl/·ppt/)가 있어야 한다.
 * 글자 파일(txt·md·csv)은 NUL 바이트가 없고 알려진 이진 형식이 아니어야 한다.
 */
final class FileInspector {
    /** 확장자 → 저장할 형식. 이 목록 밖은 받지 않는다. */
    static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf",
            "zip", "application/zip",
            "txt", "text/plain",
            "md", "text/markdown",
            "csv", "text/csv",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Map<String, String> OFFICE_DIRS = Map.of("docx", "word/", "xlsx", "xl/", "pptx", "ppt/");
    /** 압축 목록은 이만큼만 훑는다. */
    private static final int MAX_ENTRIES = 10_000;

    enum Result { OK, NOT_ALLOWED, IMAGE, MISMATCH }

    private FileInspector() {}

    static Optional<String> extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return Optional.empty();
        return Optional.of(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static Result check(String extension, byte[] b) {
        if (ImageInspector.inspect(b).isPresent()) return Result.IMAGE;
        if (extension == null || !TYPES.containsKey(extension)) return Result.NOT_ALLOWED;
        boolean ok = switch (extension) {
            case "pdf" -> startsWith(b, "%PDF-".getBytes());
            case "zip" -> isZip(b);
            case "docx", "xlsx", "pptx" -> isZip(b) && officeEntries(b, OFFICE_DIRS.get(extension));
            default -> isText(b);
        };
        return ok ? Result.OK : Result.MISMATCH;
    }

    private static boolean isZip(byte[] b) {
        return startsWith(b, new byte[] {'P', 'K', 3, 4}) || startsWith(b, new byte[] {'P', 'K', 5, 6});
    }

    /**
     * 압축 끝의 중앙 목록(central directory)에서 이름만 읽는다. 내용을 풀지 않으므로 압축 폭탄이어도 비용이 없다.
     */
    private static boolean officeEntries(byte[] b, String dir) {
        int eocd = findEndRecord(b);
        if (eocd < 0) return false;
        int count = u16(b, eocd + 10);
        long offset = u32(b, eocd + 16);
        boolean types = false;
        boolean folder = false;
        int p = (int) Math.min(offset, Integer.MAX_VALUE);
        for (int n = 0; n < Math.min(count, MAX_ENTRIES); n++) {
            if (!isCentralHeader(b, p)) return false;
            int nameLen = u16(b, p + 28);
            int extraLen = u16(b, p + 30);
            int commentLen = u16(b, p + 32);
            if (p + 46 + nameLen > b.length) return false;
            String name = new String(b, p + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals("[Content_Types].xml")) types = true;
            if (name.startsWith(dir)) folder = true;
            if (types && folder) return true;
            p += 46 + nameLen + extraLen + commentLen;
        }
        return false;
    }

    /** 끝 레코드(PK 5 6)의 자리. 없으면 -1 */
    private static int findEndRecord(byte[] b) {
        // 끝 레코드는 22바이트 + 주석(최대 65535바이트)
        for (int i = b.length - 22; i >= Math.max(0, b.length - 22 - 0xFFFF); i--) {
            if (b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == 5 && b[i + 3] == 6) return i;
        }
        return -1;
    }

    /** p 자리에 중앙 목록 항목(PK 1 2)이 온전히 있는가 */
    private static boolean isCentralHeader(byte[] b, int p) {
        return p >= 0 && p + 46 <= b.length && b[p] == 'P' && b[p + 1] == 'K' && b[p + 2] == 1 && b[p + 3] == 2;
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int i) {
        return u16(b, i) | (long) u16(b, i + 2) << 16;
    }

    /** 실행 파일·이진 파일이 글자 확장자로 들어오는 것을 막는다. 인코딩(UTF-8·CP949 등)은 묻지 않는다. */
    private static boolean isText(byte[] b) {
        if (isZip(b) || startsWith(b, "%PDF-".getBytes()) || startsWith(b, new byte[] {'M', 'Z'})
                || startsWith(b, new byte[] {0x7f, 'E', 'L', 'F'})) return false;
        for (byte x : b) if (x == 0) return false;
        return true;
    }

    private static boolean startsWith(byte[] b, byte[] head) {
        if (b.length < head.length) return false;
        for (int i = 0; i < head.length; i++) if (b[i] != head[i]) return false;
        return true;
    }
}
