package com.team.blog.discovery.web;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.discovery.application.SiteIndex;
import com.team.blog.page.AdSense;

/** 검색 엔진이 읽는 robots.txt와 sitemap.xml, 광고 판매자 확인용 ads.txt(spec 076). 누구에게나 같으므로 공유 캐시에 둔다 */
@RestController
public class SiteIndexController {
    private static final MediaType XML = MediaType.parseMediaType("application/xml;charset=UTF-8");
    private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final SiteIndex index;
    private final AdSense adSense;

    public SiteIndexController(SiteIndex index, AdSense adSense) {
        this.index = index;
        this.adSense = adSense;
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok().contentType(TEXT).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(index.robots());
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok().contentType(XML).cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic()).body(index.sitemap());
    }

    /** 사이트맵 색인이 가리키는 글·시리즈·고정 화면 파일 (spec 107) */
    @GetMapping("/sitemaps/{part}.xml")
    public ResponseEntity<String> sitemapPart(@PathVariable String part) {
        for (SiteIndex.Part p : SiteIndex.Part.values()) {
            if (p.name().toLowerCase().equals(part)) {
                return ResponseEntity.ok().contentType(XML).cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic()).body(index.sitemap(p));
            }
        }
        return ResponseEntity.notFound().build();
    }

    /** 애드센스를 끄면 404: 빈 ads.txt는 "누구도 광고를 팔 수 없음"으로 읽힐 수 있다 */
    @GetMapping("/ads.txt")
    public ResponseEntity<String> adsTxt() {
        if (!adSense.enabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(TEXT).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(adSense.adsTxt());
    }
}
