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

/** 서버 모의 봇의 내 기준 (#340). 손절·익절은 비율(0.03 = 3%), 익절 0은 쓰지 않음. 한 번 금액은 기본 크레딧 */
export interface PaperBotSettings {
  coins: string[]
  stopLoss: number
  takeProfit: number
  interval: string
  budget: number
}

export interface PaperBotView {
  enabled: boolean
  settings: PaperBotSettings
  /** 고를 수 있는 코인과 봉 간격 */
  coins: string[]
  intervals: string[]
}

/** 서버가 돌리는 모의 봇 (spec 102). 켜면 devlog 서버가 5분마다 공개 시세로 모의 매매를 한다 */
export const loadPaperBot = () => api<PaperBotView>('/api/me/trade-bot/paper')
export const setPaperBot = (enabled: boolean) => api<PaperBotView>('/api/me/trade-bot/paper', { method: 'PUT', body: { enabled } })
export const savePaperBotSettings = (settings: PaperBotSettings) =>
  api<PaperBotView>('/api/me/trade-bot/paper', { method: 'PUT', body: { settings } })

/** 화면에서 고치는 기준: 손절·익절은 % 숫자 그대로 */
export interface PaperBotForm {
  coins: string[]
  stopLossPct: string
  takeProfitPct: string
  interval: string
  budget: string
}

export const PAPER_BOT_MIN_BUDGET = 5_000
export const PAPER_BOT_MAX_BUDGET = 1_000_000

/** 소수 계산 오차 없이 비율을 %로 (0.035 → "3.5") */
const pct = (ratio: number) => String(Math.round(ratio * 10_000) / 100)

export function paperBotForm(s: PaperBotSettings): PaperBotForm {
  return { coins: s.coins, stopLossPct: pct(s.stopLoss), takeProfitPct: pct(s.takeProfit), interval: s.interval, budget: String(s.budget) }
}

/** 서버 검사와 같은 범위. 틀린 곳이 있으면 그 문구, 없으면 null */
export function paperBotFormError(f: PaperBotForm): string | null {
  const sl = Number(f.stopLossPct)
  const tp = f.takeProfitPct.trim() === '' ? 0 : Number(f.takeProfitPct)
  const budget = Number(f.budget)
  if (f.coins.length === 0) return t('코인을 하나 이상 골라 주세요.')
  if (!Number.isFinite(sl) || sl < 0.5 || sl > 50) return t('손절은 0.5%에서 50% 사이로 정해 주세요.')
  if (!Number.isFinite(tp) || tp < 0 || (tp > 0 && tp < 0.5) || tp > 100) return t('익절은 0(쓰지 않음)이나 0.5%에서 100% 사이로 정해 주세요.')
  if (!Number.isInteger(budget) || budget < PAPER_BOT_MIN_BUDGET || budget > PAPER_BOT_MAX_BUDGET) {
    return t('한 번 금액은 {0}에서 {1} 크레딧 사이로 정해 주세요.', { 0: PAPER_BOT_MIN_BUDGET.toLocaleString(), 1: PAPER_BOT_MAX_BUDGET.toLocaleString() })
  }
  return null
}

export function paperBotSettings(f: PaperBotForm): PaperBotSettings {
  const ratio = (v: string) => Math.round(Number(v || 0) * 100) / 10_000
  return { coins: f.coins, stopLoss: ratio(f.stopLossPct), takeProfit: ratio(f.takeProfitPct), interval: f.interval, budget: Number(f.budget) }
}

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
/** 차트 봉 간격 버튼 (#354): 바이낸스 차트처럼 1초·15분·1시간·4시간·1일·1주 */
export const CHART_INTERVALS = ['1s', '15m', '1h', '4h', '1d', '1w'] as const
export type ChartInterval = (typeof CHART_INTERVALS)[number]
/** 버튼에 쓰는 이름. 바이낸스처럼 시간 이상은 대문자 */
export const INTERVAL_LABELS: Record<ChartInterval, string> = { '1s': '1s', '15m': '15m', '1h': '1H', '4h': '4H', '1d': '1D', '1w': '1W' }
/** 원화 시세(코빗)는 1초 봉이 없다 */
export const KRW_INTERVALS: ChartInterval[] = ['15m', '1h', '4h', '1d', '1w']

/** v: 거래량(코인 개수). #354 전 응답에는 없다 */
export interface ChartCandle { t: number; o: number; h: number; l: number; c: number; v?: number }
/** 최근 24시간 고가·저가·거래량(코인)·거래대금 */
export interface ChartDay { high: number; low: number; volume: number; quoteVolume: number }
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
  /** 최근 24시간 요약. 받지 못했으면 null */
  day?: ChartDay | null
}
export interface CoinChart {
  exchange: 'BINANCE' | 'UPBIT'
  interval: ChartInterval
  currency: string
  coins: ChartCoin[]
  fetchedAt: string
  /** 캔들을 실제로 받은 곳. 원화 탭은 서버 설정에 따라 코빗이나 업비트 */
  source?: 'BINANCE' | 'KORBIT' | 'UPBIT'
}

/** 원화 시세를 받는 곳의 이름(번역 키). 서버가 알리지 않으면 기본값 코빗 */
export const krwSourceName = (source?: string | null) => (source === 'UPBIT' ? '업비트' : '코빗')

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
