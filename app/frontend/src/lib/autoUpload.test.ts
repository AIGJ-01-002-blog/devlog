import { describe, expect, it } from 'vitest'
import { canEnable, connectResultMessage, jobLabel, type AutoJob, type AutoPlatformState } from './autoUpload'

const state = (over: Partial<AutoPlatformState> = {}): AutoPlatformState => ({
  platform: 'YOUTUBE', configured: true, connected: true, accountName: 'devlog', enabled: false, testedAt: null, testUrl: null,
  testError: null, tokenExpiresAt: null, redirectUri: 'https://devlog.life/api/me/shorts/auto-upload/youtube/callback', ...over,
})

const job = (over: Partial<AutoJob> = {}): AutoJob => ({
  platform: 'YOUTUBE', status: 'QUEUED', attempts: 0, publishAt: '2026-10-29T11:00:00Z', url: null, error: null, note: null,
  updatedAt: '2026-10-28T02:00:00Z', ...over,
})

describe('autoUpload (spec 108)', () => {
  it('시험 올리기를 통과한 연결만 켤 수 있다', () => {
    expect(canEnable(state())).toBe(false)
    expect(canEnable(state({ testedAt: '2026-10-28T02:00:00Z' }))).toBe(true)
    expect(canEnable(state({ connected: false, testedAt: '2026-10-28T02:00:00Z' }))).toBe(false)
  })

  it('작업 상태를 공개 시각과 함께 알려 준다', () => {
    expect(jobLabel(job({ status: 'SCHEDULED' }))).toMatch(/^비공개로 올리고 10월 \d+일 \d\d:\d\d에 공개 예약했어요$/)
    expect(jobLabel(job({ attempts: 2 }))).toBe('다시 해 볼 차례예요 (2번 실패)')
    expect(jobLabel(job({ status: 'FAILED' }))).toBe('올리지 못했어요')
    expect(jobLabel(job({ status: 'PUBLISHED' }))).toMatch(/에 공개했어요$/)
  })

  it('연결 콜백 결과를 안내 문구로 바꾼다', () => {
    expect(connectResultMessage(null, null)).toBeNull()
    expect(connectResultMessage('connected', 'youtube')).toEqual({ ok: true, text: 'YouTube 계정을 이었어요. [시험 올리기]를 통과하면 자동 올리기를 켤 수 있어요.' })
    expect(connectResultMessage('denied', 'instagram')?.ok).toBe(false)
    expect(connectResultMessage('state', 'youtube')?.text).toContain('[계정 연결]을 다시')
    expect(connectResultMessage('failed', 'instagram')?.text).toContain('Instagram 계정을 잇지 못했어요')
  })
})
