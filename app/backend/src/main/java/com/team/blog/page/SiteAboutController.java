package com.team.blog.page;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.shared.config.BlogProperties;

/** 사이트 소개·연락처·포트폴리오 화면 데이터 (077, spec 107). 화면 안에서 옮겨 와 서버가 첫 화면 데이터를 넣지 못했을 때 React가 읽는다. */
@RestController
public class SiteAboutController {
    private final SiteOwner owner;
    private final BlogProperties.Site site;
    private final SitePortfolio portfolio;

    public SiteAboutController(SiteOwner owner, BlogProperties props, SitePortfolio portfolio) {
        this.owner = owner;
        this.site = props.site();
        this.portfolio = portfolio;
    }

    /** @param contactEmail·contactUrl 로그인 없이 연락하는 방법 (spec 104). 설정이 비면 null */
    public record SiteAbout(String ownerHandle, String contactEmail, String contactUrl) {}

    @GetMapping("/api/site/about")
    public SiteAbout about() {
        return new SiteAbout(owner.handle().orElse(null), site.contactEmailOrNull(), site.contactUrlOrNull());
    }

    /** 사이트 포트폴리오 본문 (spec 107). 연락처 화면도 같은 소개 데이터(/api/site/about)를 읽는다 */
    public record SitePortfolioBody(String ownerHandle, String html) {}

    @GetMapping("/api/site/portfolio")
    public SitePortfolioBody portfolio() {
        return new SitePortfolioBody(owner.handle().orElse(null), portfolio.html());
    }
}
