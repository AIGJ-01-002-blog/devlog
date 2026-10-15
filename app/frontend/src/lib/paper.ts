import { api } from './api'
import { num } from './dashboard'
import { t } from './i18n'
import type { ChartInterval, CoinChart } from './tradeBot'

/** 포인트 모의투자 (#267). 서버 PaperTrading과 같은 모양. 1P = 1원, 업비트 원화 시장 현물만 */
export type PaperSide = 'BUY' | 'SELL'

export interface PaperQuote {
  symbol: string
  name: string
  price: number | null
  changeRate: number | null
}

export interface PaperPosition {
  symbol: string
  name: string
  quantity: number
  cost: number
  costInvest: number
  costShop: number
  avgPrice: number
  value: number
  pnl: number
}

export interface PaperState {
  quotes: PaperQuote[]
  positions: PaperPosition[]
  invest: number
  shop: number
  holdings: number
  feeRate: number
  minOrder: number
  quotesError: string | null
}

export interface PaperOrder {
  clientOrderId: string
  symbol: string
  side: PaperSide
  amount?: number
  quantity?: number
  all?: boolean
  useShop?: boolean
  reason?: string
}

export interface PaperFilled {
  duplicate: boolean
  tradeId: number | null
  side: PaperSide
  symbol: string
  quantity: number | null
  price: number | null
  fee: number
  pnl: number | null
  reward: number
  state: PaperState
}

export const loadPaper = () => api<PaperState>('/api/me/paper')
/** 포인트 모의투자 차트 (#324): 원화 캔들에 내 체결(ME)과 봇 체결(BOT)을 함께 찍는다 */
export const loadPaperChart = (interval: ChartInterval) => api<CoinChart>(`/api/me/paper/chart?interval=${interval}`)
export const placeOrder = (order: PaperOrder) => api<PaperFilled>('/api/me/paper/orders', { method: 'POST', body: order })

/** 주문 번호: 두 번 눌러도 한 번만 체결되게 화면이 주문마다 하나 만든다 */
export function newOrderId(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `o-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/** 코인 가격(원). 100원 미만은 소수 둘째 자리까지 (도지·리플) */
export function price(n: number | null): string {
  if (n === null) return '-'
  if (Math.abs(n) < 100) return t('{0}원', { 0: n.toLocaleString('en-US', { maximumFractionDigits: 2 }) })
  return t('{0}원', { 0: num(Math.round(n)) })
}

/** 전일 대비 등락률. 색만으로 구분하지 않게 +·- 를 붙인다 */
export function change(rate: number | null): string {
  if (rate === null) return '-'
  const pct = (rate * 100).toFixed(2)
  return rate > 0 ? `+${pct}%` : `${pct}%`
}

/** 이익·상승은 good(초록), 손해·하락은 bad(빨강) */
export function tone(n: number | null): 'good' | 'bad' | 'flat' {
  if (n === null || n === 0) return 'flat'
  return n > 0 ? 'good' : 'bad'
}

/** 코인 수량. 꼬리 0 없이 소수 여덟째 자리까지 */
export function qty(n: number): string {
  return n.toLocaleString('en-US', { maximumFractionDigits: 8 })
}

/** 원금 대비 손익률 */
export function pnlRate(p: Pick<PaperPosition, 'pnl' | 'cost'>): string {
  if (p.cost <= 0) return '0.00%'
  return change(p.pnl / p.cost)
}

/**
 * 살 때 예상: 수수료는 올림, 수량은 내림(서버와 같은 셈). 시세가 없거나 금액이 0이면 null
 */
export function estimateBuy(amount: number, quote: number | null, feeRate: number): { fee: number; quantity: number } | null {
  if (!quote || amount <= 0) return null
  const fee = Math.ceil(amount * feeRate)
  return { fee, quantity: Math.max(0, amount - fee) / quote }
}

/** 팔 때 예상: 받는 돈(수수료 뺀 값) */
export function estimateSell(quantity: number, quote: number | null, feeRate: number): { fee: number; proceeds: number } | null {
  if (!quote || quantity <= 0) return null
  const gross = Math.floor(quantity * quote)
  const fee = Math.ceil(gross * feeRate)
  return { fee, proceeds: Math.max(0, gross - fee) }
}
