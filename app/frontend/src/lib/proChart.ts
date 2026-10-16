import { t } from './i18n'
import { accountInterval, type BotAccount, type ChartCandle, type ChartMark } from './tradeBot'

/**
 * 거래소처럼 자세한 차트 (#354)의 계산. 화면(ProChart)과 떼어 두어 jsdom 없이 시험한다.
 * 차트 라이브러리는 시간을 UTC 초로 받아 그대로 그리므로, 한국 시간으로 보이게 9시간을 더한 값을 넘긴다(한국은 서머타임이 없다).
 */
export const KST_SECONDS = 9 * 3600

/** 서버 봉 시각(밀리초) → 차트 시각(한국 시간으로 보이게 민 초) */
export function chartTime(ms: number): number {
  return Math.floor(ms / 1000) + KST_SECONDS
}

/** 십자선 시간 라벨: 차트 시각(이미 한국 시간으로 민 초)을 10. 16. 13:45 꼴로. 1초 봉이면 초까지 */
export function timeLabel(sec: number, seconds: boolean): string {
  const d = new Date(sec * 1000)
  const two = (n: number) => String(n).padStart(2, '0')
  const hm = `${two(d.getUTCHours())}:${two(d.getUTCMinutes())}${seconds ? `:${two(d.getUTCSeconds())}` : ''}`
  return `${d.getUTCMonth() + 1}. ${d.getUTCDate()}. ${hm}`
}

/** 이동평균선 기간. 바이낸스 기본과 같다 */
export const MA_PERIODS = [7, 25, 99] as const

/** n개 종가 단순 이동평균. 봉이 n개가 되기 전 자리는 비운다 */
export function movingAverage(candles: ChartCandle[], n: number): { i: number; value: number }[] {
  const out: { i: number; value: number }[] = []
  let sum = 0
  for (let i = 0; i < candles.length; i++) {
    sum += candles[i].c
    if (i >= n) sum -= candles[i - n].c
    if (i >= n - 1) out.push({ i, value: sum / n })
  }
  return out
}

/** 봉 하나의 이동평균 값. 봉이 모자라면 null */
export function maAt(candles: ChartCandle[], i: number, n: number): number | null {
  if (i < n - 1 || i >= candles.length) return null
  let sum = 0
  for (let k = i - n + 1; k <= i; k++) sum += candles[k].c
  return sum / n
}

/** 봉의 등락률(%): 시가 → 종가 */
export function candleRate(c: ChartCandle): number {
  return c.o === 0 ? 0 : ((c.c - c.o) / c.o) * 100
}

/** 차트 위 평단선에 쓰는 내 포지션. side는 선물만 LONG·SHORT, 현물은 빈 문자열 */
export interface ChartPosition {
  avgPrice: number
  quantity: number
  side: string
  /** 누구 포지션인지(내 보유·봇 계좌 이름) */
  label: string
}

/** 지금 값 기준 평가 손익과 수익률(%). 숏은 내려야 이익이다 */
export function positionPnl(p: ChartPosition, price: number): { pnl: number; rate: number } {
  const dir = p.side === 'SHORT' ? -1 : 1
  const pnl = dir * (price - p.avgPrice) * p.quantity
  const rate = p.avgPrice === 0 ? 0 : dir * ((price - p.avgPrice) / p.avgPrice) * 100
  return { pnl, rate }
}

/** 체결이 들어간 봉 번호들. 같은 봉의 체결은 한데 모은다(십자선이 그 봉에 오면 머리말에 보인다) */
export function marksByCandle(candles: ChartCandle[], marks: ChartMark[], index: (cs: ChartCandle[], t: number) => number | null): Map<number, ChartMark[]> {
  const out = new Map<number, ChartMark[]>()
  for (const m of marks) {
    const i = index(candles, m.t)
    if (i === null) continue
    const list = out.get(i) ?? []
    list.push(m)
    out.set(i, list)
  }
  return out
}

/** 거래량을 짧게: 1,234 → 1.23K, 12,345,678 → 12.35M */
export function compact(n: number): string {
  const abs = Math.abs(n)
  if (abs >= 1e9) return `${(n / 1e9).toFixed(2)}B`
  if (abs >= 1e6) return `${(n / 1e6).toFixed(2)}M`
  if (abs >= 1e3) return `${(n / 1e3).toFixed(2)}K`
  return abs >= 1 ? n.toFixed(2) : n.toPrecision(3)
}

/** #rrggbb 색에 투명도를 붙인다. 다른 꼴이면 그대로 둔다 */
export function withAlpha(color: string, alpha: number): string {
  if (!/^#[0-9a-f]{6}$/i.test(color)) return color
  return color + Math.round(alpha * 255).toString(16).padStart(2, '0')
}

/** 가격 소수 자리: 원화는 100원 아래만 소수 둘, USDT는 값이 작을수록 길게 */
export function pricePrecision(price: number, currency: string): number {
  if (currency === 'KRW') return price < 100 ? 2 : 0
  if (price < 1) return 5
  if (price < 100) return 3
  return 2
}

/** 머리말 숫자: 통화 이름 없이 자리만 맞춘다(거래소 차트처럼) */
export function priceText(n: number, precision: number): string {
  return n.toLocaleString('en-US', { minimumFractionDigits: precision, maximumFractionDigits: precision })
}

/**
 * 자동매매 계좌들 가운데 이 거래소·종목을 들고 있는 봇 포지션. 계좌마다 평단선 하나씩 그린다.
 * @param exchange BINANCE·UPBIT (차트 응답의 exchange)
 */
export function botPositions(accounts: BotAccount[], exchange: string, symbol: string): ChartPosition[] {
  const out: ChartPosition[] = []
  for (const a of accounts) {
    if (a.exchange.toUpperCase() !== exchange.toUpperCase()) continue
    for (const p of a.positions) {
      if (p.symbol !== symbol || p.avgPrice === null || p.avgPrice <= 0 || p.quantity === 0) continue
      const who = a.account.startsWith('server-paper-') ? t('서버 봇') : t('내 컴퓨터 봇')
      const market = a.market === 'FUTURES' ? t('선물') : t('현물')
      out.push({ avgPrice: p.avgPrice, quantity: Math.abs(p.quantity), side: p.side, label: [who, market, accountInterval(a.account)].filter(Boolean).join(' ') })
    }
  }
  return out
}
