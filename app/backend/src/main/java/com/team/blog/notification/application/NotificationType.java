package com.team.blog.notification.application;

import java.util.EnumSet;
import java.util.Set;

/** 알림 종류 (015 FR-002). 운영 알림(신고 결과·숨김·문의 답변 054)은 끌 수 없다(FR-035). AI 글 제안(071)과 친구 요청·수락(A-1)은 끌 수 있다. */
public enum NotificationType {
    COMMENT, REPLY, LIKE, FOLLOW, NEW_POST, REPORT_RESOLVED, CONTENT_HIDDEN, INQUIRY_ANSWERED, AI_PROPOSAL, FRIEND_REQUEST, FRIEND_ACCEPTED;

    private static final Set<NotificationType> MUTABLE_TYPES = EnumSet.of(COMMENT, REPLY, LIKE, FOLLOW, NEW_POST, AI_PROPOSAL, FRIEND_REQUEST, FRIEND_ACCEPTED);

    public boolean mutable() {
        return MUTABLE_TYPES.contains(this);
    }
}
