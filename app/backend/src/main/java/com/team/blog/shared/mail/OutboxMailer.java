package com.team.blog.shared.mail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 개발·테스트용: 실제로 보내지 않고 최근 메일 100통을 메모리에 둔다. 링크(토큰)가 들어 있으므로 본문은 로그에 남기지 않는다.
 * 개발용 화면(/api/dev/mails)에서 본문을 볼 수 있다.
 */
public class OutboxMailer implements Mailer {
    private static final Logger log = LoggerFactory.getLogger(OutboxMailer.class);
    private static final int KEEP = 100;
    private final Deque<Mail> outbox = new ArrayDeque<>();

    @Override
    public synchronized void send(Mail mail) {
        if (outbox.size() == KEEP) outbox.removeFirst();
        outbox.addLast(mail);
        if (log.isInfoEnabled()) log.info("메일 보관(발송 안 함): to={} subject={}", mask(mail.to()), mail.subject());
    }

    /** 최근 것부터. */
    public synchronized List<Mail> recent() {
        return List.copyOf(outbox.reversed());
    }

    public synchronized void clear() {
        outbox.clear();
    }

    static String mask(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
