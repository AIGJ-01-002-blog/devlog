// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ShortsItem } from '../lib/shorts'
import { StudioUploadGuide } from './StudioUploadGuide'

// spec 103: 올리기 안내에서 고른 플랫폼의 내 채널로 바로 가거나, 채널을 넣는 설정으로 안내한다
describe('StudioUploadGuide 내 채널', () => {
  let root: Root
  let host: HTMLDivElement
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const item: ShortsItem = {
    id: 5, postId: null, postTitle: null, postUrl: null, title: '소켓 하나로 웹 서버 만들기',
    scenes: [{ caption: '소켓', narration: '소켓 하나로 시작해요.', code: null }],
    status: 'READY', attempts: 0, videoUrl: '/media/shorts/a.mp4', durationMs: 30000, videoBytes: 1000, error: null,
    estimatedSeconds: 30, queuedAt: null, renderedAt: null, createdAt: '', updatedAt: '', version: 1,
    postTags: [], uploads: [], replay: null, batchId: null, topic: null, character: null, outro: true, onBlog: true, playlist: null,
  }
  const serve = (socialLinks: unknown) => vi.stubGlobal('fetch', vi.fn(async () => json({ socialLinks })))

  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.unstubAllGlobals()
  })

  const tab = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find((b) => b.textContent?.startsWith(name))!

  it('채널을 넣었으면 고른 플랫폼의 내 채널을 새 탭으로 연다', async () => {
    serve({ youtube: '@devlog_kim', tiktok: 'dev.kim' })
    await act(async () => root.render(<StudioUploadGuide item={item} onChange={() => {}} />))
    const open = () => [...host.querySelectorAll('a')].find((a) => a.textContent === '내 채널 열기')
    expect(open()?.getAttribute('href')).toBe('https://www.youtube.com/@devlog_kim')
    expect(open()?.target).toBe('_blank')
    expect(open()?.dataset.tip).toBeTruthy()

    await act(async () => { tab('TikTok').click() })
    expect(open()?.getAttribute('href')).toBe('https://www.tiktok.com/@dev.kim')

    await act(async () => { tab('Instagram').click() })
    expect(open()).toBeUndefined()
    expect([...host.querySelectorAll('a')].find((a) => a.textContent === '설정에서 채널 넣기')?.getAttribute('href')).toBe('/settings')
  })

  it('남긴 주소가 있으면 블로그에 보일지 고를 수 있다', async () => {
    const sent: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (raw: string, init?: RequestInit) => {
      if (String(raw).endsWith('/blog')) {
        sent.push(JSON.parse(String(init?.body)))
        return json({ ...withUpload, onBlog: false })
      }
      return json({ socialLinks: {} })
    }))
    const withUpload = { ...item, uploads: [{ platform: 'YOUTUBE' as const, url: 'https://youtube.com/shorts/a', uploadedAt: '' }] }
    let current: ShortsItem = withUpload
    await act(async () => root.render(<StudioUploadGuide item={current} onChange={(n) => { current = n }} />))
    const box = host.querySelector<HTMLInputElement>('.studio-onblog input')!
    expect(box.checked).toBe(true)
    await act(async () => { box.click() })
    expect(sent).toEqual([{ visible: false }])
    expect(current.onBlog).toBe(false)

    await act(async () => root.render(<StudioUploadGuide item={item} onChange={() => {}} />))
    expect(host.querySelector('.studio-onblog')).toBeNull()
  })

  it('글 쇼츠는 YouTube 탭에서 재생목록 묶음을 고르고, 고른 묶음의 재생목록 이름이 순서에 나온다', async () => {
    const sent: unknown[] = []
    const ofPost = { ...item, postId: 7, postUrl: '/@minseo/posts/7' }
    vi.stubGlobal('fetch', vi.fn(async (raw: string, init?: RequestInit) => {
      if (String(raw).endsWith('/playlist')) {
        sent.push(JSON.parse(String(init?.body)))
        return json({ ...ofPost, playlist: 'PERFORMANCE' })
      }
      return json({ socialLinks: {} })
    }))
    let current: ShortsItem = ofPost
    await act(async () => root.render(<StudioUploadGuide item={current} onChange={(n) => { current = n }} />))
    const select = host.querySelector<HTMLSelectElement>('#studio-playlist')!
    await act(async () => {
      select.value = 'PERFORMANCE'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })
    expect(sent).toEqual([{ playlist: 'PERFORMANCE' }])
    await act(async () => root.render(<StudioUploadGuide item={current} onChange={() => {}} />))
    expect(host.querySelector('.studio-steps')?.textContent).toContain('"[백엔드 면접] 성능"')

    await act(async () => { tab('Instagram').click() })
    expect(host.querySelector('#studio-playlist')).toBeNull()
    await act(async () => root.render(<StudioUploadGuide item={item} onChange={() => {}} />))
    expect(host.querySelector('#studio-playlist')).toBeNull()
  })
})
