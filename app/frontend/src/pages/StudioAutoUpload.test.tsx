// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../lib/api'
import type { AutoJob, AutoPlatformState, AutoUploadStatus } from '../lib/autoUpload'
import * as auto from '../lib/autoUpload'
import type { ShortsItem } from '../lib/shorts'
import { AutoUploadJobs, StudioAutoUpload } from './StudioAutoUpload'

vi.mock('../lib/autoUpload', async (importOriginal) => {
  const real = await importOriginal<typeof import('../lib/autoUpload')>()
  return { ...real, autoUploadStatus: vi.fn(), testUpload: vi.fn(), autoJobs: vi.fn(), retryJob: vi.fn() }
})

// spec 108: 자동 올리기 카드. 시험을 통과해야 켤 수 있고, 실패·못 읽음을 숨기지 않는다
describe('StudioAutoUpload', () => {
  let root: Root
  let host: HTMLDivElement
  const item: ShortsItem = {
    id: 5, postId: null, postTitle: null, postUrl: null, title: '소켓 하나로 웹 서버 만들기',
    scenes: [{ caption: '소켓', narration: '소켓 하나로 시작해요.', code: null }],
    status: 'READY', attempts: 0, videoUrl: '/media/shorts/a.mp4', durationMs: 30000, videoBytes: 1000, error: null,
    estimatedSeconds: 30, queuedAt: null, renderedAt: null, createdAt: '', updatedAt: '', version: 1,
    postTags: [], uploads: [], replay: null, batchId: null, topic: null, character: null, outro: true, onBlog: true, playlist: null,
  }
  const yt = (over: Partial<AutoPlatformState> = {}): AutoPlatformState => ({
    platform: 'YOUTUBE', configured: true, connected: true, accountName: 'devlog', enabled: false, testedAt: null, testUrl: null,
    testError: null, tokenExpiresAt: null, redirectUri: 'https://devlog.life/api/me/shorts/auto-upload/youtube/callback', ...over,
  })
  const status = (p: AutoPlatformState): AutoUploadStatus => ({ platforms: [p], publishTime: '20:00' })

  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.clearAllMocks()
  })

  it('쓸 수 없으면(null) 불러오는 중에 머물지 않고 까닭을 보여 준다', async () => {
    vi.mocked(auto.autoUploadStatus).mockResolvedValue(null)
    await act(async () => root.render(<StudioAutoUpload items={[]} result={null} resultPlatform={null} />))
    expect(host.textContent).not.toContain('불러오는 중')
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('자동 올리기를 쓸 수 없어요')
  })

  it('시험 전에는 켤 수 없고, 시험이 실패하면 실패 안내를 보여 준다', async () => {
    vi.mocked(auto.autoUploadStatus).mockResolvedValue(status(yt()))
    vi.mocked(auto.testUpload).mockRejectedValue(new ApiError(422, 'AUTO_UPLOAD_TEST_FAILED', 'YouTube가 거절했어요.'))
    await act(async () => root.render(<StudioAutoUpload items={[item]} result={null} resultPlatform={null} />))
    expect(host.querySelector<HTMLInputElement>('input[type="checkbox"]')!.disabled).toBe(true)
    const test = [...host.querySelectorAll('button')].find((b) => b.textContent === '시험 올리기')!
    await act(async () => test.click())
    expect(auto.testUpload).toHaveBeenCalledWith('YOUTUBE', 5)
    expect(host.querySelector('.error.small[role="status"]')?.textContent).toBe('YouTube가 거절했어요.')
  })

  it('작업을 못 읽으면 알리고, 다시 올리기는 한 번만 보낸다', async () => {
    const failed: AutoJob = { platform: 'YOUTUBE', status: 'FAILED', attempts: 3, publishAt: '2026-10-29T11:00:00Z', url: null, error: '실패',
      note: null, updatedAt: '2026-10-28T02:00:00Z' }
    vi.mocked(auto.autoJobs).mockRejectedValueOnce(new ApiError(500, 'ERROR', '서버 오류'))
    await act(async () => root.render(<AutoUploadJobs item={item} />))
    expect(host.querySelector('[role="alert"]')?.textContent).toBe('서버 오류')

    act(() => root.unmount())
    root = createRoot(host)
    vi.mocked(auto.autoJobs).mockResolvedValue([failed])
    let finish: (jobs: AutoJob[]) => void = () => {}
    vi.mocked(auto.retryJob).mockReturnValue(new Promise((r) => { finish = r }))
    await act(async () => root.render(<AutoUploadJobs item={item} />))
    const retry = [...host.querySelectorAll('button')].find((b) => b.textContent === '다시 올리기')!
    await act(async () => { retry.click(); retry.click() })
    expect(auto.retryJob).toHaveBeenCalledTimes(1)
    await act(async () => finish([{ ...failed, status: 'QUEUED', attempts: 0, error: null }]))
    expect(host.textContent).toContain('공개로 올릴 차례를 기다려요')
  })
})
