package com.team.blog.notification.application;

import java.util.Map;

/**
 * 바깥 메신저(텔레그램 023, 디스코드 078)로 보내는 알림 한 줄 문구. 서식 없는 글자만 만든다.
 * 화면 알림(015)과 같은 정보만 쓴다: 볼 수 없는 글이면 제목·링크가 없다.
 */
public final class NotificationText {
    private static final Map<String, String> REASONS = Map.of("SPAM", "스팸·광고", "ABUSE", "욕설·혐오", "SEXUAL", "음란·선정",
            "PRIVACY", "개인정보 노출", "COPYRIGHT", "저작권 침해", "OTHER", "기타");

    private NotificationText() {}

    /** @return 보낼 문구. 받는 사람에게 보여 줄 것이 없으면 null */
    public static String of(NotificationQuery.Item n, String baseUrl) {
        String who = who(n.actor());
        if (n.othersCount() > 0) who = who.substring(0, who.length() - 1) + " 외 " + n.othersCount() + "명이";
        String title = n.post() != null && n.post().readable() ? ": 「" + n.post().title() + "」" : "";
        String text = switch (n.type()) {
            case COMMENT -> "💬 " + who + " 내 글에 댓글을 남겼어요" + title + quote(n.commentPreview());
            case REPLY -> "↩️ " + who + " 내 댓글에 답글을 남겼어요" + title + quote(n.commentPreview());
            case LIKE -> "❤️ " + who + " 내 글을 좋아해요" + title;
            case FOLLOW -> "👤 " + who + " 나를 팔로우했어요";
            case NEW_POST -> title.isEmpty() ? null : "📝 " + who + " 새 글을 올렸어요" + title;
            case REPORT_RESOLVED -> "ACTION_TAKEN".equals(n.result())
                    ? "🛡️ 신고하신 내용을 검토해 조치했어요. 알려 주셔서 고마워요."
                    : "🛡️ 신고하신 내용을 검토했지만 운영 정책 위반은 아니었어요.";
            case CONTENT_HIDDEN -> hidden(n);
            case INQUIRY_ANSWERED -> "📮 남기신 문의에 답변이 왔어요" + (n.inquiry() == null ? "" : ": 「" + n.inquiry().title() + "」");
            case FRIEND_REQUEST -> "🤝 " + who + " 친구 요청을 보냈어요";
            case FRIEND_ACCEPTED -> "🤝 " + who + " 친구 요청을 수락했어요";
            case AI_PROPOSAL -> "🤖 AI가 글을 제안했어요" + (n.proposal() == null ? "" : ": 「" + n.proposal().title() + "」");
        };
        if (text == null) return null;
        return n.link() == null ? text : text + "\n" + baseUrl + n.link();
    }

    private static String who(NotificationQuery.Actor actor) {
        if (actor == null) return "누군가가";
        return actor.withdrawn() ? "탈퇴한 사용자가" : actor.nickname() + "님이";
    }

    private static String hidden(NotificationQuery.Item n) {
        NotificationQuery.Hidden h = n.hidden();
        String what = h != null && "COMMENT".equals(h.targetType()) ? "내 댓글이" : "내 글이";
        if (h == null || !h.stillHidden()) return "⚠️ " + what + " 운영 정책에 따라 숨겨졌었어요 (지금은 다시 보여요).";
        return "⚠️ " + what + " 운영 정책에 따라 숨겨졌어요 (사유: " + REASONS.getOrDefault(h.reason(), "기타") + ").";
    }

    private static String quote(String preview) {
        return preview == null || preview.isBlank() ? "" : "\n“" + preview + "”";
    }
}
