package com.team.blog.point.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 블로그 포인트 설정 (#266, 목표 #253). devlog 2.0 묶음 릴리스 전까지 enabled=false로 화면·API를 숨긴다.
 *
 * @param enabled POINTS_ENABLED. 꺼져 있으면 /api/me/points·/api/admin/points는 없는 주소(404)이고 글 발행 적립도 하지 않는다
 */
@ConfigurationProperties(prefix = "blog.points")
public record PointProperties(@DefaultValue("false") boolean enabled) {}
