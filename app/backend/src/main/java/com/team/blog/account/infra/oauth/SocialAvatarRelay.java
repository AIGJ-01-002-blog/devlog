package com.team.blog.account.infra.oauth;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.team.blog.account.application.SocialAvatar;
import com.team.blog.media.ImageInspector;

/**
 * 가입 대기 중인 사람의 소셜 사진을 서버가 대신 받아 준다 (080 FR-012, 005 FR-020의 예외).
 * 카카오 사진 서버는 다른 사이트가 사진을 캔버스로 가공하는 걸 허락하지 않아(CORS) 브라우저가 직접 복사할 수 없다.
 * 소셜 인증이 돌려준 주소만, 허용된 사진 서버 세 곳의 https 주소만, 넘겨주기(redirect) 없이 받는다. 저장하지 않고 그대로 돌려준다.
 */
@Component
public class SocialAvatarRelay {
    private static final Logger log = LoggerFactory.getLogger(SocialAvatarRelay.class);
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 주소 하나를 받아 본문을 돌려준다. 200이 아니면 빈 값. 시험에서 바꿔 끼운다. */
    interface Download {
        Optional<byte[]> get(URI uri) throws IOException, InterruptedException;
    }

    public record Image(byte[] data, String contentType) {
        // 배열은 기본 record 비교가 주소만 보므로 내용으로 비교한다
        @Override
        public boolean equals(Object o) {
            return o instanceof Image(byte[] otherData, String otherType) && Arrays.equals(data, otherData) && Objects.equals(contentType, otherType);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(data) + Objects.hashCode(contentType);
        }

        @Override
        public String toString() {
            return "Image[" + (data == null ? 0 : data.length) + " bytes, " + contentType + "]";
        }
    }

    private final Download download;

    @Autowired
    public SocialAvatarRelay() {
        this(httpDownload(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build()));
    }

    SocialAvatarRelay(Download download) {
        this.download = download;
    }

    /** @return 사진이면 그 내용과 실제 형식. 허용되지 않은 주소이거나 받지 못했거나 사진이 아니면 빈 값 */
    public Optional<Image> fetch(String rawUrl) {
        String url = SocialAvatar.safeUrl(rawUrl);
        if (url == null) return Optional.empty();
        try {
            return download.get(URI.create(url))
                    .flatMap(data -> ImageInspector.inspect(data).map(info -> new Image(data, info.contentType())));
        } catch (IOException | IllegalArgumentException e) {
            log.info("소셜 사진을 받지 못함: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Download httpDownload(HttpClient client) {
        return uri -> {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "image/*").GET().build();
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() != 200) return Optional.empty();
                byte[] data = in.readNBytes(MAX_BYTES + 1);
                return data.length > MAX_BYTES ? Optional.empty() : Optional.of(data);
            }
        };
    }
}
