import { api } from './api'
import { getLocale, t } from './i18n'
import type { Platform } from './shorts'

/** 내 대시보드 (#239). 서버 MyDashboard와 같은 모양 */
export type Period = 7 | 30 | 90
export const PERIODS: Period[] = [7, 30, 90]

export interface Sums {
  views: number
  likes: number
  comments: number
}

export type Metric = keyof Sums

export interface DashboardDay extends Sums {
  date: string
}

export interface DashboardPost extends Sums {
  id: number
  title: string
  visibility: string
  link: string
}

export interface ShortsSummary {
  scripts: number
  ready: number
  uploads: Record<Platform, number>
  recent: { shortsId: number; title: string; platform: Platform; url: string; uploadedAt: string }[]
}

export interface MyDashboard {
  days: Period
  from: string
  to: string
  posts: number
  totals: Sums
  current: Sums
  previous: Sums
  daily: DashboardDay[]
  topPosts: DashboardPost[]
  shorts: ShortsSummary | null
}

export const loadDashboard = (days: Period) => api<MyDashboard>(`/api/me/dashboard?days=${days}`)

export function periodOf(raw: string | null): Period {
  return PERIODS.find((p) => String(p) === raw) ?? 30
}

/** 이전 기간과 견준 변화. 화살표 기호를 함께 써서 색만으로 구분하지 않게 한다 */
export function delta(current: number, previous: number): { text: string; trend: 'up' | 'down' | 'flat' } {
  if (current === previous) return { text: t('변화 없음'), trend: 'flat' }
  if (previous === 0) return { text: t('새로 생김'), trend: 'up' }
  const pct = Math.round(((current - previous) / previous) * 100)
  if (pct === 0) return { text: t('변화 없음'), trend: 'flat' }
  return { text: `${pct > 0 ? '+' : ''}${pct}%`, trend: pct > 0 ? 'up' : 'down' }
}

export function num(n: number): string {
  const tag = { ko: 'ko-KR', en: 'en-US', ja: 'ja-JP', zh: 'zh-CN' }[getLocale()]
  return n.toLocaleString(tag)
}

/** 2026-10-08 → 축에 쓰는 10.8, 툴팁에 쓰는 10월 8일 */
export function dayLabels(date: string): { label: string; fullLabel: string } {
  const [, m, d] = date.split('-').map(Number)
  return { label: `${m}.${d}`, fullLabel: t('{0}월 {1}일', { 0: m, 1: d }) }
}
