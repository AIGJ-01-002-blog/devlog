package com.team.blog.shorts.autoupload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AutoUploadProperties.class)
class AutoUploadConfig {}
