// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StudioPostPicker, type PickedPost } from './StudioPostPicker'

// 쇼츠 바탕 글 고르기 (#308): 최신순 목록, 끝에서 더 불러오기, 제목 검색
describe('StudioPostPicker', () => {
  let root: Root
  let host: HTMLDivElement
  let urls: URL[]
  let picked: PickedPost | null | undefined

  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const posts = (from: number, n: number) => Array.from({ length: n }, (_, i) => ({ id: from + i, title: `글 ${from + i}` }))

  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.useFakeTimers()
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    urls = []
    picked = undefined
    vi.stubGlobal('fetch', vi.fn(async (raw: string) => {
      const url = new URL(raw, 'http://x')
      urls.push(url)
      const q = url.searchParams.get('q')
      if (q) return json({ items: q === '도커' ? [{ id: 99, title: '도커 볼륨 복구' }] : [], nextCursor: null, counts: null })
      return url.searchParams.get('cursor')
        ? json({ items: posts(21, 3), nextCursor: null, counts: null })
        : json({ items: posts(1, 20), nextCursor: 'c1', counts: null })
    }))
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const render = async () => {
    await act(async () => root.render(<StudioPostPicker value={null} onChange={(p) => { picked = p }} />))
    await settle()
  }
  const settle = async (ms = 0) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
  const type = async (v: string) => {
    const input = host.querySelector('input[type="search"]') as HTMLInputElement
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    await act(async () => { setter.call(input, v); input.dispatchEvent(new Event('input', { bubbles: true })) })
  }
  const titles = () => [...host.querySelectorAll('.post-picker-item span')].map((s) => s.textContent)

  it('공개 글을 최신순으로 보여 주고 [글 더 보기]로 다음 쪽을 이어 붙인다', async () => {
    await render()
    expect(urls[0].searchParams.get('tab')).toBe('published')
    expect(titles()).toHaveLength(21) // 글 없이 쓰기 + 20개
    const more = [...host.querySelectorAll('button')].find((b) => b.textContent === '글 더 보기')!
    await act(async () => more.click())
    await settle()
    expect(urls[1].searchParams.get('cursor')).toBe('c1')
    expect(titles()).toHaveLength(24)
    expect([...host.querySelectorAll('button')].some((b) => b.textContent === '글 더 보기')).toBe(false)
  })

  it('검색어를 다 친 뒤 한 번만 전체 글에서 찾고, 고르면 알린다', async () => {
    await render()
    await type('도')
    await type('도커')
    await settle(300)
    expect(urls.filter((u) => u.searchParams.has('q')).map((u) => u.searchParams.get('q'))).toEqual(['도커'])
    expect(titles()).toEqual(['글 없이 쓰기', '도커 볼륨 복구'])
    const radio = host.querySelectorAll('input[type="radio"]')[1] as HTMLInputElement
    await act(async () => radio.click())
    expect(picked).toEqual({ id: 99, title: '도커 볼륨 복구' })

    await type('없는 글')
    await settle(300)
    expect(host.textContent).toContain('"없는 글"이 들어간 공개 글이 없어요.')
  })
})
