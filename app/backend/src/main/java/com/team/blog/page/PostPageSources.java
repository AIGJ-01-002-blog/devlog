package com.team.blog.page;

import org.springframework.stereotype.Component;

import com.team.blog.comment.application.CommentQuery;
import com.team.blog.discovery.application.FeedQuery;
import com.team.blog.discovery.application.PostDetailQuery;
import com.team.blog.trending.application.TrendingService;

/** 글·피드 화면이 읽는 곳 (PageController) */
@Component
record PostPageSources(FeedQuery feed, PostDetailQuery details, CommentQuery comments, TrendingService trending) {
}
