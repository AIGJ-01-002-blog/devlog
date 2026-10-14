import { beforeEach, describe, expect, it } from 'vitest'
import { clearReads, forgetRead, recentReads, rememberRead, RECENT_KEEP } from './recentReads'
import { forgetSearch, recentSearches, rememberSearch, SEARCH_KEEP } from './recentSearches'

const post = (id: number) => ({ id, url: `/@a/posts/${id}`, title: `글 ${id}`, author: '가', thumbnailUrl: null })

describe('최근 읽은 글·검색어 (097)', () => {
  beforeEach(() => localStorage.clear())

  it('최근 연 글이 맨 앞이고 같은 글은 한 번만, 50개까지 남긴다', () => {
    for (let i = 1; i <= RECENT_KEEP + 3; i++) rememberRead(post(i))
    rememberRead(post(10))
    const list = recentReads()
    expect(list).toHaveLength(RECENT_KEEP)
    expect(list[0].id).toBe(10)
    expect(list.filter((r) => r.id === 10)).toHaveLength(1)
    forgetRead(10)
    expect(recentReads().some((r) => r.id === 10)).toBe(false)
    clearReads()
    expect(recentReads()).toEqual([])
  })

  it('망가진 기록은 빈 목록으로 본다', () => {
    localStorage.setItem('devlog:recent-reads', '{oops')
    expect(recentReads()).toEqual([])
  })

  it('검색어는 공백을 다듬어 8개까지, 지우면 빠진다', () => {
    for (let i = 1; i <= SEARCH_KEEP + 2; i++) rememberSearch(` q${i} `)
    rememberSearch('   ')
    expect(recentSearches()).toHaveLength(SEARCH_KEEP)
    expect(recentSearches()[0]).toBe(`q${SEARCH_KEEP + 2}`)
    forgetSearch(`q${SEARCH_KEEP + 2}`)
    expect(recentSearches()[0]).toBe(`q${SEARCH_KEEP + 1}`)
  })
})
