import { api, ApiError } from './api'
import { t } from './i18n'

/**
 * 쇼츠 자동 올리기 (spec 108). 렌더가 끝난 영상을 블로그 주인의 YouTube·Instagram에 올린다.
 * 계정을 잇고 [시험 올리기]를 통과한 플랫폼만 켤 수 있고, 처음에는 모두 꺼져 있다.
 * 제목·캡션은 서버가 uploadGuide()와 같은 문구로 만든다(shortsCaptionParity.json으로 양쪽이 같은지 본다).
 */
export type AutoPlatform = 'YOUTUBE' | 'INSTAGRAM'
export const AUTO_PLATFORMS: AutoPlatform[] = ['YOUTUBE', 'INSTAGRAM']
export const AUTO_PLATFORM_NAMES: Record<AutoPlatform, string> = { YOUTUBE: 'YouTube', INSTAGRAM: 'Instagram' }

export interface AutoPlatformState {
  platform: AutoPlatform
  /** 서버에 그 플랫폼 앱 값이 있는지. 없으면 잇기 버튼을 막는다 */
  configured: boolean
  connected: boolean
  accountName: string | null
  enabled: boolean
  testedAt: string | null
  testUrl: string | null
  testError: string | null
  tokenExpiresAt: string | null
  /** 플랫폼 앱에 등록할 콜백 주소 */
  redirectUri: string
}

export interface AutoUploadStatus {
  platforms: AutoPlatformState[]
  publishTime: string
  /** YouTube는 공개 예약 없이 비공개로만 올린다(#501). 공개는 YouTube 스튜디오에서 직접 한다 */
  youtubePrivate: boolean
}

export type AutoJobStatus = 'QUEUED' | 'UPLOADING' | 'SCHEDULED' | 'UPLOADED' | 'PUBLISHED' | 'FAILED'

export interface AutoJob {
  platform: AutoPlatform
  status: AutoJobStatus
  attempts: number
  publishAt: string
  url: string | null
  error: string | null
  note: string | null
  updatedAt: string
  /** 비공개로만 올리는 작업이면 publishAt은 공개 시각이 아니라 올린 시각이다 */
  privateOnly: boolean
}

export interface AutoTestResult {
  platform: AutoPlatform
  passed: boolean
  url: string | null
  message: string
}

const slug = (p: AutoPlatform) => p.toLowerCase()

/** 쓸 수 없으면(관리자가 아님·서버 설정 없음) null */
export async function autoUploadStatus(): Promise<AutoUploadStatus | null> {
  try {
    const s = await api<AutoUploadStatus>('/api/me/shorts/auto-upload')
    // 모양이 다른 응답(다른 서버 버전 등)은 쓸 수 없음으로 본다
    return Array.isArray(s?.platforms) ? s : null
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

export const connectUrl = (p: AutoPlatform) =>
  api<{ url: string }>(`/api/me/shorts/auto-upload/${slug(p)}/connect`, { method: 'POST' }).then((r) => r.url)
export const disconnect = (p: AutoPlatform) => api<void>(`/api/me/shorts/auto-upload/${slug(p)}`, { method: 'DELETE' })
export const testUpload = (p: AutoPlatform, shortsId: number) =>
  api<AutoTestResult>(`/api/me/shorts/auto-upload/${slug(p)}/test`, { method: 'POST', body: { shortsId } })
export const setAutoEnabled = (p: AutoPlatform, enabled: boolean) =>
  api<AutoUploadStatus>(`/api/me/shorts/auto-upload/${slug(p)}/enabled`, { method: 'PUT', body: { enabled } })

/** 쓸 수 없으면 빈 목록 */
export async function autoJobs(shortsId: number): Promise<AutoJob[]> {
  try {
    const jobs = await api<AutoJob[]>(`/api/me/shorts/${shortsId}/auto-uploads`)
    return Array.isArray(jobs) ? jobs : []
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return []
    throw e
  }
}

export const retryJob = (shortsId: number, p: AutoPlatform) =>
  api<AutoJob[]>(`/api/me/shorts/${shortsId}/auto-uploads/${slug(p)}/retry`, { method: 'POST' })

/** 켜기는 시험 올리기를 통과한 뒤에만 */
export const canEnable = (s: AutoPlatformState) => s.connected && s.testedAt !== null

/** 공개 시각은 한국 시간으로 보인다(예약도 한국 시간 기준). 브라우저가 다른 시간대여도 같은 날짜·시각이 나온다 */
function kst(iso: string): string {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(iso)).map((p) => [p.type, p.value]))
  return `${t('{0}월 {1}일', { 0: parts.month, 1: parts.day })} ${parts.hour}:${parts.minute}`
}

/** 작업이 끝났는지(더 볼 일이 없는지) */
export const jobDone = (job: AutoJob) => job.status === 'PUBLISHED' || job.status === 'UPLOADED' || job.status === 'FAILED'

export function jobLabel(job: AutoJob): string {
  const when = kst(job.publishAt)
  if (job.privateOnly) {
    switch (job.status) {
      case 'QUEUED': return job.attempts > 0 ? t('다시 해 볼 차례예요 ({0}번 실패)', { 0: job.attempts }) : t('비공개로 올릴 차례를 기다려요')
      case 'UPLOADING': return t('비공개로 올리는 중')
      case 'UPLOADED': return t('{0}에 비공개로 올렸어요. 공개는 YouTube 스튜디오에서 해 주세요', { 0: when })
      default: break
    }
  }
  switch (job.status) {
    case 'QUEUED': return job.attempts > 0 ? t('다시 해 볼 차례예요 ({0}번 실패)', { 0: job.attempts }) : t('{0} 공개로 올릴 차례를 기다려요', { 0: when })
    case 'UPLOADING': return t('올리는 중 ({0} 공개)', { 0: when })
    case 'SCHEDULED': return t('비공개로 올리고 {0}에 공개 예약했어요', { 0: when })
    case 'UPLOADED': return t('{0}에 비공개로 올렸어요. 공개는 YouTube 스튜디오에서 해 주세요', { 0: when })
    case 'PUBLISHED': return t('{0}에 공개했어요', { 0: when })
    case 'FAILED': return t('올리지 못했어요')
  }
}

/** 연결 콜백이 /studio?auto=1&result=…로 돌려보낸 결과 */
export function connectResultMessage(result: string | null, platform: string | null): { ok: boolean; text: string } | null {
  if (!result) return null
  const name = platform === 'instagram' ? 'Instagram' : platform === 'youtube' ? 'YouTube' : ''
  switch (result) {
    case 'connected': return { ok: true, text: t('{0} 계정을 이었어요. [시험 올리기]를 통과하면 자동 올리기를 켤 수 있어요.', { 0: name }) }
    case 'denied': return { ok: false, text: t('{0} 동의 화면에서 허용하지 않아 잇지 않았어요.', { 0: name }) }
    case 'state': return { ok: false, text: t('연결 요청이 오래됐거나 맞지 않아요. [계정 연결]을 다시 눌러 주세요.') }
    default: return { ok: false, text: t('{0} 계정을 잇지 못했어요. 잠시 뒤 다시 해 주세요.', { 0: name }) }
  }
}
