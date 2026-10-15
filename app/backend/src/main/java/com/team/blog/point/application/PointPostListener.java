package com.team.blog.point.application;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.team.blog.post.application.PostEvents;

/**
 * 글이 처음으로 전체 공개되면 글 발행 크레딧을 적립한다 (#266). 글마다 한 번뿐인 사건(PostFirstPublic)을 들어서,
 * 공개 범위를 바꿨다 되돌리거나 다시 발행해도 두 번 받지 않는다.
 * 발행 트랜잭션 안에서 미지급 목록(outbox)에 넣고, 커밋 뒤 알림과 같은 실행기에서 바로 지급한다. 그 사이에 서버가 멈춰도
 * {@link PointOutbox}가 1분마다 남은 줄을 지급하므로 크레딧이 빠지지 않는다 (블로그 주인 결정 "outbox 방식").
 */
@Component
class PointPostListener {
    private final PointOutbox outbox;

    PointPostListener(PointOutbox outbox) {
        this.outbox = outbox;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueue(PostEvents.PostFirstPublic e) {
        outbox.enqueue(e.authorId(), e.postId());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener
    public void deliver(PostEvents.PostFirstPublic e) {
        try {
            outbox.deliver(e.postId());
        } catch (RuntimeException ex) {
            // 포인트를 못 줬다고 발행이 실패하지는 않는다. 줄이 남아 있어 작업이 다시 지급한다
            outbox.failed(e.postId(), ex);
        }
    }
}
