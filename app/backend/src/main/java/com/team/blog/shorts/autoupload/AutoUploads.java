package com.team.blog.shorts.autoupload;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.shared.config.BlogProperties;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shared.time.Times;
import com.team.blog.shorts.application.ShortsService;
import com.team.blog.shorts.application.UploadLink;
import com.team.blog.shorts.autoupload.PlatformConnections.Connection;

/**
 * 자동 올리기 설정 (spec 108 FR-001~FR-005): 계정 잇기·끊기, 시험 올리기, 켜고 끄기.
 * 쇼츠가 꺼졌거나, 토큰 열쇠·앱 값이 없거나, 쓸 수 없는 회원(기본은 관리자만)이면 모두 없는 주소(404)다.
 */
@Service
public class AutoUploads {
    private static final Logger log = LoggerFactory.getLogger(AutoUploads.class);
    static final Duration STATE_TTL = Duration.ofMinutes(10);
    /** Instagram 시험에서 컨테이너 처리를 기다리는 최대 횟수(요청 안에서 기다린다) */
    static final int TEST_POLLS = 5;

    public record PlatformState(AutoPlatform platform, boolean configured, boolean connected, String accountName, boolean enabled,
                                Instant testedAt, String testUrl, String testError, Instant tokenExpiresAt, String redirectUri) {}

    /** @param publishTime 한국 시간 HH:mm */
    public record Status(List<PlatformState> platforms, String publishTime) {}

    public record TestBody(Long shortsId) {}

    public record EnableBody(Boolean enabled) {}

    public record TestResult(AutoPlatform platform, boolean passed, String url, String message) {}

    /** 연결 시작 때 세션에 넣는 값: 시작한 회원, state, 만료. 콜백의 회원이 다르면 맞지 않는 state로 본다 */
    public record PendingState(long memberId, String state, Instant expiresAt) implements java.io.Serializable {}

    private final PlatformConnections connections;
    private final AutoUploadJobs jobs;
    private final YouTubeApi youtube;
    private final InstagramApi instagram;
    private final ShortsService shorts;
    private final ShortsVideos videos;
    private final AutoUploadProperties props;
    private final JdbcTemplate jdbc;
    private final String origin;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    AutoUploads(PlatformConnections connections, AutoUploadJobs jobs, YouTubeApi youtube, InstagramApi instagram, ShortsService shorts,
                ShortsVideos videos, AutoUploadProperties props, JdbcTemplate jdbc, BlogProperties blog, Clock clock) {
        this.connections = connections;
        this.jobs = jobs;
        this.youtube = youtube;
        this.instagram = instagram;
        this.shorts = shorts;
        this.videos = videos;
        this.props = props;
        this.jdbc = jdbc;
        this.origin = HttpJson.trimBase(blog.site().baseUrl());
        this.clock = clock;
    }

    /** 이 회원이 쓸 수 있는지. 못 쓰면 화면이 카드를 숨긴다 */
    public boolean available(MemberPrincipal me) {
        return shorts.enabled() && connections.anyConfigured() && (props.audience() == AutoUploadProperties.Audience.MEMBERS || me.isAdmin());
    }

    public void require(MemberPrincipal me) {
        if (!available(me)) throw new NotFoundException();
    }

    private AutoPlatform platform(MemberPrincipal me, String raw) {
        require(me);
        AutoPlatform p = AutoPlatform.parse(raw).orElseThrow(NotFoundException::new);
        if (!connections.configured(p)) throw new NotFoundException();
        return p;
    }

    public Status status(MemberPrincipal me) {
        require(me);
        List<Connection> mine = connections.list(me.id());
        List<PlatformState> states = java.util.Arrays.stream(AutoPlatform.values()).map(p -> {
            Optional<Connection> c = mine.stream().filter(x -> x.platform() == p).findFirst();
            return new PlatformState(p, connections.configured(p), c.isPresent(), c.map(Connection::accountName).orElse(null),
                    c.map(Connection::enabled).orElse(false), c.map(Connection::testedAt).orElse(null), c.map(Connection::testUrl).orElse(null),
                    c.map(Connection::testError).orElse(null), c.map(Connection::tokenExpiresAt).orElse(null), redirectUri(p));
        }).toList();
        return new Status(states, props.publishTime().toString());
    }

    /** 플랫폼 앱에 등록할 콜백 주소 */
    public String redirectUri(AutoPlatform p) {
        return origin + "/api/me/shorts/auto-upload/" + p.name().toLowerCase(java.util.Locale.ROOT) + "/callback";
    }

    /** 연결 시작: state를 만들고 플랫폼 동의 화면 주소를 돌려준다. state는 세션에 두고 콜백에서 한 번만 맞춰 본다 */
    public String startConnect(MemberPrincipal me, String raw, java.util.function.Consumer<PendingState> keep) {
        AutoPlatform p = platform(me, raw);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        keep.accept(new PendingState(me.id(), state, Times.now(clock).plus(STATE_TTL)));
        return p == AutoPlatform.YOUTUBE ? youtube.authorizeUrl(redirectUri(p), state) : instagram.authorizeUrl(redirectUri(p), state);
    }

    /** 콜백 결과. 화면이 code로 안내 문구를 고른다 */
    public enum ConnectResult { CONNECTED, DENIED, STATE, FAILED }

    public ConnectResult finishConnect(MemberPrincipal me, String raw, String code, String state, String error, PendingState pending) {
        AutoPlatform p = platform(me, raw);
        if (pending == null || pending.memberId() != me.id() || state == null || Times.now(clock).isAfter(pending.expiresAt())
                || !MessageDigest.isEqual(pending.state().getBytes(java.nio.charset.StandardCharsets.UTF_8), state.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ConnectResult.STATE;
        }
        if (error != null || code == null || code.isBlank()) return ConnectResult.DENIED;
        try {
            if (p == AutoPlatform.YOUTUBE) {
                YouTubeApi.Tokens t = youtube.exchange(code, redirectUri(p));
                if (t.refreshToken() == null) {
                    log.warn("YouTube가 갱신 토큰을 주지 않았습니다 (회원 {})", me.id());
                    return ConnectResult.FAILED;
                }
                YouTubeApi.Channel ch = youtube.channel(t.accessToken());
                connections.save(me.id(), p, ch.id(), ch.title(), t.accessToken(), t.refreshToken(), t.expiresAt());
            } else {
                InstagramApi.Tokens t = instagram.exchange(code, redirectUri(p));
                InstagramApi.Account a = instagram.account(t.accessToken());
                connections.save(me.id(), p, a.userId(), a.username(), t.accessToken(), null, t.expiresAt());
            }
            jobs.stopPending(me.id(), p, "계정을 다시 연결해 멈췄어요. 필요하면 [다시 올리기]를 눌러 주세요.");
            return ConnectResult.CONNECTED;
        } catch (PlatformException e) {
            log.warn("{} 연결 실패 (회원 {}): {}", p, me.id(), e.getMessage());
            return ConnectResult.FAILED;
        }
    }

    public void disconnect(MemberPrincipal me, String raw) {
        AutoPlatform p = platform(me, raw);
        connections.delete(me.id(), p);
        jobs.stopPending(me.id(), p, "연결을 끊어 올리지 않았어요.");
    }

    /** 켜기는 시험 올리기를 통과한 뒤에만 */
    public Status setEnabled(MemberPrincipal me, String raw, EnableBody body) {
        AutoPlatform p = platform(me, raw);
        if (body == null || body.enabled() == null) throw ApiException.badRequest("ENABLED_REQUIRED", "켤지 끌지 골라 주세요.");
        Connection c = connections.find(me.id(), p).orElseThrow(() -> ApiException.conflict("NOT_CONNECTED", "먼저 계정을 연결해 주세요."));
        if (body.enabled() && c.testedAt() == null) {
            throw ApiException.conflict("TEST_REQUIRED", "먼저 [시험 올리기]를 통과해야 자동 올리기를 켤 수 있어요.");
        }
        connections.setEnabled(me.id(), p, body.enabled());
        return status(me);
    }

    /**
     * 시험 올리기 (FR-004). YouTube: 고른 쇼츠를 비공개로 올린다(공개 예약 없음, 영상은 채널에 비공개로 남는다).
     * Instagram: 비공개 게시가 없어 토큰을 확인하고 컨테이너만 만든다(게시하지 않는다. 컨테이너는 24시간 뒤 사라진다).
     */
    public TestResult test(MemberPrincipal me, String raw, TestBody body) {
        AutoPlatform p = platform(me, raw);
        Connection c = connections.find(me.id(), p).orElseThrow(() -> ApiException.conflict("NOT_CONNECTED", "먼저 계정을 연결해 주세요."));
        if (body == null || body.shortsId() == null) throw ApiException.badRequest("SHORTS_REQUIRED", "시험으로 올릴 쇼츠를 골라 주세요.");
        ShortsService.Item item = shorts.get(me.id(), body.shortsId());
        String key = jdbc.query("SELECT video_key FROM shorts WHERE id = ? AND status = 'READY'", (rs, i) -> rs.getString(1), item.id())
                .stream().filter(java.util.Objects::nonNull).findFirst()
                .orElseThrow(() -> ApiException.conflict("SHORTS_NOT_READY", "영상을 다 만든 쇼츠만 시험으로 올릴 수 있어요."));
        try {
            String url = p == AutoPlatform.YOUTUBE ? testYouTube(c, item, key) : testInstagram(c, item, key);
            connections.recordTest(me.id(), p, true, url, null);
            return new TestResult(p, true, url, p == AutoPlatform.YOUTUBE
                    ? "비공개로 올렸어요. YouTube 스튜디오에서 확인한 뒤 지워도 돼요."
                    : "토큰과 영상 주소가 맞아요. 게시하지 않고 컨테이너만 만들었어요.");
        } catch (PlatformException e) {
            connections.recordTest(me.id(), p, false, null, e.getMessage());
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "AUTO_UPLOAD_TEST_FAILED", e.getMessage());
        }
    }

    private String testYouTube(Connection c, ShortsService.Item item, String key) {
        ShortsCaption.Text text = ShortsCaption.build(ShortsCaption.Input.of(item), UploadLink.Platform.YOUTUBE, origin, props.publishTime());
        String id = youtube.upload(connections.accessToken(c), videos.bytes(key), new YouTubeApi.Video(ShortsCaption.limit(text.title(), 100),
                ShortsCaption.limit(text.caption(), 5000), text.tags().stream().map(t -> t.substring(1)).toList(), null));
        return "https://youtube.com/shorts/" + id;
    }

    private String testInstagram(Connection c, ShortsService.Item item, String key) {
        String token = connections.accessToken(c);
        InstagramApi.Account a = instagram.account(token);
        if (!a.userId().equals(c.accountId())) {
            throw new PlatformException(PlatformException.Kind.AUTH, "연결한 Instagram 계정이 바뀌었어요. 다시 연결해 주세요.");
        }
        ShortsCaption.Text text = ShortsCaption.build(ShortsCaption.Input.of(item), UploadLink.Platform.INSTAGRAM, origin, props.publishTime());
        String container = instagram.createReel(token, c.accountId(), videos.publicUrl(key), ShortsCaption.limit(text.caption(), 2200));
        for (int i = 0; i < TEST_POLLS; i++) {
            InstagramApi.ContainerStatus s = instagram.status(token, container);
            if (s == InstagramApi.ContainerStatus.FINISHED) break;
            if (s == InstagramApi.ContainerStatus.ERROR || s == InstagramApi.ContainerStatus.EXPIRED) {
                throw new PlatformException(PlatformException.Kind.PERMANENT,
                        "Instagram이 영상을 처리하지 못했어요. 영상 주소(" + videos.publicUrl(key) + ")를 바깥에서 열 수 있는지 확인해 주세요.");
            }
            sleep(props.instagram().containerPoll());
        }
        // 처리 중(IN_PROGRESS)이어도 컨테이너를 받았으면 토큰·권한·영상 주소는 맞다
        return null;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<AutoUploadJobs.JobView> jobs(MemberPrincipal me, long shortsId) {
        require(me);
        shorts.get(me.id(), shortsId);
        return jobs.list(me.id(), shortsId);
    }

    public List<AutoUploadJobs.JobView> retry(MemberPrincipal me, long shortsId, String raw) {
        AutoPlatform p = platform(me, raw);
        shorts.get(me.id(), shortsId);
        jobs.retry(me.id(), shortsId, p);
        return jobs.list(me.id(), shortsId);
    }
}
