import { api } from './api'
import { num } from './dashboard'
import { t } from './i18n'

/** 매매 일지 (#243). 서버 TradeJournal과 같은 모양. 금액은 원 단위 숫자 */
export type TradePeriod = 7 | 30 | 90
export const TRADE_PERIODS: TradePeriod[] = [7, 30, 90]

export interface TradeSummary {
  trades: number
  buys: number
  sells: number
  wins: number
  losses: number
  realizedPnl: number
  fees: number
}

export interface TradeFill {
  id: number
  externalId: string
  mode: 'PAPER' | 'LIVE'
  symbol: string
  symbolName: string
  side: 'BUY' | 'SELL'
  quantity: number
  price: number
  fee: number
  realizedPnl: number | null
  strategy: string
  reason: string
  executedAt: string
}

export interface TradeDayRow {
  date: string
  summary: TradeSummary
  cumulativePnl: number
  memo: boolean
  postId: number | null
  shortsId: number | null
}

export interface TradeOverview {
  from: string
  to: string
  days: TradeDayRow[]
  total: TradeSummary
  cumulativePnl: number
  live: boolean
}

export interface TradeDay {
  date: string
  fills: TradeFill[]
  summary: TradeSummary
  cumulativePnl: number
  memo: string
  postId: number | null
  shortsId: number | null
}

export const MEMO_MAX = 2000

export const loadTrades = (days: TradePeriod) => api<TradeOverview>(`/api/me/trades?days=${days}`)
export const loadTradeDay = (date: string) => api<TradeDay>(`/api/me/trades/${date}`)
export const saveTradeMemo = (date: string, memo: string) =>
  api<TradeDay>(`/api/me/trades/${date}/memo`, { method: 'PUT', body: { memo } })
export const createTradeDraft = (date: string) => api<TradeDay>(`/api/me/trades/${date}/draft`, { method: 'POST' })
export const createTradeShorts = (date: string) => api<TradeDay>(`/api/me/trades/${date}/shorts`, { method: 'POST' })
export const deleteTradeFill = (id: number) => api<void>(`/api/me/trades/fills/${id}`, { method: 'DELETE' })

export function tradePeriodOf(raw: string | null): TradePeriod {
  return TRADE_PERIODS.find((p) => String(p) === raw) ?? 30
}

/** 2026-10-14처럼 적힌 날짜인가 */
export function isDate(raw: string | null): raw is string {
  return raw !== null && /^\d{4}-\d{2}-\d{2}$/.test(raw)
}

/** 원 단위 금액. sign이면 이익에 +를 붙인다 (색만으로 구분하지 않게) */
export function won(n: number, sign = false): string {
  const v = Math.round(n)
  const body = t('{0}원', { 0: num(Math.abs(v)) })
  if (v < 0) return `-${body}`
  return sign && v > 0 ? `+${body}` : body
}

/** 손익의 색 이름: 이익 good, 손해 bad, 0 flat */
export function pnlClass(n: number): 'good' | 'bad' | 'flat' {
  const v = Math.round(n)
  return v > 0 ? 'good' : v < 0 ? 'bad' : 'flat'
}

/** 이긴 매도 비율(%). 매도가 없으면 null */
export function winRate(s: TradeSummary): number | null {
  const decided = s.wins + s.losses
  return decided === 0 ? null : Math.round((s.wins / decided) * 100)
}

/** 체결 시각을 한국 시간 HH:mm으로 */
export function fillTime(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' }).format(new Date(iso))
}

/** 수량. 소수점 아래 0은 지운다 */
export function qty(n: number): string {
  return num(Number(n.toFixed(4)))
}
