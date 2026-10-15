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
  image: 500,
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
  /** 장면 배경 그림 (선택, devlog에 올린 그림 주소나 /로 시작하는 사이트 경로) */
  image?: string | null
  /** 장면 연출 이름 (선택, 비우면 렌더 워커가 고름) */
  visual?: SceneVisual | null
  /** 장면 안 효과음 (선택, 연결한 AI가 넣음. 편집기는 그대로 보존) */
  effects?: { sound: string; at: number; volume?: number | null }[]
  /** 설명 도식 (선택, 연결한 AI가 넣음: flow·stack·steps·compare). 편집기는 그대로 보존 */
  diagram?: { type: string; [key: string]: unknown } | null
}

export const SCENE_VISUALS = ['explain', 'shock', 'typing', 'panic', 'restore', 'wait', 'fast', 'idea', 'protect', 'think', 'celebrate'] as const
export type SceneVisual = (typeof SCENE_VISUALS)[number]

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
  /** 바탕 글의 태그 (올리기 안내의 해시태그) */
  postTags: string[]
  /** 직접 올린 뒤 남긴 영상 주소 */
  uploads: ShortsUpload[]
  /** 매매 재현 영상(#265)이면 고른 체결과 차트 구간. 글 쇼츠는 null */
  replay: TradeReplay | null
  /** 주제 묶음(#276)에서 만든 편이면 묶음 번호와 주제 번호(1부터) */
  batchId: number | null
  topic: number | null
}

/** 주제 묶음의 한 주제. 아직 대본이 없으면 shortsId·status가 null */
export interface BatchTopic {
  number: number
  title: string
  hook: string
  scope: string
  shortsId: number | null
  status: ShortsStatus | null
}

/** 글 하나나 MCP로 받은 맥락 하나를 작은 주제 여러 개로 나눈 묶음 (#276). AI가 plan_shorts로 만든다 */
export interface ShortsBatch {
  id: number
  postId: number | null
  postTitle: string | null
  title: string
  /** MCP로 받은 맥락 근거가 있는가 */
  hasSource: boolean
  topics: BatchTopic[]
  createdAt: string
  updatedAt: string
}

export interface BatchRenderResult {
  queued: number[]
  skipped: { shortsId: number; title: string; reason: string }[]
}

/** 고를 수 있는 봉. 서버 TradeReplay.Interval과 같은 이름 */
export type ReplayInterval = '1m' | '5m' | '15m' | '1h' | '4h' | '1d'
export const REPLAY_INTERVALS: ReplayInterval[] = ['1m', '5m', '15m', '1h', '4h', '1d']
/** 한 영상에 담는 체결 수 (서버 TradeReplay.FILLS_MAX) */
export const REPLAY_FILLS_MAX = 6

export function intervalLabel(i: string): string {
  const labels: Record<string, string> = {
    '1m': t('1분봉'), '5m': t('5분봉'), '15m': t('15분봉'), '1h': t('1시간봉'), '4h': t('4시간봉'), '1d': t('일봉'),
  }
  return labels[i] ?? i
}

export interface TradeReplayFill {
  tradeId: number
  side: 'BUY' | 'SELL'
  price: number
  quantity: number
  realizedPnl: number | null
  executedAt: string
  reason: string
}

export interface TradeReplay {
  exchange: string
  symbol: string
  symbolName: string
  currency: string
  mode: 'PAPER' | 'LIVE'
  interval: ReplayInterval
  from: string
  to: string
  fills: TradeReplayFill[]
}

export type Platform = 'YOUTUBE' | 'INSTAGRAM' | 'TIKTOK'

export interface ShortsUpload {
  platform: Platform
  url: string
  uploadedAt: string
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
export const listBatches = () => api<ShortsBatch[]>('/api/me/shorts/batches')
export const renderBatch = (id: number) => api<BatchRenderResult>(`/api/me/shorts/batches/${id}/render`, { method: 'POST' })
export const deleteBatch = (id: number) => api<void>(`/api/me/shorts/batches/${id}`, { method: 'DELETE' })

/** 대본을 다 쓴 주제 수 */
export const writtenTopics = (b: ShortsBatch) => b.topics.filter((t) => t.shortsId !== null).length

/** 목록을 묶음별로 나눈다: 묶음마다 주제 순서로, 묶음에 없는 대본은 따로 */
export function groupByBatch(items: ShortsItem[], batches: ShortsBatch[]): { batch: ShortsBatch; items: ShortsItem[] }[] {
  return batches.map((batch) => ({
    batch,
    items: items.filter((it) => it.batchId === batch.id).sort((a, b) => (a.topic ?? 0) - (b.topic ?? 0)),
  }))
}
export const createShorts = (body: { postId: number | null; title: string; scenes: Scene[] }) =>
  api<ShortsItem>('/api/me/shorts', { method: 'POST', body })
export const saveShorts = (id: number, body: { title: string; scenes: Scene[]; version: number }) =>
  api<ShortsItem>(`/api/me/shorts/${id}`, { method: 'PUT', body })
export const createReplay = (tradeIds: number[], interval: ReplayInterval | null) =>
  api<ShortsItem>('/api/me/shorts/replay', { method: 'POST', body: { tradeIds, interval } })
/** 내 AI에게 보낼 부탁. AI가 get_shorts로 체결·메모를 읽고 update_shorts_script로 대본을 다시 쓴다 */
export const replayPrompt = (id: number) =>
  t('devlog 쇼츠 {0}번은 매매 재현 영상이에요. get_shorts로 고른 체결과 메모를 읽고, 그 시각에 매매하는 것처럼 "지금 사는 이유"를 말하는 대본으로 update_shorts_script 해 줘.', { 0: id })
export const checkShorts = (id: number) => api<OriginalityReport>(`/api/me/shorts/${id}/check`, { method: 'POST' })
export const renderShorts = (id: number) => api<ShortsItem>(`/api/me/shorts/${id}/render`, { method: 'POST' })
export const deleteShorts = (id: number) => api<void>(`/api/me/shorts/${id}`, { method: 'DELETE' })

export const recordUpload = (id: number, platform: Platform, url: string) =>
  api<ShortsItem>(`/api/me/shorts/${id}/uploads/${platform.toLowerCase()}`, { method: 'PUT', body: { url } })
export const deleteUpload = (id: number, platform: Platform) =>
  api<ShortsItem>(`/api/me/shorts/${id}/uploads/${platform.toLowerCase()}`, { method: 'DELETE' })

/**
 * 플랫폼별 직접 올리기 안내 (#238). devlog는 아무 데도 올리지 않고, 올릴 때 붙여 넣을 문구와 순서만 준다.
 * 제한 값은 2026년 10월에 확인한 각 플랫폼 안내 기준이다. 우리 영상은 60초 이하 9:16이라 길이·비율은 늘 맞고,
 * 글자 수와 해시태그 수만 문구에 따라 넘칠 수 있다. 넘치면 화면이 알려 주고 문구는 잘라 두지 않는다(내가 고쳐 쓰게).
 */
export interface PlatformSpec {
  id: Platform
  name: string
  /** 제목 칸이 따로 있으면 그 글자 수 한도 */
  titleMax: number | null
  captionMax: number
  hashtagMax: number
  secondsMax: number
  /** 설명·캡션의 링크를 누를 수 있는가. 못 누르면 고정 댓글·프로필 링크로 안내한다 */
  linksClickable: boolean
  uploadUrl: string
}

export const PLATFORMS: PlatformSpec[] = [
  { id: 'YOUTUBE', name: 'YouTube Shorts', titleMax: 100, captionMax: 5000, hashtagMax: 3, secondsMax: 180, linksClickable: true, uploadUrl: 'https://studio.youtube.com' },
  { id: 'INSTAGRAM', name: 'Instagram Reels', titleMax: null, captionMax: 2200, hashtagMax: 5, secondsMax: 180, linksClickable: false, uploadUrl: 'https://www.instagram.com' },
  { id: 'TIKTOK', name: 'TikTok', titleMax: null, captionMax: 2200, hashtagMax: 5, secondsMax: 600, linksClickable: false, uploadUrl: 'https://www.tiktok.com/upload' },
]

export const platformSpec = (id: Platform) => PLATFORMS.find((p) => p.id === id)!

/** 태그를 해시태그로: 해시태그에 못 쓰는 글자(. + # -)를 빼고, 겹치면 하나만 */
export function hashtags(tags: string[], max: number): string[] {
  const out: string[] = []
  for (const tag of tags) {
    const word = tag.replace(/[^\p{L}\p{N}_]/gu, '')
    if (word && !out.includes(`#${word}`)) out.push(`#${word}`)
    if (out.length >= max) break
  }
  return out
}

export interface GuideCheck {
  ok: boolean
  label: string
}

export interface UploadGuide {
  spec: PlatformSpec
  title: string | null
  caption: string
  pinnedComment: string
  tags: string[]
  checks: GuideCheck[]
  steps: string[]
}

/** 첫 장면 내레이션을 설명 첫 줄로 쓴다(영상의 첫마디와 같은 이야기로 시작하게) */
function hook(item: ShortsItem): string {
  return item.scenes.find((s) => s.narration.trim())?.narration.trim() ?? ''
}

export function uploadGuide(item: ShortsItem, platform: Platform, origin: string): UploadGuide {
  const spec = platformSpec(platform)
  const link = new URL(item.postUrl ?? '/', origin).href
  const tags = hashtags(item.postTags, spec.hashtagMax)
  const tagLine = tags.join(' ')
  const linkLine = spec.linksClickable ? t('전체 글: {0}', { 0: link }) : t('전체 글은 고정 댓글의 주소에서 볼 수 있어요.')
  const parts = spec.titleMax === null ? [item.title, hook(item), linkLine, tagLine] : [hook(item), linkLine, tagLine]
  const caption = parts.filter(Boolean).join('\n\n')
  const title = spec.titleMax === null ? null : item.title
  const seconds = (item.durationMs ?? item.estimatedSeconds * 1000) / 1000
  const checks: GuideCheck[] = [
    { ok: seconds <= spec.secondsMax, label: t('길이 {0}초 (최대 {1}초)', { 0: Math.round(seconds), 1: spec.secondsMax }) },
    { ok: true, label: t('세로 9:16 (1080×1920)') },
    ...(title === null ? [] : [{ ok: [...title].length <= (spec.titleMax ?? 0), label: t('제목 {0}자 (최대 {1}자)', { 0: [...title].length, 1: spec.titleMax ?? 0 }) }]),
    { ok: [...caption].length <= spec.captionMax, label: t('설명 {0}자 (최대 {1}자)', { 0: [...caption].length, 1: spec.captionMax }) },
    // 글 태그가 많으면 앞에서부터 한도만큼만 쓰니 늘 맞는다. 붙여 넣은 뒤 내가 더 달면 넘칠 수 있어 한도를 보여 준다
    { ok: tags.length <= spec.hashtagMax, label: t('해시태그 {0}개 (최대 {1}개)', { 0: tags.length, 1: spec.hashtagMax }) },
  ]
  return {
    spec, title, caption, tags, checks,
    pinnedComment: t('과정과 코드는 블로그에 정리해 뒀어요: {0}', { 0: link }),
    steps: uploadSteps(platform),
  }
}

function uploadSteps(platform: Platform): string[] {
  switch (platform) {
    case 'YOUTUBE': return [
      t('[영상 받기]로 MP4를 받아요. 휴대폰이면 [앱으로 보내기]를 눌러 YouTube 앱을 골라요.'),
      t('YouTube 앱에서 + → 동영상 업로드(PC는 YouTube 스튜디오 → 만들기 → 동영상 업로드)로 파일을 골라요.'),
      t('제목과 설명에 아래 문구를 붙여 넣어요.'),
      t('아동용 아님을 고르고, 목소리를 AI로 만든 사람 목소리처럼 들리면 변경된 콘텐츠를 예로 표시해요.'),
      t('공개로 올린 뒤 고정 댓글을 달고, 영상 주소를 아래에 남겨요.'),
    ]
    case 'INSTAGRAM': return [
      t('[영상 받기]로 MP4를 받아요. 휴대폰이면 [앱으로 보내기]를 눌러 Instagram을 골라요.'),
      t('Instagram 앱에서 + → 릴스로 영상을 골라요. 이미 자막과 음성이 있으니 음악은 넣지 않아도 돼요.'),
      t('캡션에 아래 문구를 붙여 넣어요. 캡션 링크는 누를 수 없으니 프로필 링크에 블로그 주소를 걸어 두면 좋아요.'),
      t('AI 음성을 썼다면 고급 설정에서 AI 정보 표시를 켜요.'),
      t('공유한 뒤 고정 댓글을 달고, 릴스 주소(공유 → 링크 복사)를 아래에 남겨요.'),
    ]
    case 'TIKTOK': return [
      t('[영상 받기]로 MP4를 받아요. 휴대폰이면 [앱으로 보내기]를 눌러 TikTok을 골라요.'),
      t('TikTok 앱에서 + → 업로드로 영상을 골라요(PC는 tiktok.com/upload).'),
      t('설명에 아래 문구를 붙여 넣어요. 설명 링크는 누를 수 없으니 고정 댓글로 안내해요.'),
      t('AI 음성을 썼다면 더 보기 옵션에서 AI 생성 콘텐츠 표시를 켜요.'),
      t('게시한 뒤 고정 댓글을 달고, 영상 주소(공유 → 링크 복사)를 아래에 남겨요.'),
    ]
  }
}

/** 한 번 받은 영상 파일. 느린 망에서 받는 사이 탭 허용 시간이 지나 공유가 막히면, 다시 누를 때 바로 넘긴다 */
const sharedFiles = new Map<string, File>()

/**
 * 휴대폰에서 영상을 바로 SNS 앱으로 넘긴다(Web Share API, 파일 공유).
 * 파일 공유를 못 하는 브라우저면 영상을 받기 전에 'unsupported'로 끝내고, 화면은 [영상 받기]로 안내한다.
 * 받는 사이 탭 허용 시간이 지나 막히면(NotAllowedError) 'retry': 받아 둔 파일로 한 번 더 누르면 된다.
 */
export async function shareVideo(url: string, fileName: string, title: string): Promise<'shared' | 'cancelled' | 'retry' | 'unsupported'> {
  const nav = typeof navigator === 'undefined' ? undefined : navigator
  if (!nav?.share || !nav.canShare) return 'unsupported'
  if (!nav.canShare({ files: [new File([], fileName, { type: 'video/mp4' })] })) return 'unsupported'
  let file = sharedFiles.get(url)
  if (!file) {
    const res = await fetch(url, { credentials: 'same-origin' })
    if (!res.ok) return 'unsupported'
    file = new File([await res.blob()], fileName, { type: 'video/mp4' })
    sharedFiles.clear()
    sharedFiles.set(url, file)
  }
  try {
    await nav.share({ files: [file], title })
    return 'shared'
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return 'cancelled'
    if (e instanceof DOMException && e.name === 'NotAllowedError') return 'retry'
    return 'unsupported'
  }
}

export const canShareFiles = () => typeof navigator !== 'undefined' && !!navigator.share && !!navigator.canShare
