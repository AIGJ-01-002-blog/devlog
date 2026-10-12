package com.team.blog.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.team.blog.account.web.ActivityInterceptor;
import com.team.blog.shared.security.CurrentMemberArgumentResolver;
import com.team.blog.shared.security.ResilientSessionFilter;

@Configuration
@EnableScheduling
public class WebConfig implements WebMvcConfigurer {
    private final CurrentMemberArgumentResolver currentMemberResolver;
    private final ActivityInterceptor activityInterceptor;

    public WebConfig(CurrentMemberArgumentResolver currentMemberResolver, ActivityInterceptor activityInterceptor) {
        this.currentMemberResolver = currentMemberResolver;
        this.activityInterceptor = activityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activityInterceptor).excludePathPatterns("/assets/**", "/media/**", "/actuator/**");
    }

    /**
     * 화면 빌드 결과(/assets)는 파일 이름에 내용 해시가 붙어 내용이 바뀌면 이름도 바뀐다. 그래서 한 번 받은 파일은
     * 1년 동안 다시 묻지 않게 한다. 이 설정이 없으면 Spring Security 기본값(no-store)이 붙어 화면을 열 때마다
     * JS·CSS를 모두 다시 받았다.
     * <p>
     * 이름이 고정된 파일도 no-store였다. theme.js는 화면을 그리기 전에 받는 스크립트라 매번 새로 받으면 첫 화면이 그만큼 늦는다.
     * theme.js는 바뀌면 바로 보여야 하니 "가진 것을 쓰기 전에 확인"(no-cache, 바뀌지 않았으면 304)으로, 아이콘·공유 그림은 하루 동안 쓴다.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        // 릴리스 노트 사진(093). 같은 버전 폴더의 사진은 릴리스 뒤에 바뀌지 않는다
        registry.addResourceHandler("/release-assets/**")
                .addResourceLocations("classpath:/static/release-assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        registry.addResourceHandler("/theme.js")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache().cachePublic());
        registry.addResourceHandler("/favicon.svg", "/apple-touch-icon.png", "/og-default.png")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentMemberResolver);
    }

    /** Spring Session 필터 바로 뒤: 세션 저장소 장애 시 비로그인 처리. */
    @Bean
    FilterRegistrationBean<ResilientSessionFilter> resilientSessionFilter() {
        FilterRegistrationBean<ResilientSessionFilter> reg = new FilterRegistrationBean<>(new ResilientSessionFilter());
        reg.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
        return reg;
    }
}
