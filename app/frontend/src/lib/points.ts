import { api } from './api'
import { num } from './dashboard'
import { t } from './i18n'

/** 블로그 포인트 (#266). 서버 PointLedger와 같은 모양. 포인트는 정수(P) */
export type Wallet = 'INVEST_ONLY' | 'SHOP'
export type PointReason = 'WELCOME' | 'ATTENDANCE' | 'STREAK' | 'POST' | 'TRADE_PROFIT' | 'SHOP' | 'INVEST' | 'ADMIN'

export interface PointBalance {
  invest: number
  shop: number
}

export interface PointEntry {
  id: number
  wallet: Wallet
  amount: number
  reason: PointReason | string
  refId: number | null
  memo: string
  createdAt: string
}

export interface PointHistory {
  items: PointEntry[]
  nextBefore: number | null
}

export interface PointAttendance {
  today: string
  attendedToday: boolean
  streak: number
  streakDays: number
  nextBonusIn: number
  recent: string[]
}

export interface PointRuleView {
  code: string
  label: string
  value: number
  defaultValue: number
  max: number
}

export interface PointSummary {
  balance: PointBalance
  attendance: PointAttendance
  rules: PointRuleView[]
  postsRewardedToday: number
  tradeProfitToday: number
  history: PointHistory
}

export interface AttendResult {
  already: boolean
  earned: number
  bonus: number
  summary: PointSummary
}

export const loadPoints = () => api<PointSummary>('/api/me/points')
export const attend = () => api<AttendResult>('/api/me/points/attendance', { method: 'POST' })
export function loadPointHistory(wallet: Wallet | null, before: number | null): Promise<PointHistory> {
  const q = new URLSearchParams()
  if (wallet) q.set('wallet', wallet)
  if (before !== null) q.set('before', String(before))
  const s = q.toString()
  return api<PointHistory>(`/api/me/points/history${s ? `?${s}` : ''}`)
}
export const loadPointRules = () => api<PointRuleView[]>('/api/admin/points/rules')
export const savePointRules = (values: Record<string, number>) =>
  api<PointRuleView[]>('/api/admin/points/rules', { method: 'PUT', body: values })

/** 포인트 숫자. sign이면 적립에 +를 붙인다 (색만으로 구분하지 않게) */
export function points(n: number, sign = false): string {
  const body = t('{0}P', { 0: num(Math.abs(n)) })
  if (n < 0) return `-${body}`
  return sign && n > 0 ? `+${body}` : body
}

export function ruleValue(rules: PointRuleView[], code: string): number {
  return rules.find((r) => r.code === code)?.value ?? 0
}

/** 원장 한 줄을 사람이 읽는 이름으로 */
export function reasonLabel(reason: string): string {
  switch (reason) {
    case 'WELCOME': return t('가입 첫 지급')
    case 'ATTENDANCE': return t('출석')
    case 'STREAK': return t('연속 출석 보너스')
    case 'POST': return t('글 발행')
    case 'TRADE_PROFIT': return t('모의투자 수익')
    case 'SHOP': return t('상점 구매')
    case 'INVEST': return t('모의투자')
    case 'ADMIN': return t('운영자 조정')
    default: return reason
  }
}

export function walletLabel(w: Wallet): string {
  return w === 'INVEST_ONLY' ? t('투자 전용') : t('상점')
}

/**
 * 출석 칸: 오늘을 끝으로 지난 days일 동안 출석했는지. 오늘 칸은 출석 전이면 비어 있다.
 * today는 서버가 준 한국 날짜(YYYY-MM-DD)라서 브라우저 시간대와 상관없다.
 */
export function attendanceDots(a: PointAttendance, days = 7): { date: string; done: boolean; today: boolean }[] {
  const set = new Set(a.recent)
  const base = new Date(`${a.today}T00:00:00Z`)
  const out: { date: string; done: boolean; today: boolean }[] = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base.getTime() - i * 86_400_000).toISOString().slice(0, 10)
    out.push({ date: d, done: set.has(d), today: i === 0 })
  }
  return out
}

/** 내역 시각: 한국 시간 M월 D일 HH:mm */
export function entryTime(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' })
    .format(new Date(iso))
}
