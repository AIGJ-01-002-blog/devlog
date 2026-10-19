package com.team.blog.page;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.config.BlogProperties;

/** 사이트 소개 화면 데이터 (077). 화면 안에서 옮겨 와 서버가 첫 화면 데이터를 넣지 못했을 때 React가 읽는다. */
@RestController
public class SiteAboutController {
    private final SiteOwner owner;
    private final BlogProperties.Site site;

    public SiteAboutController(SiteOwner owner, BlogProperties props) {
        this.owner = owner;
        this.site = props.site();
    }

    /** @param contactEmail·contactUrl 로그인 없이 연락하는 방법 (spec 104). 설정이 비면 null */
    public record SiteAbout(String ownerHandle, String contactEmail, String contactUrl) {}

    @GetMapping("/api/site/about")
    public SiteAbout about() {
        return new SiteAbout(owner.handle().orElse(null), site.contactEmailOrNull(), site.contactUrlOrNull());
    }
}
