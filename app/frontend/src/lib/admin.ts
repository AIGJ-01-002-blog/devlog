import { api } from './api'
import type { ReportReason } from './moderation'

// 관리자 페이지 (spec 062): 대시보드 통계, 회원·글 관리, 권한. 관리자(ADMIN)는 운영자 계정 하나, 매니저(MANAGER)는 권한을 주지 못한다.

export type Role = 'USER' | 'MANAGER' | 'ADMIN'
export type Period = 7 | 30 | 90

export const PERIODS: Period[] = [7, 30, 90]

export const ROLE_LABEL: Record<Role, string> = { USER: '일반 회원', MANAGER: '매니저', ADMIN: '관리자' }

export const MEMBER_STATUS_LABEL: Record<string, string> = { ACTIVE: '활동 중', SUSPENDED: '정지', WITHDRAWN: '탈퇴 신청' }

export const PROVIDER_LABEL: Record<string, string> = { GITHUB: 'GitHub', GOOGLE: 'Google', LOCAL: '이메일' }

/** 관리자 페이지를 쓸 수 있는 권한인가 (매니저 포함) */
export function isStaff(role: string | null | undefined): boolean {
  return role === 'ADMIN' || role === 'MANAGER'
}

export function roleLabel(role: string | null | undefined): string {
  return ROLE_LABEL[role as Role] ?? '일반 회원'
}

export interface MemberSummary { total: number; active: number; suspended: number; withdrawing: number; managers: number; admins: number }
export interface PostSummary { published: number; publicPosts: number; privatePosts: number; drafts: number; trash: number; hidden: number }
/** visitors는 기간 동안 온 사람 수(여러 날 와도 한 명), visits는 들어온 횟수 (064), activeMembers는 기간 동안 활동한 회원 수 (066), searchVisits는 검색으로 온 방문 수 (070) */
export interface Sums { signups: number; posts: number; comments: number; likes: number; views: number; reports: number; visitors: number; visits: number; activeMembers: number; searchVisits: number }
export interface Day extends Sums { date: string }

export interface PostLine {
  id: number
  /** 비공개·친구 공개 글은 관리자에게도 제목을 주지 않는다 */
  title: string | null
  authorHandle: string
  visibility: string
  hidden: boolean
  views: number
  likes: number
  comments: number
  link: string
}

/** 렌더 워커 상태 (#241) */
export interface WorkerStatus {
  /** 워커 토큰을 넣었는가. 없으면 워커 API가 꺼져 있다 */
  tokenSet: boolean
  /** 워커가 마지막으로 부른 때 (없거나 모르면 null) */
  lastSeen: string | null
  /** 하루 안에 옛 토큰으로 들어온 워커가 있다 */
  previousToken: boolean
  queued: number
  rendering: number
  failedToday: number
  oldestQueuedAt: string | null
}

/** 워커가 이만큼 부르지 않으면 안 보이는 것으로 본다. 워커는 몇 초마다 일감을 묻는다 */
export const WORKER_STALE_MINUTES = 5

export type WorkerHealth =
  | { state: 'no-token' }
  | { state: 'never' }
  | { state: 'ok'; minutes: number }
  | { state: 'stale'; minutes: number }

export function workerHealth(w: WorkerStatus, now: number = Date.now()): WorkerHealth {
  if (!w.tokenSet) return { state: 'no-token' }
  if (!w.lastSeen) return { state: 'never' }
  const minutes = Math.max(0, Math.floor((now - Date.parse(w.lastSeen)) / 60_000))
  return minutes < WORKER_STALE_MINUTES ? { state: 'ok', minutes } : { state: 'stale', minutes }
}

/** "3분 전", "2시간 전", "4일 전" */
export function ago(minutes: number): string {
  if (minutes < 1) return '방금'
  if (minutes < 60) return `${minutes}분 전`
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)}시간 전`
  return `${Math.floor(minutes / 60 / 24)}일 전`
}

export function workerLabel(h: WorkerHealth): string {
  switch (h.state) {
    case 'no-token': return '토큰 없음'
    case 'never': return '아직 연결 없음'
    case 'ok': return `연결됨 · ${ago(h.minutes)}`
    case 'stale': return `안 보임 · 마지막 ${ago(h.minutes)}`
  }
}

export interface Dashboard {
  days: Period
  from: string
  to: string
  totals: { members: MemberSummary; posts: PostSummary; comments: number; likes: number; views: number; pendingReports: number; openInquiries: number }
  current: Sums
  previous: Sums
  activeMembers: number
  /** 오늘·어제 순방문자, 기간 방문자 중 회원 수 */
  visitors: { today: number; yesterday: number; members: number }
  daily: Day[]
  topPosts: PostLine[]
  topAuthors: { handle: string; nickname: string | null; posts: number }[]
  /** 유입 경로별 새 방문 수, 많은 순 (070). host는 source가 other일 때 그 사이트(빈 값이면 나머지 사이트를 모은 줄) */
  sources: { source: string; host: string; visits: number }[]
  /** 많이 본 화면 (070). title은 공개 글 화면일 때만 */
  topPages: { path: string; views: number; title: string | null }[]
  /** 쇼츠 렌더 워커 상태 (#241). 쇼츠가 꺼져 있으면 null */
  worker: WorkerStatus | null
}

export interface MemberRow {
  id: number
  handle: string
  nickname: string | null
  role: Role
  status: string
  provider: string | null
  joinedAt: string
  lastActiveAt: string | null
}

export interface MemberLine { member: MemberRow; posts: number; drafts: number; hiddenPosts: number; comments: number }

export interface Paged<T> { items: T[]; page: number; total: number; pageSize: number }

export interface MemberDetail {
  member: MemberRow
  posts: { published: number; publicPosts: number; drafts: number; hidden: number }
  comments: number
  viewsReceived: number
  likesReceived: number
  commentsReceived: number
  monthly: { month: string; posts: number }[]
  topPosts: PostLine[]
}

export type PostFilter = 'all' | 'public' | 'private' | 'hidden'

export const POST_FILTERS: { code: PostFilter; label: string }[] = [
  { code: 'all', label: '전체' },
  { code: 'public', label: '공개' },
  { code: 'private', label: '비공개' },
  { code: 'hidden', label: '숨김' },
]

function query(params: Record<string, string | number | null | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== '') q.set(k, String(v))
  const s = q.toString()
  return s ? `?${s}` : ''
}

export const consoleApi = {
  dashboard: (days: Period) => api<Dashboard>(`/api/admin/dashboard${query({ days })}`),
  members: (q: string, role: string, status: string, page: number) =>
    api<Paged<MemberLine>>(`/api/admin/members${query({ q, role, status, page })}`),
  memberStats: (handle: string) => api<MemberDetail>(`/api/admin/members/${encodeURIComponent(handle)}/stats`),
  /** 탈퇴 회원을 30일 유예 전에 바로 정리한다 (020 FR-039). 관리자만, 되돌릴 수 없다 */
  purgeWithdrawn: (handle: string) => api<void>(`/api/admin/members/${encodeURIComponent(handle)}/purge`, { method: 'POST' }),
  setRole: (handle: string, role: Role) =>
    api<{ handle: string; role: Role }>(`/api/admin/members/${encodeURIComponent(handle)}/role`, { method: 'PUT', body: { role } }),
  posts: (q: string, filter: PostFilter, author: string, page: number) =>
    api<Paged<PostLine>>(`/api/admin/posts${query({ q, filter: filter === 'all' ? null : filter, author, page })}`),
  hidePost: (id: number, reason: ReportReason) => api<void>(`/api/admin/posts/${id}/hide`, { method: 'POST', body: { reason } }),
  unhidePost: (id: number) => api<void>(`/api/admin/posts/${id}/unhide`, { method: 'POST' }),
  /** 검색 수집 대상·제외 글 수와 검수를 기다리는 외부 회원 글 (spec 107) */
  indexReport: () => api<IndexReport>('/api/admin/index-report'),
  /** 외부 회원 글의 검색 수집을 허락하거나 거둔다 (spec 107) */
  setIndexApproval: (id: number, approved: boolean) =>
    api<void>(`/api/admin/posts/${id}/index-approval`, { method: 'PUT', body: { approved } }),
}

/** 검색 수집 현황 (spec 107). 제외 사유는 일기 → 짧은 글 → 검수 전 순서로 처음 걸린 하나로 센다 */
export interface IndexReport {
  indexed: number
  aiDiary: number
  shortPost: number
  unreviewed: number
  pending: { id: number; title: string; authorHandle: string; url: string; bodyChars: number }[]
}

/** 이전 기간 대비: "+12%", "-3%", 이전이 0이면 "새로" , 둘 다 0이면 "" */
export function change(current: number, previous: number): { text: string; trend: 'up' | 'down' | 'flat' } {
  if (current === previous) return { text: current === 0 ? '' : '변화 없음', trend: 'flat' }
  if (previous === 0) return { text: '새로 생김', trend: 'up' }
  const pct = Math.round(((current - previous) / previous) * 100)
  if (pct === 0) return { text: '변화 없음', trend: 'flat' }
  return { text: `${pct > 0 ? '+' : ''}${pct}%`, trend: pct > 0 ? 'up' : 'down' }
}

/** 1,234 처럼 세 자리마다 쉼표 */
export function count(n: number): string {
  return n.toLocaleString('ko-KR')
}

/** 쪽 수 */
export function pageCount(total: number, pageSize: number): number {
  return Math.max(1, Math.ceil(total / pageSize))
}
