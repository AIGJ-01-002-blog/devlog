// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { describe, expect, it } from 'vitest'
import type { TagRowData } from '../lib/tags'
import type { Card } from '../lib/types'
import { TagRow } from './TagRow'

const card = (id: number, likeCount = 0): Card => ({
  id, url: `/@minseo/posts/${id}`, title: `글 ${id}`, excerpt: null, thumbnailUrl: null, firstPublicAt: '2026-10-08T00:00:00Z',
  publishedAt: '2026-10-08T00:00:00Z', visibility: 'PUBLIC', commentCount: 0, likeCount, viewCount: 0, tags: [],
  author: { id: 1, handle: 'minseo', nickname: '민서', profileImageUrl: null },
})

async function render(row: TagRowData) {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const el = document.createElement('div'); document.body.appendChild(el)
  await act(async () => { createRoot(el).render(<TagRow row={row} />) })
  return el
}

describe('TagRow (spec 089)', () => {
  it('태그 이름·글 수와 인기 글 카드를 순서대로 그린다', async () => {
    const el = await render({ name: 'spring', postCount: 2, lastUsedAt: null, posts: [card(2, 5), card(1)] })
    expect(el.querySelector('.tag-row-title a')!.getAttribute('href')).toBe('/tags/spring')
    expect(el.querySelector('.tag-row-count')!.textContent).toBe('글 2편')
    expect([...el.querySelectorAll('.mini-card-title')].map((n) => n.textContent)).toEqual(['글 2', '글 1'])
    expect(el.querySelector('.mini-card-likes')!.getAttribute('data-tip')).toBe('좋아요 5개')
    // 줄에 다 실렸으면 [전체 보기]가 없다
    expect(el.querySelector('.tag-row-all')).toBeNull()
  })

  it('글이 줄보다 많으면 끝에 남은 수와 [전체 보기]를 단다', async () => {
    const el = await render({ name: 'c#', postCount: 12, lastUsedAt: null, posts: [card(1), card(2)] })
    const all = el.querySelector('.tag-row-all')!
    expect(all.getAttribute('href')).toBe('/tags/c%23')
    expect(all.textContent).toContain('+10편')
    expect(all.getAttribute('data-tip')).toBe('#c# 태그 글 12편 모두 보기')
  })

  it('[이전]/[다음] 버튼은 이름과 툴팁이 있고, 넘칠 게 없으면 꺼진다', async () => {
    const el = await render({ name: 'java', postCount: 1, lastUsedAt: null, posts: [card(1)] })
    const buttons = [...el.querySelectorAll<HTMLButtonElement>('.tag-row-nav button')]
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['#java 이전 글', '#java 다음 글'])
    expect(buttons.map((b) => b.getAttribute('data-tip'))).toEqual(['이전 글', '다음 글'])
    expect(buttons.every((b) => b.disabled)).toBe(true)
  })
})
