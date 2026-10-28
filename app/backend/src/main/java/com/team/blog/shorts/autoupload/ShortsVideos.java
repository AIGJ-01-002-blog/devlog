package com.team.blog.shorts.autoupload;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.shorts.application.ShortsProperties;

/**
 * 만든 영상 파일 읽기 (spec 108). YouTube에는 바이트를 보내고, Instagram에는 바깥에서 받을 수 있는 공개 주소를 준다.
 * 로컬 저장소면 저장소에서 바로 읽고, S3(MinIO)면 공개 주소로 받는다.
 */
@Component
class ShortsVideos {
    private final ObjectStorage storage;
    private final ImageUrls urls;
    private final String siteBase;
    private final long maxBytes;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    ShortsVideos(ObjectStorage storage, ImageUrls urls, BlogProperties blog, ShortsProperties shorts) {
        this.storage = storage;
        this.urls = urls;
        this.siteBase = HttpJson.trimBase(blog.site().baseUrl());
        this.maxBytes = shorts.maxVideo().toBytes() + 1024 * 1024;
    }

    /** 공개 주소. 사이트 안 경로(/media/…)면 사이트 주소를 붙인다 */
    String publicUrl(String key) {
        String url = urls.urlOf(key);
        return url.startsWith("/") ? siteBase + url : url;
    }

    byte[] bytes(String key) {
        var stored = storage.get(key);
        if (stored.isPresent()) return stored.get().data();
        try {
            HttpResponse<byte[]> res = client.send(HttpRequest.newBuilder(URI.create(publicUrl(key))).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new PlatformException(PlatformException.Kind.RETRYABLE, "만든 영상을 저장소에서 읽지 못했어요 (HTTP " + res.statusCode() + ").");
            if (res.body().length > maxBytes) throw new PlatformException(PlatformException.Kind.PERMANENT, "영상 파일이 너무 커요.");
            return res.body();
        } catch (IOException | IllegalArgumentException e) {
            throw new PlatformException(PlatformException.Kind.RETRYABLE, "만든 영상을 저장소에서 읽지 못했어요. 잠시 뒤 다시 해 볼게요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException(PlatformException.Kind.RETRYABLE, "만든 영상을 저장소에서 읽지 못했어요. 잠시 뒤 다시 해 볼게요.");
        }
    }
}
