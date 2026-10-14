package com.team.blog.point.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.team.blog.post.application.PostEvents;

/**
 * 글이 처음으로 전체 공개되면 글 발행 포인트를 적립한다 (#266). 글마다 한 번뿐인 사건(PostFirstPublic)을 들어서,
 * 공개 범위를 바꿨다 되돌리거나 다시 발행해도 두 번 받지 않는다. 알림과 같은 실행기에서 커밋 뒤에 돈다.
 */
@Component
class PointPostListener {
    private static final Logger log = LoggerFactory.getLogger(PointPostListener.class);
    private final PointLedger points;

    PointPostListener(PointLedger points) {
        this.points = points;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener
    public void on(PostEvents.PostFirstPublic e) {
        try {
            points.rewardPost(e.authorId(), e.postId());
        } catch (RuntimeException ex) {
            // 포인트를 못 줬다고 발행이 실패하지는 않는다
            log.warn("글 발행 포인트를 적립하지 못했습니다 (post {}): {}", e.postId(), ex.getClass().getSimpleName());
        }
    }
}
