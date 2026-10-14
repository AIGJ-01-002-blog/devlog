import { api } from './api'
import { num } from './dashboard'
import { t } from './i18n'

/** 블로그 포인트 (#266). 서버 PointLedger와 같은 모양. 포인트는 정수(P) */
export type Wallet = 'INVEST_ONLY' | 'SHOP'
export type PointReason = 'WELCOME' | 'ATTENDANCE' | 'POST' | 'TRADE_PROFIT' | 'SHOP' | 'INVEST' | 'ADMIN'

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
  /** 오늘 받은(또는 지금 출석하면 받을) 크레딧 */
  todayReward: number
  /** 내일도 이어서 출석하면 받을 크레딧 */
  nextReward: number
  maxReward: number
  maxStreak: number
  recent: string[]
}

/** 연속 출석 휘장 (#266). 365일은 1년을 채운 회원의 특별 휘장 */
export interface StreakBadge {
  days: number
  special: boolean
  earnedAt: string
}

export const BADGE_DAYS = [20, 50, 100, 150, 200, 300, 365] as const

export interface PointToday {
  attendedToday: boolean
  streak: number
  reward: number
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
  badges: StreakBadge[]
  nextBadge: number | null
}

export interface AttendResult {
  already: boolean
  earned: number
  streak: number
  newBadges: number[]
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
export const loadPointToday = () => api<PointToday>('/api/me/points/today')
export const loadBadges = (handle: string) => api<StreakBadge[]>(`/api/members/${encodeURIComponent(handle)}/badges`)
export const loadPointRules = () => api<PointRuleView[]>('/api/admin/points/rules')
export const savePointRules = (values: Record<string, number>) =>
  api<PointRuleView[]>('/api/admin/points/rules', { method: 'PUT', body: values })

/** 크레딧 숫자 (활동으로 번 상점 지갑, 1크레딧 = 1P). sign이면 적립에 +를 붙인다 */
export function credits(n: number, sign = false): string {
  const body = t('{0}크레딧', { 0: num(Math.abs(n)) })
  if (n < 0) return `-${body}`
  return sign && n > 0 ? `+${body}` : body
}

/** 원장 한 줄의 금액: 상점 지갑은 크레딧, 투자 전용은 P */
export function amountOf(wallet: Wallet, n: number, sign = false): string {
  return wallet === 'SHOP' ? credits(n, sign) : points(n, sign)
}

/** 연속 출석 streak일째 보상: 기준 × streak², 하루 상한까지 (서버 PointLedger.attendanceReward와 같은 식) */
export function attendanceReward(base: number, max: number, streak: number): number {
  if (base <= 0 || streak <= 0) return 0
  return Math.min(max, base * streak * streak)
}

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
    case 'POST': return t('글 발행')
    case 'TRADE_PROFIT': return t('모의투자 수익')
    case 'SHOP': return t('상점 구매')
    case 'INVEST': return t('모의투자')
    case 'ADMIN': return t('운영자 조정')
    default: return reason
  }
}

export function walletLabel(w: Wallet): string {
  return w === 'INVEST_ONLY' ? t('투자 전용') : t('크레딧')
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

/** 휘장 이름: 365일은 "1년 개근", 나머지는 "N일 연속 출석" */
export function badgeName(days: number): string {
  return days >= 365 ? t('1년 개근') : t('{0}일 연속 출석', { 0: days })
}

/** 오늘 날짜(한국 시간, YYYY-MM-DD). 출석 알림을 오늘 하루 닫아 둘 때 쓴다 */
export function kstToday(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}
