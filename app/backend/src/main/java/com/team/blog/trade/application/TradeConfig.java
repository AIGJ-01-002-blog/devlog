package com.team.blog.trade.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TradeProperties.class, PaperBotBudget.class})
class TradeConfig {}
