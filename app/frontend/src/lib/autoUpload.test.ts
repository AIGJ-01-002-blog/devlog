import { describe, expect, it } from 'vitest'
import { canEnable, connectResultMessage, jobDone, jobLabel, type AutoJob, type AutoPlatformState } from './autoUpload'

const state = (over: Partial<AutoPlatformState> = {}): AutoPlatformState => ({
  platform: 'YOUTUBE', configured: true, connected: true, accountName: 'devlog', enabled: false, testedAt: null, testUrl: null,
  testError: null, tokenExpiresAt: null, redirectUri: 'https://devlog.life/api/me/shorts/auto-upload/youtube/callback', ...over,
})

const job = (over: Partial<AutoJob> = {}): AutoJob => ({
  platform: 'YOUTUBE', status: 'QUEUED', attempts: 0, publishAt: '2026-10-29T11:00:00Z', url: null, error: null, note: null,
  updatedAt: '2026-10-28T02:00:00Z', privateOnly: false, ...over,
})

describe('autoUpload (spec 108)', () => {
  it('시험 올리기를 통과한 연결만 켤 수 있다', () => {
    expect(canEnable(state())).toBe(false)
    expect(canEnable(state({ testedAt: '2026-10-28T02:00:00Z' }))).toBe(true)
    expect(canEnable(state({ connected: false, testedAt: '2026-10-28T02:00:00Z' }))).toBe(false)
  })

  it('작업 상태를 공개 시각과 함께 알려 준다', () => {
    // 2026-10-29T11:00Z는 한국 시간 10월 29일 20:00. 브라우저 시간대와 상관없이 한국 시간으로 보인다
    expect(jobLabel(job({ status: 'SCHEDULED' }))).toBe('비공개로 올리고 10월 29일 20:00에 공개 예약했어요')
    expect(jobLabel(job({ status: 'SCHEDULED', publishAt: '2026-10-29T15:30:00Z' }))).toBe('비공개로 올리고 10월 30일 00:30에 공개 예약했어요')
    expect(jobLabel(job({ attempts: 2 }))).toBe('다시 해 볼 차례예요 (2번 실패)')
    expect(jobLabel(job({ status: 'FAILED' }))).toBe('올리지 못했어요')
    expect(jobLabel(job({ status: 'PUBLISHED' }))).toMatch(/에 공개했어요$/)
  })

  it('비공개로만 올리는 작업은 공개 시각 대신 비공개로 올렸다고 알려 준다 (#501)', () => {
    const p = (over: Partial<AutoJob>) => job({ privateOnly: true, ...over })
    expect(jobLabel(p({}))).toBe('비공개로 올릴 차례를 기다려요')
    expect(jobLabel(p({ attempts: 1 }))).toBe('다시 해 볼 차례예요 (1번 실패)')
    expect(jobLabel(p({ status: 'UPLOADING' }))).toBe('비공개로 올리는 중')
    expect(jobLabel(p({ status: 'UPLOADED' }))).toBe('10월 29일 20:00에 비공개로 올렸어요. 공개는 YouTube 스튜디오에서 해 주세요')
    expect(jobLabel(p({ status: 'FAILED' }))).toBe('올리지 못했어요')
    expect(jobDone(p({ status: 'UPLOADED' }))).toBe(true)
    expect(jobDone(p({ status: 'QUEUED' }))).toBe(false)
  })

  it('연결 콜백 결과를 안내 문구로 바꾼다', () => {
    expect(connectResultMessage(null, null)).toBeNull()
    expect(connectResultMessage('connected', 'youtube')).toEqual({ ok: true, text: 'YouTube 계정을 이었어요. [시험 올리기]를 통과하면 자동 올리기를 켤 수 있어요.' })
    expect(connectResultMessage('denied', 'instagram')?.ok).toBe(false)
    expect(connectResultMessage('state', 'youtube')?.text).toContain('[계정 연결]을 다시')
    expect(connectResultMessage('failed', 'instagram')?.text).toContain('Instagram 계정을 잇지 못했어요')
  })
})
