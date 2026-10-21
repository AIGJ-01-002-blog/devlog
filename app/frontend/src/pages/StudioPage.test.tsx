// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ShortsItem } from '../lib/shorts'
import { StudioPage } from './StudioPage'

// 새 대본·매매 재현 화면이 열린 채로 목록의 대본을 눌러도 그 대본으로 넘어간다 (#312)
describe('StudioPage', () => {
  let root: Root
  let host: HTMLDivElement

  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const item: ShortsItem = {
    id: 5, postId: null, postTitle: null, postUrl: null, title: '소켓 하나로 웹 서버 만들기',
    scenes: [{ caption: '소켓', narration: '소켓 하나로 시작해요.', code: null }],
    status: 'QUEUED', attempts: 0, videoUrl: null, durationMs: null, videoBytes: null, error: null,
    estimatedSeconds: 5, queuedAt: null, renderedAt: null, createdAt: '', updatedAt: '', version: 1,
    postTags: [], uploads: [], replay: null, batchId: null, topic: null, character: null, outro: true, onBlog: true, playlist: null,
  }

  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    history.replaceState(null, '', '/studio')
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    window.scrollTo = vi.fn() as never
    vi.stubGlobal('requestAnimationFrame', vi.fn())
    vi.stubGlobal('fetch', vi.fn(async (raw: string) => {
      const path = new URL(raw, 'http://x').pathname
      if (path === '/api/me/shorts') return json({ items: [item], workerSeenAt: null, rendersToday: 0, dailyRenders: 10 })
      if (path === '/api/me/shorts/batches') return json([])
      return json({ items: [], nextCursor: null, counts: null })
    }))
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.unstubAllGlobals()
  })

  const heading = () => host.querySelector('.studio-editor h2')?.textContent
  const click = async (el: Element | null) => { await act(async () => { (el as HTMLElement).click() }) }

  it('새 대본을 쓰다가 목록의 대본을 누르면 그 대본 편집으로 넘어간다', async () => {
    await act(async () => root.render(<StudioPage />))
    await click([...host.querySelectorAll('button')].find((b) => b.textContent === '새 대본')!)
    expect(heading()).toBe('새 대본')
    await click(host.querySelector('a.studio-item'))
    expect(location.search).toBe('?id=5')
    expect(heading()).not.toBe('새 대본')
    expect(host.querySelector<HTMLInputElement>('.studio-editor input')!.value).toBe('소켓 하나로 웹 서버 만들기')
  })

  it('끝 블로그 광고를 끄고 저장하면 outro: false로 보낸다', async () => {
    const draft = { ...item, status: 'DRAFT' as const }
    const sent: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (raw: string, init?: RequestInit) => {
      const path = new URL(raw, 'http://x').pathname
      if (path === '/api/me/shorts') return json({ items: [draft], workerSeenAt: null, rendersToday: 0, dailyRenders: 10 })
      if (path === '/api/me/shorts/5' && init?.method === 'PUT') {
        sent.push(JSON.parse(String(init.body)))
        return json({ ...draft, outro: false, version: 2 })
      }
      if (path === '/api/me/shorts/batches') return json([])
      return json({ items: [], nextCursor: null, counts: null })
    }))
    history.replaceState(null, '', '/studio?id=5')
    await act(async () => root.render(<StudioPage />))
    const toggle = host.querySelector<HTMLInputElement>('.studio-outro input')!
    expect(toggle.checked).toBe(true)
    await click(toggle)
    await click([...host.querySelectorAll('button')].find((b) => b.textContent === '저장')!)
    expect(sent).toEqual([expect.objectContaining({ outro: false })])
  })
})
