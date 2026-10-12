// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { describe, expect, it } from 'vitest'
import type { Card } from '../lib/types'
import { coverGlyph, coverTone, PostCard } from './PostCard'

const card = (over: Partial<Card> = {}): Card => ({
  id: 7, url: '/@minseo/posts/7', title: '운영체제 기초', excerpt: '요약', thumbnailUrl: null, firstPublicAt: '2026-10-08T00:00:00Z',
  publishedAt: '2026-10-08T00:00:00Z', visibility: 'PUBLIC', commentCount: 0, likeCount: 0, viewCount: 0, tags: [],
  author: { id: 1, handle: 'minseo', nickname: '민서', profileImageUrl: null }, ...over,
})

async function render(c: Card) {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const el = document.createElement('div'); document.body.appendChild(el)
  await act(async () => { createRoot(el).render(<PostCard card={c} />) })
  return el
}

describe('PostCard 표지 (048, 문의 #1)', () => {
  it('같은 글은 언제나 같은 색이고 0~3 안에 있다', () => {
    expect([0, 1, 2, 3, 4, 7].map(coverTone)).toEqual([0, 1, 2, 3, 0, 3])
    expect(coverTone(-1)).toBe(3)
  })

  it('제목 첫 글자를 대문자로, 빈 제목은 #', () => {
    expect(coverGlyph('spring ai')).toBe('S')
    expect(coverGlyph('  운영체제')).toBe('운')
    expect(coverGlyph('🚀 배포')).toBe('🚀')
    expect(coverGlyph('')).toBe('#')
  })

  it('사진이 없으면 devlog 로고 표지, 있으면 사진 (문의 #1)', async () => {
    const empty = await render(card())
    const cover = empty.querySelector('.card-logo-cover')!
    expect(cover.querySelector('.logo-mark')).not.toBeNull()
    expect(cover.textContent).toBe('devlog')
    expect(cover.textContent).not.toContain('운')
    expect(empty.querySelector('.card-cover')).toBeNull()
    expect(cover.closest('[aria-hidden="true"]')).not.toBeNull()
    const withImage = await render(card({ thumbnailUrl: '/media/a.webp' }))
    expect(withImage.querySelector('.card-logo-cover')).toBeNull()
    expect(withImage.querySelector('img')?.getAttribute('src')).toBe('/media/a.webp')
  })
})

describe('PostCard 하이브리드 검색 (054)', () => {
  it('뜻으로만 찾은 글에는 툴팁이 달린 "비슷한 글" 표시가 붙는다', async () => {
    const similar = await render(card({ snippetHtml: '커밋 시점', similar: true }))
    const badge = similar.querySelector('.badge-similar')!
    expect(badge.textContent).toBe('비슷한 글')
    expect(badge.getAttribute('title')).toContain('내용이 비슷해')
    const exact = await render(card({ snippetHtml: '<mark>롤백</mark> 전략', similar: false }))
    expect(exact.querySelector('.badge-similar')).toBeNull()
  })
})

describe('PostCard 태그·조회·댓글 (068)', () => {
  it('태그는 3개까지, 넘치면 …에 나머지를 툴팁으로', async () => {
    const few = await render(card({ tags: ['spring', 'jpa'] }))
    expect([...few.querySelectorAll('.card-tag')].map((a) => a.textContent)).toEqual(['#spring', '#jpa'])
    expect(few.querySelector('.card-tag-more')).toBeNull()
    const many = await render(card({ tags: ['a', 'b', 'c', 'd', 'e'] }))
    expect(many.querySelectorAll('.card-tag')).toHaveLength(3)
    expect(many.querySelector('.card-tag')?.getAttribute('href')).toBe('/tags/a')
    const more = many.querySelector('.card-tag-more')!
    expect(more.textContent).toBe('…')
    expect(more.getAttribute('data-tip')).toBe('#d #e')
    const none = await render(card())
    expect(none.querySelector('.card-tags')).toBeNull()
  })

  it('조회·댓글·좋아요는 99를 넘으면 99+, 정확한 수는 툴팁으로', async () => {
    const el = await render(card({ viewCount: 12950, commentCount: 100, likeCount: 99 }))
    const shown = (cls: string) => el.querySelector(`${cls} [aria-hidden="true"]`)?.textContent
    expect(shown('.card-view')).toBe('99+')
    expect(shown('.card-comment')).toBe('99+')
    expect(shown('.card-like')).toBe('99')
    expect(el.querySelector('.card-view')?.getAttribute('data-tip')).toBe('조회 12,950회')
    expect(el.querySelector('.card-comment .sr-only')?.textContent).toBe('댓글 100개')
  })
})
