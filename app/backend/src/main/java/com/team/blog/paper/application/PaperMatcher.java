package com.team.blog.paper.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지정가 체결기 (#332). 미체결 지정가가 있을 때만 시세를 받아(5초 캐시를 함께 쓴다) 닿은 주문을 체결한다.
 * 회원이 화면을 열거나 주문을 낼 때도 같은 셈으로 바로 체결하므로, 이 바퀴는 화면을 닫아 둔 회원 몫이다.
 */
@Component
class PaperMatcher {
    private static final Logger log = LoggerFactory.getLogger(PaperMatcher.class);

    private final PaperTrading paper;

    PaperMatcher(PaperTrading paper) {
        this.paper = paper;
    }

    @Scheduled(fixedDelayString = "${blog.paper.match-interval:10s}", initialDelayString = "${blog.paper.match-interval:10s}")
    void run() {
        try {
            int n = paper.matchAll();
            if (n > 0) log.info("지정가 {}건 체결", n);
        } catch (RuntimeException e) {
            // 한 바퀴가 실패해도 다음 바퀴는 돈다
            log.warn("지정가 체결 바퀴 실패: {}", e.getMessage());
        }
    }
}
