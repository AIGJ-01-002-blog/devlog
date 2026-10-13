import { describe, expect, it, vi } from 'vitest'
import { emptyMessage, hasShortWord, parseSort, postsEndpoint, searchPath, warmSearch } from './search'

describe('search', () => {
  it('검색 주소는 기본값을 생략하고 공백을 %20으로 쓴다', () => {
    expect(searchPath(' 트랜잭션 정리 ')).toBe('/search?q=%ED%8A%B8%EB%9E%9C%EC%9E%AD%EC%85%98%20%EC%A0%95%EB%A6%AC')
    expect(searchPath('a+b', 'people')).toBe('/search?q=a%2Bb&tab=people')
    expect(searchPath('x', 'posts', 'latest')).toBe('/search?q=x&sort=latest')
  })

  it('API 주소: 최신순·블로그 안 검색', () => {
    expect(postsEndpoint('100%', 'relevance')).toBe('/api/search/posts?q=100%25')
    expect(postsEndpoint('ab', 'latest', 'gi-me')).toBe('/api/search/posts?q=ab&sort=latest&blog=gi-me')
  })

  it('안내 문구', () => {
    expect(emptyMessage('롬복')).toBe("'롬복'에 대한 글이 없어요")
    expect(hasShortWord('롬복 트랜잭션')).toBe(true)
    expect(hasShortWord('트랜잭션 a')).toBe(false)
    expect(parseSort('latest')).toBe('latest')
    expect(parseSort('x')).toBe('relevance')
  })

  it('예열은 5분에 한 번만 보낸다', () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response(null, { status: 204 })))
    vi.stubGlobal('fetch', fetchMock)
    try {
      expect(warmSearch(1_000_000)).toBe(true)
      expect(warmSearch(1_000_000 + 60_000)).toBe(false)
      expect(warmSearch(1_000_000 + 5 * 60_000)).toBe(true)
      expect(fetchMock).toHaveBeenCalledTimes(2)
      expect(fetchMock).toHaveBeenCalledWith('/api/search/warm', { credentials: 'same-origin' })
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
