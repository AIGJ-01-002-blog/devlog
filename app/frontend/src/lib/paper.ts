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
  /** 지정가 매도에 묶이지 않아 지금 팔 수 있는 수량 (#332) */
  available: number
}

/** 지정가 주문 (#332). OPEN 미체결 · FILLED 체결 · CANCELED 취소 */
export interface PaperLimit {
  id: number
  symbol: string
  name: string
  side: PaperSide
  price: number
  quantity: number
  locked: number
  status: 'OPEN' | 'FILLED' | 'CANCELED'
  filledPrice: number | null
  createdAt: string
  closedAt: string | null
}

/** 최근 체결 한 건. bot이면 서버 모의 봇이 낸 체결 */
export interface PaperFill {
  tradeId: number
  symbol: string
  name: string
  side: PaperSide
  quantity: number
  price: number
  fee: number
  pnl: number | null
  bot: boolean
  at: string
}

export interface PaperState {
  quotes: PaperQuote[]
  positions: PaperPosition[]
  invest: number
  shop: number
  holdings: number
  /** 미체결 지정가 매수에 묶인 크레딧 */
  locked: number
  feeRate: number
  minOrder: number
  orders: PaperLimit[]
  fills: PaperFill[]
  quotesError: string | null
  /** 주문 시세를 받는 곳 */
  quotesSource?: 'KORBIT' | 'UPBIT'
}

export interface PaperLimitOrder {
  clientOrderId: string
  symbol: string
  side: PaperSide
  price: number
  quantity: number
  useShop?: boolean
  reason?: string
}

export interface PaperLimitPlaced {
  duplicate: boolean
  order: PaperLimit | null
  state: PaperState
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
export const placeLimit = (order: PaperLimitOrder) => api<PaperLimitPlaced>('/api/me/paper/limit-orders', { method: 'POST', body: order })
export const cancelLimit = (id: number) => api<PaperState>(`/api/me/paper/limit-orders/${id}`, { method: 'DELETE' })

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

/**
 * 원화 시장 호가 단위 (업비트 기준). 가격 −/＋ 버튼이 이만큼 움직인다
 */
export function tickSize(p: number): number {
  if (p >= 2_000_000) return 1000
  if (p >= 1_000_000) return 500
  if (p >= 500_000) return 100
  if (p >= 100_000) return 50
  if (p >= 10_000) return 10
  if (p >= 1_000) return 1
  if (p >= 100) return 0.1
  if (p >= 10) return 0.01
  if (p >= 1) return 0.001
  return 0.0001
}

/** 소수 자릿수 오차 없이 n을 자릿수 d로 자른다 */
function round(n: number, d: number): number {
  return Number(n.toFixed(d))
}

const decimals = (step: number) => Math.max(0, -Math.floor(Math.log10(step) + 1e-9))

/** 호가 단위에 맞춰 내림 */
export function toTick(p: number): number {
  if (!(p > 0)) return 0
  const step = tickSize(p)
  return round(Math.floor(round(p / step, 6)) * step, decimals(step))
}

/** 가격을 한 호가 올리거나(+1) 내린다(-1). 내릴 때는 아래 구간 호가를 쓴다 */
export function stepPrice(p: number, dir: 1 | -1): number {
  const base = toTick(p)
  if (base <= 0) return 0
  const step = dir > 0 ? tickSize(base) : tickSize(base - tickSize(base) / 2)
  return Math.max(0, round(base + dir * step, decimals(step)))
}

/** 코인마다 수량 소수 자리 (서버 PaperCoins와 같다). 비싼 코인은 잘게, 싼 코인은 덜 잘게 */
export const QTY_SCALE: Record<string, number> = { BTC: 8, ETH: 8, XRP: 4, SOL: 6, DOGE: 2 }

/** 코인 수량을 그 코인 자릿수에서 내림 (서버와 같은 셈) */
export function floorQty(n: number, scale = 8): number {
  if (!(n > 0)) return 0
  const k = 10 ** scale
  return round(Math.floor(round(n * k, 2)) / k, scale)
}

/**
 * 지정가 매수에 묶일 크레딧: 지정가 × 수량(올림) + 수수료(올림). 서버 PaperTrading.lockBuy와 같은 셈
 */
export function limitBuyCost(p: number, quantity: number, feeRate: number): { gross: number; fee: number; total: number } {
  if (!(p > 0) || !(quantity > 0)) return { gross: 0, fee: 0, total: 0 }
  const gross = Math.ceil(round(p * quantity, 6))
  const fee = Math.ceil(round(gross * feeRate, 6))
  return { gross, fee, total: gross + fee }
}

/** 쓸 수 있는 크레딧으로 지정가에 살 수 있는 가장 많은 수량 (수수료 포함) */
export function maxBuyQty(credit: number, p: number, feeRate: number, scale = 8): number {
  if (!(p > 0) || credit <= 0) return 0
  const unit = 10 ** -scale
  let q = floorQty(credit / (p * (1 + feeRate)), scale)
  // 올림 때문에 한두 단위 넘칠 수 있어 줄여 맞춘다
  while (q > 0 && limitBuyCost(p, q, feeRate).total > credit) q = floorQty(q - unit, scale)
  return q
}
