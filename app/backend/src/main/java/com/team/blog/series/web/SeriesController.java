package com.team.blog.series.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.post.access.Viewer;
import com.team.blog.series.application.SeriesQuery;
import com.team.blog.series.application.SeriesProjects;
import com.team.blog.series.application.SeriesService;
import com.team.blog.series.application.SeriesSubscriptions;
import com.team.blog.shared.error.ApiException;
import com.team.blog.shared.error.NotFoundException;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/** 시리즈 (024). 읽기는 보는 사람마다 달라 공유 캐시에 두지 않고(private), 친구·주인 응답은 어디에도 저장하지 않는다 (docs/06 R-5). */
@RestController
public class SeriesController {
    private final SeriesQuery query;
    private final SeriesService service;
    private final SeriesSubscriptions subscriptions;
    private final SeriesProjects projects;

    public SeriesController(SeriesQuery query, SeriesService service, SeriesSubscriptions subscriptions, SeriesProjects projects) {
        this.query = query;
        this.service = service;
        this.subscriptions = subscriptions;
        this.projects = projects;
    }

    public record NameRequest(String name) {}

    public record AssignRequest(Long seriesId) {}

    public record OrderRequest(List<Long> postIds) {}

    @GetMapping("/api/members/{handle}/series")
    public ResponseEntity<List<SeriesQuery.Summary>> list(@PathVariable String handle,
                                                         @CurrentMember(required = false) MemberPrincipal me) {
        SeriesQuery.Listing l = query.list(handle, me == null ? null : me.id()).orElseThrow(NotFoundException::new);
        return ResponseEntity.ok().cacheControl(privateOrNoStore(l.personal())).body(l.items());
    }

    @GetMapping("/api/members/{handle}/series/{slug}")
    public ResponseEntity<SeriesQuery.Detail> detail(@PathVariable String handle, @PathVariable String slug,
                                                     @CurrentMember(required = false) MemberPrincipal me) {
        SeriesQuery.Detail d = query.detail(handle, slug, me == null ? null : me.id()).orElseThrow(NotFoundException::new);
        return ResponseEntity.ok().cacheControl(privateOrNoStore(d.personal())).body(d);
    }

    /** 글 상세의 시리즈 상자. 시리즈에 없으면 본문 없이 204. */
    @GetMapping("/api/posts/{postId}/series")
    public ResponseEntity<SeriesQuery.Navigation> forPost(@PathVariable String postId,
                                                          @CurrentMember(required = false) MemberPrincipal me) {
        long id = parseId(postId);
        return query.forPost(id, Viewer.of(me))
                .map(n -> ResponseEntity.ok().cacheControl(privateOrNoStore(!n.publiclyVisible())).body(n))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    /** 포트폴리오 프로젝트 칸 (072 3단계). 주인만 */
    @GetMapping("/api/me/series/{seriesId}/project")
    public ResponseEntity<SeriesProjects.Fields> project(@CurrentMember MemberPrincipal me, @PathVariable String seriesId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(projects.get(me.id(), parseId(seriesId)));
    }

    @PutMapping("/api/me/series/{seriesId}/project")
    public SeriesProjects.Fields saveProject(@CurrentMember MemberPrincipal me, @PathVariable String seriesId,
                                             @RequestBody SeriesProjects.Fields body) {
        return projects.save(me.id(), parseId(seriesId), body);
    }

    /** 새 글 알림 받기 (072) */
    @PutMapping("/api/series/{seriesId}/subscription")
    public ResponseEntity<Void> subscribe(@CurrentMember MemberPrincipal me, @PathVariable String seriesId) {
        subscriptions.subscribe(me.id(), parseId(seriesId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/series/{seriesId}/subscription")
    public ResponseEntity<Void> unsubscribe(@CurrentMember MemberPrincipal me, @PathVariable String seriesId) {
        subscriptions.unsubscribe(me.id(), parseId(seriesId));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/posts/{postId}/series")
    public ResponseEntity<Void> assign(@CurrentMember MemberPrincipal me, @PathVariable String postId,
                                       @RequestBody AssignRequest body) {
        if (body == null) throw ApiException.badRequest("INVALID_REQUEST", "시리즈를 확인해 주세요.");
        service.assign(me.id(), parseId(postId), body.seriesId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me/series")
    public ResponseEntity<List<SeriesService.Mine>> mine(@CurrentMember MemberPrincipal me) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mine(me.id()));
    }

    @PostMapping("/api/me/series")
    public SeriesService.Mine create(@CurrentMember MemberPrincipal me, @RequestBody NameRequest body) {
        return service.create(me.id(), body == null ? null : body.name());
    }

    @PatchMapping("/api/me/series/{seriesId}")
    public SeriesService.Mine rename(@CurrentMember MemberPrincipal me, @PathVariable String seriesId,
                                     @RequestBody NameRequest body) {
        return service.rename(me.id(), parseId(seriesId), body == null ? null : body.name());
    }

    @DeleteMapping("/api/me/series/{seriesId}")
    public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable String seriesId) {
        service.delete(me.id(), parseId(seriesId));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/me/series/{seriesId}/posts")
    public ResponseEntity<Void> reorder(@CurrentMember MemberPrincipal me, @PathVariable String seriesId,
                                        @RequestBody OrderRequest body) {
        service.reorder(me.id(), parseId(seriesId), body == null ? null : body.postIds());
        return ResponseEntity.noContent().build();
    }

    private static CacheControl privateOrNoStore(boolean personal) {
        return personal ? CacheControl.noStore().cachePrivate() : CacheControl.noCache().cachePrivate();
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.matches("[1-9]\\d{0,17}")) throw new NotFoundException();
        return Long.parseLong(raw);
    }
}
