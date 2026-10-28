package com.team.blog.shorts.autoupload;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** YouTube Data API v3와 Google OAuth (spec 108). 시험은 가짜 서버로 HttpYouTubeApi를 부르거나 이 인터페이스를 가짜로 바꾼다 */
public interface YouTubeApi {
    /** 영상 올리기(youtube.upload)와 재생목록 읽기·넣기(youtube) */
    List<String> SCOPES = List.of("https://www.googleapis.com/auth/youtube.upload", "https://www.googleapis.com/auth/youtube");

    /** refreshToken은 갱신 응답에 없으면 null(전 값을 그대로 쓴다) */
    record Tokens(String accessToken, String refreshToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "Tokens[expiresAt=" + expiresAt + "]";
        }
    }

    record Channel(String id, String title) {}

    /** @param publishAt 공개 예약 시각. null이면 비공개로만 둔다(시험 올리기) */
    record Video(String title, String description, List<String> tags, Instant publishAt) {}

    String authorizeUrl(String redirectUri, String state);

    Tokens exchange(String code, String redirectUri);

    Tokens refresh(String refreshToken);

    Channel channel(String accessToken);

    /** @return 영상 ID */
    String upload(String accessToken, byte[] video, Video meta);

    /** 내 채널에서 제목이 꼭 같은 재생목록. 만들지는 않는다 */
    Optional<String> findPlaylist(String accessToken, String title);

    void addToPlaylist(String accessToken, String playlistId, String videoId);
}
