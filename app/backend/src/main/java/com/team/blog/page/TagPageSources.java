package com.team.blog.page;

import org.springframework.stereotype.Component;

import com.team.blog.search.application.SearchQuery;
import com.team.blog.search.web.SearchIpLimit;
import com.team.blog.tag.application.TagQuery;
import com.team.blog.tag.web.TagController;

/** 태그·검색 화면이 읽는 곳 (PageController) */
@Component
record TagPageSources(TagQuery tags, TagController tagApi, SearchQuery search, SearchIpLimit searchLimit) {
}
