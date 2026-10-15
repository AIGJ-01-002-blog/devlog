package com.team.blog.page;

import org.springframework.stereotype.Component;

import com.team.blog.account.application.MemberAbout;
import com.team.blog.follow.application.FollowQuery;
import com.team.blog.portfolio.application.PortfolioQuery;
import com.team.blog.series.application.SeriesQuery;

/** 블로그 안 화면(팔로우·시리즈·소개·포트폴리오)이 읽는 곳 (PageController) */
@Component
record BlogPageSources(FollowQuery follows, SeriesQuery series, MemberAbout about, PortfolioQuery portfolios) {
}
