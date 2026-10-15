package com.team.blog.telegram.infra;

import java.util.List;
import java.util.Optional;

/** 텔레그램 Bot API 중 쓰는 것만 (023). 실패는 예외 대신 결과로 돌려 부르는 쪽이 계속 돌게 한다. */
public interface TelegramApi {
    /** 봇 사용자 이름(@ 없이). 확인할 수 없으면 비어 있다 */
    Optional<String> botUsername();

    /** @param offset 다음에 받을 업데이트 번호. @return 받은 업데이트(없으면 빈 목록). 호출이 실패하면 비어 있다 */
    Optional<List<Update>> updates(long offset);

    SendResult send(long chatId, String text);

    /** 받은 글자 메시지 하나. 글자가 아닌 업데이트는 text가 null이다 */
    record Update(long updateId, Long chatId, String chatType, String text, String firstName) {}

    enum SendResult {
        OK,
        /** 봇이 차단되었거나 대화가 없다: 연결을 지운다 */
        GONE,
        FAILED
    }
}
