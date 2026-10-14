import { api } from './api'
import { num } from './dashboard'
import { t } from './i18n'

/**
 * 매매 일지 (#243). 서버 TradeJournal과 같은 모양.
 * 코인 거래소(#254)는 원화와 USDT가 섞이므로 손익·수수료는 통화별 금액({ KRW: 1200, USDT: 3.5 })으로 온다.
 */
export type TradePeriod = 7 | 30 | 90
export const TRADE_PERIODS: TradePeriod[] = [7, 30, 90]

/** 통화 코드 → 금액. 서버가 KRW를 먼저, 나머지는 이름 순으로 보낸다 */
export type Money = Record<string, number>

export interface TradeSummary {
  trades: number
  buys: number
  sells: number
  wins: number
  losses: number
  realizedPnl: Money
  fees: Money
}

export interface TradeFill {
  id: number
  externalId: string
  mode: 'PAPER' | 'LIVE'
  /** BINANCE·UPBIT 같은 거래소 코드. 비어 있으면 알리지 않은 기록 */
  exchange: string
  symbol: string
  symbolName: string
  side: 'BUY' | 'SELL'
  quantity: number
  price: number
  currency: string
  fee: number
  realizedPnl: number | null
  strategy: string
  reason: string
  executedAt: string
}

export interface TradeDayRow {
  date: string
  summary: TradeSummary
  cumulativePnl: Money
  memo: boolean
  postId: number | null
  shortsId: number | null
}

export interface TradeOverview {
  from: string
  to: string
  days: TradeDayRow[]
  total: TradeSummary
  cumulativePnl: Money
  live: boolean
}

export interface TradeDay {
  date: string
  fills: TradeFill[]
  summary: TradeSummary
  cumulativePnl: Money
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

/** 화면에 보이는 자릿수로 반올림: 원화는 원 단위, 다른 통화는 1 이상이면 소수 둘째 자리, 1보다 작으면 여덟째 자리 */
function rounded(n: number, currency: string): { value: number; digits: number } {
  if (currency === 'KRW') return { value: Math.round(n), digits: 0 }
  const digits = Math.abs(n) >= 1 || n === 0 ? 2 : 8
  const value = Number(n.toFixed(digits))
  // 꼬리 0은 지우되 소수 둘째 자리까지는 남긴다 (0.000123 USDT, 12.50 USDT)
  const shown = Math.max(2, Math.min(digits, (String(Math.abs(value)).split('.')[1] ?? '').length))
  return { value, digits: shown }
}

/** 금액 하나. 원화는 1,234원, 다른 통화는 12.34 USDT. sign이면 이익에 +를 붙인다 (색만으로 구분하지 않게) */
export function money(n: number, currency = 'KRW', sign = false): string {
  const { value, digits } = rounded(n, currency)
  const abs = Math.abs(value)
  const body = currency === 'KRW'
    ? t('{0}원', { 0: num(abs) })
    : `${abs.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })} ${currency}`
  if (value < 0) return `-${body}`
  return sign && value > 0 ? `+${body}` : body
}

/** 원 단위 금액 (money의 원화) */
export function won(n: number, sign = false): string {
  return money(n, 'KRW', sign)
}

/** 통화별 금액을 " · "로 잇는다. 비어 있으면 fallback 통화의 0 */
export function moneys(m: Money, fallback = 'KRW', sign = false): string {
  const entries = Object.entries(m)
  if (entries.length === 0) return money(0, fallback, sign)
  return entries.map(([c, v]) => money(v, c, sign)).join(' · ')
}

/** 기간·날의 대표 통화: 수수료가 있는 첫 통화, 없으면 원화 */
export function mainCurrency(s: TradeSummary): string {
  return Object.keys(s.fees)[0] ?? 'KRW'
}

/** 손익의 색 이름: 이익 good, 손해 bad, 0 flat. 보이는 자릿수로 반올림한 값으로 정한다 */
export function pnlClass(n: number, currency = 'KRW'): 'good' | 'bad' | 'flat' {
  const v = rounded(n, currency).value
  return v > 0 ? 'good' : v < 0 ? 'bad' : 'flat'
}

/** 통화가 여럿이면 모두 같은 쪽일 때만 색을 칠한다 (원화 이익·USDT 손실이 섞이면 flat) */
export function moneyClass(m: Money): 'good' | 'bad' | 'flat' {
  const classes = new Set(Object.entries(m).map(([c, v]) => pnlClass(v, c)).filter((k) => k !== 'flat'))
  return classes.size === 1 ? [...classes][0] : 'flat'
}

const CRYPTO = new Set(['BINANCE', 'UPBIT', 'BYBIT', 'BITHUMB', 'OKX', 'COINBASE', 'KRAKEN', 'BITGET', 'KORBIT', 'COINONE'])
/** 거래소 이름. 모르는 거래소는 코드 그대로 */
export function exchangeName(code: string): string {
  const names: Record<string, string> = {
    BINANCE: t('바이낸스'), UPBIT: t('업비트'), BYBIT: t('바이비트'), BITHUMB: t('빗썸'), KIS: t('한국투자증권'),
  }
  return names[code] ?? code
}

/** 수량 단위: 주식은 null(화면이 "주"로 읽는다), 코인은 BTC 같은 코인 이름. 서버 Money.unit과 같은 규칙 */
export function coinUnit(f: Pick<TradeFill, 'exchange' | 'symbol' | 'currency'>): string | null {
  if (!CRYPTO.has(f.exchange)) return null
  const s = f.symbol.toUpperCase()
  if (s.includes('-')) {
    const [a, b] = s.split('-', 2)
    return a === f.currency ? b : a
  }
  return s.endsWith(f.currency) && s.length > f.currency.length ? s.slice(0, -f.currency.length) : s
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
  return Number(n.toFixed(10)).toLocaleString('en-US', { maximumFractionDigits: 10 })
}
