import { api, ApiError } from './api'
import { t } from './i18n'

/**
 * 쇼츠 스튜디오 (spec 099). 대본은 내 AI가 MCP로 쓰거나 여기서 직접 쓰고, [영상 만들기]는 사람이 누른다.
 * 영상은 내 컴퓨터의 렌더 워커가 만들고, SNS에는 내가 직접 올린다(자동 업로드 없음).
 * 길이·장면 수 규칙은 서버(ShortsScript)와 같다. 화면은 미리 알려 주기만 하고 판정은 서버가 한다.
 */
export const LIMITS = {
  title: 60,
  scenesMax: 10,
  scenesMinRender: 3,
  caption: 40,
  narration: 200,
  code: 300,
  seconds: 60,
} as const

const CHARS_PER_SECOND = 6.5
const SCENE_GAP_SECONDS = 0.6
/** 워커가 이 시간 안에 대기열을 봤으면 켜져 있다고 본다 (워커 기본 간격 15초, 실패 시 최대 5분까지 늘어남) */
export const WORKER_FRESH_MS = 6 * 60_000

export type ShortsStatus = 'DRAFT' | 'QUEUED' | 'RENDERING' | 'READY' | 'FAILED'

export interface Scene {
  caption: string
  narration: string
  code: string | null
}

export interface ShortsItem {
  id: number
  postId: number | null
  postTitle: string | null
  postUrl: string | null
  title: string
  scenes: Scene[]
  status: ShortsStatus
  attempts: number
  videoUrl: string | null
  durationMs: number | null
  videoBytes: number | null
  error: string | null
  estimatedSeconds: number
  queuedAt: string | null
  renderedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ShortsOverview {
  items: ShortsItem[]
  workerSeenAt: string | null
  rendersToday: number
  dailyRenders: number
}

export interface OriginalityIssue {
  code: string
  level: 'BLOCK' | 'WARN'
  message: string
  hint: string | null
}

export interface OriginalityReport {
  score: number
  passed: boolean
  issues: OriginalityIssue[]
}

export const emptyScene = (): Scene => ({ caption: '', narration: '', code: null })

/** 서버와 같은 계산: 내레이션 글자 수 ÷ 6.5 + 장면마다 0.6초 */
export function estimateSeconds(scenes: Scene[]): number {
  return scenes.reduce((sum, s) => sum + [...s.narration.trim()].length / CHARS_PER_SECOND + SCENE_GAP_SECONDS, 0)
}

export function statusLabel(status: ShortsStatus): string {
  switch (status) {
    case 'DRAFT': return t('대본')
    case 'QUEUED': return t('만들기 대기')
    case 'RENDERING': return t('만드는 중')
    case 'READY': return t('영상 완성')
    case 'FAILED': return t('만들지 못함')
  }
}

export const isBusy = (status: ShortsStatus) => status === 'QUEUED' || status === 'RENDERING'

export function workerOnline(seenAt: string | null, now = Date.now()): boolean {
  return !!seenAt && now - new Date(seenAt).getTime() < WORKER_FRESH_MS
}

/** 영상 만들기 전에 화면에서 바로 알려 줄 문제. 서버가 다시 본다. */
export function renderProblems(title: string, scenes: Scene[]): string[] {
  const out: string[] = []
  if (!title.trim()) out.push(t('제목을 적어 주세요.'))
  if (scenes.length < LIMITS.scenesMinRender) out.push(t('장면이 {0}개 이상 있어야 영상으로 만들 수 있어요.', { 0: LIMITS.scenesMinRender }))
  scenes.forEach((s, i) => {
    if (!s.caption.trim()) out.push(t('{0}번 장면에 자막이 없어요.', { 0: i + 1 }))
    if (!s.narration.trim()) out.push(t('{0}번 장면에 내레이션이 없어요.', { 0: i + 1 }))
  })
  const seconds = estimateSeconds(scenes)
  if (seconds > LIMITS.seconds) out.push(t('예상 길이가 {0}초예요. {1}초 안으로 줄여 주세요.', { 0: Math.round(seconds), 1: LIMITS.seconds }))
  return out
}

/** 영상 파일 이름: 제목에서 파일 이름에 못 쓰는 글자를 뺀다 */
export function videoFileName(title: string): string {
  const base = title.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').replace(/\s+/g, ' ').trim().slice(0, 50)
  return `${base || 'devlog-shorts'}.mp4`
}

/** 422 SHORTS_NOT_ORIGINAL이면 서버가 함께 보낸 검사 결과를 꺼낸다 */
export function reportOf(err: unknown): OriginalityReport | null {
  if (!(err instanceof ApiError) || err.code !== 'SHORTS_NOT_ORIGINAL') return null
  const details = err.details as { report?: OriginalityReport } | null
  return details?.report ?? null
}

export const listShorts = () => api<ShortsOverview>('/api/me/shorts')
export const createShorts = (body: { postId: number | null; title: string; scenes: Scene[] }) =>
  api<ShortsItem>('/api/me/shorts', { method: 'POST', body })
export const saveShorts = (id: number, body: { title: string; scenes: Scene[]; version: number }) =>
  api<ShortsItem>(`/api/me/shorts/${id}`, { method: 'PUT', body })
export const checkShorts = (id: number) => api<OriginalityReport>(`/api/me/shorts/${id}/check`, { method: 'POST' })
export const renderShorts = (id: number) => api<ShortsItem>(`/api/me/shorts/${id}/render`, { method: 'POST' })
export const deleteShorts = (id: number) => api<void>(`/api/me/shorts/${id}`, { method: 'DELETE' })
