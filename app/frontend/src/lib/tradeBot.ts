import { api } from './api'
import { t } from './i18n'

/**
 * 자동매매 모의 화면 /trade (spec 101). 서버 TradeBotSim.View와 같은 모양.
 * 봇이 MCP report_trade_bot으로 알린 모의 계좌·체결·Gemini 진입 판단·하루 요약이다. 매매 일지(/trades)와 따로 저장된다.
 */
export interface BotPosition {
  symbol: string
  /** 선물만 LONG·SHORT, 현물은 빈 문자열 */
  side: string
  quantity: number
  avgPrice: number | null
  price: number | null
  unrealizedPnl: number | null
}

export interface BotRule {
  label: string
  value: string
}

export interface BotAccount {
  account: string
  exchange: string
  market: 'SPOT' | 'FUTURES'
  currency: string
  cash: number
  unrealizedPnl: number | null
  positions: BotPosition[]
  rules: BotRule[]
  reportedAt: string
}

export interface BotEvent {
  id: number
  kind: 'FILL' | 'AI' | 'SUMMARY'
  account: string
  symbol: string
  side: string
  quantity: number | null
  price: number | null
  currency: string
  realizedPnl: number | null
  /** AI만: ALLOW 진입 허락, BLOCK 막음 */
  verdict: '' | 'ALLOW' | 'BLOCK'
  text: string
  occurredAt: string
}

export interface BotStatus {
  aiEnabled: boolean
  aiModel: string
  aiCallsToday: number
  botVersion: string
  /** 한 번도 알리지 않았으면 null */
  reportedAt: string | null
}

export interface BotView {
  status: BotStatus
  accounts: BotAccount[]
  fills: BotEvent[]
  decisions: BotEvent[]
  summaries: BotEvent[]
}

export const loadTradeBot = () => api<BotView>('/api/me/trade-bot')

/** 봇이 5분마다 도니 15분 넘게 소식이 없으면 멈춘 것으로 본다 */
export const STALE_MINUTES = 15

export function isStale(reportedAt: string | null, now = Date.now()): boolean {
  return reportedAt === null || now - new Date(reportedAt).getTime() > STALE_MINUTES * 60_000
}

/** 계좌 파일 이름 trade-state-binance-futures-15m.json에서 봉 간격을 읽는다. 없으면 null */
export function accountInterval(account: string): string | null {
  const m = /-(1m|3m|5m|15m|30m|1h|2h|4h|1d)(?:\.json)?$/.exec(account)
  return m ? m[1] : null
}

/** 체결 방향 이름. 현물 BUY·SELL, 선물 LONG·SHORT·CLOSE */
export function sideLabel(side: string): string {
  const names: Record<string, string> = { BUY: t('매수'), SELL: t('매도'), LONG: t('롱 진입'), SHORT: t('숏 진입'), CLOSE: t('청산') }
  return names[side] ?? side
}

/** 사들이는 쪽(초록)인가 */
export function sideUp(side: string): boolean {
  return side === 'BUY' || side === 'LONG'
}

/** 한국 시간 10. 15. 13:05 */
export function eventTime(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
  }).format(new Date(iso))
}
