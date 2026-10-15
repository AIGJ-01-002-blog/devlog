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

/** 서버가 돌리는 모의 봇 (spec 102). 켜면 devlog 서버가 5분마다 공개 시세로 모의 매매를 한다 */
export const loadPaperBot = () => api<{ enabled: boolean }>('/api/me/trade-bot/paper')
export const setPaperBot = (enabled: boolean) => api<{ enabled: boolean }>('/api/me/trade-bot/paper', { method: 'PUT', body: { enabled } })

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

/** 차트 탭 (spec 101): 서버 TradeCharts.Chart와 같은 모양. 캔들은 서버가 10초씩 들고 있는 거래소 공개 캔들이다 */
export type ChartExchange = 'binance' | 'upbit'
export const CHART_EXCHANGES: ChartExchange[] = ['binance', 'upbit']
export const CHART_INTERVALS = ['1m', '5m', '15m', '1h', '4h', '1d'] as const
export type ChartInterval = (typeof CHART_INTERVALS)[number]

export interface ChartCandle { t: number; o: number; h: number; l: number; c: number }
/** by: 누가 낸 체결인지. BOT은 봇의 모의 체결, ME는 포인트 모의투자에서 내가 직접 낸 체결(#324) */
export interface ChartMark { t: number; side: string; price: number; text: string; by?: 'BOT' | 'ME' }
export interface ChartCoin {
  coin: string
  name: string
  symbol: string
  candles: ChartCandle[]
  marks: ChartMark[]
  /** 캔들을 받지 못했으면 이유 */
  error: string | null
}
export interface CoinChart {
  exchange: 'BINANCE' | 'UPBIT'
  interval: ChartInterval
  currency: string
  coins: ChartCoin[]
  fetchedAt: string
}

export const loadTradeCharts = (exchange: ChartExchange, interval: ChartInterval) =>
  api<CoinChart>(`/api/me/trade-bot/charts?exchange=${exchange}&interval=${interval}`)

/** 체결을 찍을 봉 번호: 체결 시각이 들어 있는 봉. 차트 범위 밖이면 null */
export function markIndex(candles: ChartCandle[], t: number): number | null {
  if (candles.length === 0 || t < candles[0].t) return null
  let i = candles.length - 1
  while (i > 0 && candles[i].t > t) i--
  return i
}

/** 차트 구간의 등락률(%): 첫 봉 시가 → 마지막 봉 종가 */
export function changeRate(candles: ChartCandle[]): number | null {
  if (candles.length === 0 || candles[0].o === 0) return null
  return ((candles[candles.length - 1].c - candles[0].o) / candles[0].o) * 100
}
