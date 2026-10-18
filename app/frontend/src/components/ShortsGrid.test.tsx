// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BlogShortsCard } from '../lib/shorts'
import { PostShorts, ShortsGrid } from './ShortsGrid'

// spec 103: 직접 올린 쇼츠를 블로그·글 화면에 보인다
describe('ShortsGrid', () => {
  let root: Root
  let host: HTMLDivElement
  const yt: BlogShortsCard = {
    id: 1, title: '인덱스 하나로 35ms', postTitle: '느린 API', postUrl: '/@kim/posts/3', durationMs: 31000, uploadedAt: '2026-10-18T00:00:00Z',
    links: [{ platform: 'YOUTUBE', url: 'https://youtube.com/shorts/AbCdEfGhIj0', embedId: 'AbCdEfGhIj0' },
      { platform: 'INSTAGRAM', url: 'https://www.instagram.com/reel/Cx/', embedId: null }],
  }
  const tiktok: BlogShortsCard = {
    id: 2, title: '틱톡에만 올린 쇼츠', postTitle: null, postUrl: null, durationMs: null, uploadedAt: '2026-10-18T00:00:00Z',
    links: [{ platform: 'TIKTOK', url: 'https://www.tiktok.com/@kim/video/1', embedId: null }],
  }

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

  it('YouTube는 썸네일을 누르면 그 자리에서 youtube-nocookie로 재생한다', async () => {
    await act(async () => root.render(<ShortsGrid cards={[yt, tiktok]} showPost />))
    expect(host.querySelector('iframe')).toBeNull()
    const play = host.querySelector<HTMLButtonElement>('.shorts-play')!
    expect(play.querySelector('img')!.getAttribute('src')).toBe('https://i.ytimg.com/vi/AbCdEfGhIj0/hqdefault.jpg')
    expect(play.textContent).toContain('0:31')
    expect(play.dataset.tip).toBeTruthy()
    await act(async () => { play.click() })
    expect(host.querySelector('iframe')!.getAttribute('src')).toMatch(/^https:\/\/www\.youtube-nocookie\.com\/embed\/AbCdEfGhIj0\?/)
  })

  it('영상 ID가 없으면 그 플랫폼으로 가는 카드이고, 플랫폼마다 새 탭 링크와 바탕 글 링크를 보인다', async () => {
    await act(async () => root.render(<ShortsGrid cards={[yt, tiktok]} showPost />))
    const placeholder = host.querySelector<HTMLAnchorElement>('.shorts-placeholder')!
    expect(placeholder.href).toBe('https://www.tiktok.com/@kim/video/1')
    expect(placeholder.target).toBe('_blank')
    const chips = [...host.querySelectorAll<HTMLAnchorElement>('.shorts-links a')]
    expect(chips.map((a) => a.textContent)).toEqual(['YouTube', 'Instagram', 'TikTok'])
    expect(chips.every((a) => a.rel.includes('noopener') && a.dataset.tip)).toBe(true)
    expect(host.querySelector<HTMLAnchorElement>('.shorts-post')!.getAttribute('href')).toBe('/@kim/posts/3')

    await act(async () => root.render(<ShortsGrid cards={[yt]} showPost={false} />))
    expect(host.querySelector('.shorts-post')).toBeNull()
  })

  it('글 상세는 쇼츠가 있을 때만 "이 글의 쇼츠"를 그리고, 꺼져 있으면 받지도 않는다', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify([yt]), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    await act(async () => root.render(<PostShorts postId={3} enabled={false} />))
    expect(fetch).not.toHaveBeenCalled()
    expect(host.querySelector('.post-shorts')).toBeNull()

    await act(async () => root.render(<PostShorts postId={3} enabled />))
    expect(String((fetch.mock.calls[0] as unknown[])[0])).toBe('/api/posts/3/shorts')
    expect(host.querySelector('.post-shorts h2')!.textContent).toBe('이 글의 쇼츠')
  })
})
