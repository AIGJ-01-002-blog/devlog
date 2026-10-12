package com.team.blog.media.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * S3 저장소가 MinIO가 받는 모양으로 요청하는지 본다: 경로 방식 주소(/버킷/키), 서명, 형식·캐시 머리,
 * 그리고 옛 MinIO가 모르는 체크섬 트레일러(STREAMING-…-TRAILER)를 쓰지 않는지.
 */
class S3ObjectStorageTest {
    record Seen(String method, String path, String contentType, String cacheControl, String auth, String payloadMode) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes(); // 본문은 읽어 비우기만 한다
            var h = ex.getRequestHeaders();
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), h.getFirst("Content-Type"),
                    h.getFirst("Cache-Control"), h.getFirst("Authorization"), h.getFirst("x-amz-content-sha256")));
            ex.getResponseHeaders().add("ETag", "\"abc\"");
            int code = ex.getRequestMethod().equals("DELETE") ? 204 : 200;
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 경로_방식으로_올리고_지운다() {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        ObjectStorage storage = new StorageConfig().objectStorage(endpoint, "us-east-1", "blog-images", "ak", "sk", "unused");
        assertThat(storage).isInstanceOf(S3ObjectStorage.class);

        storage.put("profiles/2026/10/a.webp", new byte[]{1, 2, 3, 4}, "image/webp");
        storage.delete("profiles/2026/10/a.webp");

        assertThat(seen).hasSize(2);
        Seen put = seen.get(0);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/blog-images/profiles/2026/10/a.webp");
        assertThat(put.contentType()).isEqualTo("image/webp");
        assertThat(put.cacheControl()).contains("immutable");
        assertThat(put.auth()).startsWith("AWS4-HMAC-SHA256 Credential=ak/");
        // 서명된 청크(옛 MinIO도 받는다)는 되지만 체크섬 트레일러는 쓰지 않는다
        assertThat(put.payloadMode()).doesNotContain("TRAILER");
        assertThat(seen.get(1).method()).isEqualTo("DELETE");
        assertThat(seen.get(1).path()).isEqualTo("/blog-images/profiles/2026/10/a.webp");
    }

    @Test
    void 주소가_없으면_로컬_폴더를_쓰고_폴더_밖_키는_막는다(@TempDir Path dir) {
        ObjectStorage storage = new StorageConfig().objectStorage("", "us-east-1", "b", "", "", dir.toString());
        storage.put("profiles/x/a.png", new byte[]{9}, "image/png");
        assertThat(storage.get("profiles/x/a.png")).hasValueSatisfying(o -> {
            assertThat(o.data()).containsExactly(9);
            assertThat(o.contentType()).isEqualTo("image/png");
        });
        storage.delete("profiles/x/a.png");
        storage.delete("profiles/x/a.png");
        assertThat(storage.get("profiles/x/a.png")).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.put("../escape.png", new byte[]{1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
