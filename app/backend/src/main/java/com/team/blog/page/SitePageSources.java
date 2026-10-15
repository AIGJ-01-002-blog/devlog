package com.team.blog.page;

import org.springframework.stereotype.Component;

import com.team.blog.release.ReleaseNotes;

/** 사이트 안내 화면(소개·약관·릴리스 노트)이 읽는 곳 (PageController) */
@Component
record SitePageSources(SiteOwner owner, PolicyDocuments policies, ReleaseNotes releaseNotes) {
}
