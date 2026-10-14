package com.team.blog.shorts.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ShortsProperties.class)
class ShortsConfig {}
