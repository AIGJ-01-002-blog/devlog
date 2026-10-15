# Feature Specification: 자동매매 모의 화면 (/trade)

**Feature Branch**: `101-trade-bot-page` (작업 브랜치 `claude/trade-page-wcw7qp`)
**Created**: 2026-10-15
**Status**: 1단계 Implemented (devlog 쪽 저장·화면). 봇이 알림을 보내는 쪽은 trade-bot 저장소 작업
**근거**: 2026-10-15 블로그 주인 "일단 하위 경로로 가자", 코인 모의매매 봇 스레드의 구조 선택 "규칙형".

## 고른 것과 이유

- **주소는 하위 경로 `devlog.life/trade`**(블로그 주인 결정): 로그인·메뉴·배포를 devlog와 함께 쓴다. 매매 일지(`/trades`)와 이름이 비슷하지만 일지는 날짜별 기록, 이 화면은 봇이 지금 무엇을 하는지 보는 곳이다.
- **구조는 규칙형**(블로그 주인 결정): 사용자 AI(MCP)가 매매 기준(전략·코인·봉·한도)을 정하고 가끔 직접 주문한다. 봇 코드가 그 기준대로 5분마다 주문하고 손실 상한·주문 한도를 지킨다. 무료 Gemini는 진입 직전 필터와 하루 요약만 맡는다. 주문을 LLM이 옮기면 숫자를 잘못 옮기거나 무료 한도에 걸려 주문이 빠질 수 있어서다.
- **모의 기록은 전용 표에 둔다**(기본안, 블로그 주인 확인 대기): 봇 규칙 "--dry-run 모의 기록은 운영 매매 일지에 쓰지 않는다"를 지키면서 이 화면은 모의 데이터를 보여 줘야 해서, `trade`와 섞지 않는 표 3개(`trade_bot_status`·`trade_bot_account`·`trade_bot_event`)를 따로 둔다. 공개 글·매매 일지·포인트 정산은 이 표를 읽지 않는다.
- **실계좌는 받지 않는다**: `trade-live-…` 계좌나 mode=LIVE를 보내면 거절한다. 실계좌 설정·주문은 이 화면과 상관없다.
- **화면에는 주문 버튼이 없다**: 기준을 바꾸거나 주문하는 일은 내 AI가 봇 MCP(`trade_bot_order`·`trade_bot_ai`)로 한다. 화면은 보기 전용이라 잘못 눌러 주문이 나갈 일이 없다.
- **사건은 30일만 둔다, 하루 3,000건 상한**: 5분마다 코인 5종을 돌려도 넉넉하고, 봇이 고장 나 같은 사건을 끝없이 보내도 멈춘다.

## 사용자 시나리오

1. **Given** 봇이 `report_trade_bot`으로 계좌·체결·Gemini 판단·요약을 보냈다, **When** 회원이 내 메뉴 → [자동매매 (모의)]를 열면, **Then** 마지막 알림 시각, AI 필터 켜짐/꺼짐과 오늘 판단 수, 계좌별 현금·보유 포지션·평가 손익·적용 중인 규칙, 최근 체결(이유 포함), Gemini 판단(허락·막음과 이유), 하루 요약이 보인다.
2. **Given** 봇이 15분 넘게 알리지 않았다, **Then** 마지막 알림 칸에 "15분 넘게 소식 없음"이 빨갛게 보인다.
3. **Given** 한 번도 알리지 않은 회원, **Then** 빈 화면 안내와 매매 일지 링크가 보인다.
4. **Given** 같은 사건 id를 다시 보낸다, **Then** 한 번만 남는다.
5. **Given** 실계좌 계좌나 잘못된 값이 하나라도 있다, **Then** 아무것도 남기지 않고 이유를 돌려준다.
6. **Given** 휴대폰·다크 모드, **Then** 가로로 넘치지 않고 칸이 한 줄씩 쌓인다. 배지·버튼·칸에 툴팁이 있다.

## Requirements

- **FR-001**: V48에 `trade_bot_status`(AI 필터 켜짐·모델·오늘 호출 수·봇 버전·마지막 알림), `trade_bot_account`(계좌·거래소·시장·통화·현금·평가 손익·positions·rules JSON), `trade_bot_event`(FILL·AI·SUMMARY, 회원+사건 번호 고유)를 둔다. 탈퇴 정리 때 지운다.
- **FR-002**: MCP 도구 `report_trade_bot`(쓰기 토큰, `TRADES_ENABLED`일 때만 보임). `accounts`를 주면 그 목록으로 계좌를 통째로 바꾸고, 빼면 그대로 둔다. `events`는 한 번에 200건까지.
- **FR-003**: `GET /api/me/trade-bot`은 로그인한 본인 것만 돌려준다. 체결 50건·판단 30건·요약 7건(최근 30일). `TRADES_ENABLED`가 꺼져 있으면 404.
- **FR-004**: 화면 `/trade`는 1분마다 다시 받는다. 주문·설정을 바꾸는 버튼은 없다.

## 봇이 보낼 모양 (trade-bot 저장소 작업)

```json
{"bot_version":"1.0.0",
 "ai":{"enabled":true,"model":"gemini-2.5-flash","calls_today":12},
 "accounts":[{"account":"trade-state-binance-spot-1h.json","exchange":"BINANCE","market":"spot","currency":"USDT",
   "cash":"900.5","unrealized_pnl":"-1.25",
   "positions":[{"symbol":"BTCUSDT","quantity":"0.0015","avg_price":"66000","price":"65200","unrealized_pnl":"-1.2"}],
   "rules":{"전략":"20봉 이동평균 돌파","봉":"1h","손절":"3%"}}],
 "events":[{"id":"fill:…","kind":"FILL","at":"2026-10-15T13:05:00+09:00","symbol":"BTCUSDT","side":"BUY","quantity":"0.0015","price":"66000","currency":"USDT","text":"종가가 20봉 평균 위로"},
           {"id":"ai:…","kind":"AI","verdict":"BLOCK","symbol":"DOGEUSDT","text":"급등 직후라 쉬어요"},
           {"id":"sum:2026-10-15","kind":"SUMMARY","text":"오늘 진입 2건, 막은 진입 1건"}]}
```

`accounts` 항목은 봇 MCP `trade_bot_accounts`가 돌려주는 계좌 모양과 같게 맞췄다(`entry`도 `avg_price` 대신 받는다).
