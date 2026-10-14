package com.team.blog.dashboard.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.team.blog.dashboard.application.MyDashboard;
import com.team.blog.shared.security.CurrentMember;
import com.team.blog.shared.security.MemberPrincipal;

/** 내 대시보드 (#239). GET /api/me/dashboard?days=7|30|90 (그 밖의 값은 30일) */
@RestController
public class MyDashboardController {
    private final MyDashboard dashboard;

    public MyDashboardController(MyDashboard dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/me/dashboard")
    public ResponseEntity<MyDashboard.Dashboard> get(@CurrentMember MemberPrincipal me, @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dashboard.of(me.id(), days));
    }
}
