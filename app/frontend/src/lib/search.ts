import { api } from './api'
import type { Card, CardAuthor } from './types'
import { t } from './i18n'

// 검색 (spec 014, docs/33). 검색어 규칙은 서버 SearchTerms가 정하고 화면은 안내만 한다.

export type SearchTab = 'posts' | 'people'
export type SearchSort = 'relevance' | 'latest'
export type SearchNotice = 'TWO_CHAR_TITLE_TAG_ONLY' | 'TOO_SHORT' | null

/** 글 결과: 목록 카드와 같은 모양에 미리보기 자리만 검색어 주변 문장(snippetHtml, mark 외 태그 없음)이다. */
export interface SearchHit extends Omit<Card, 'excerpt' | 'publishedAt' | 'visibility' | 'firstPublicAt'> {
  firstPublicAt: string
  snippetHtml: string | null
  similar: boolean
}

export interface SearchPostPage {
  query: string
  items: SearchHit[]
  nextCursor: string | null
  notice: SearchNotice
}

export interface Person {
  id: number
  handle: string
  nickname: string
  bioFirstLine: string | null
  profileImageUrl: string | null
}

export interface PeoplePage {
  query: string
  items: Person[]
  notice: SearchNotice
}

export const SEARCH_MAX_LENGTH = 50

const WARM_EVERY_MS = 5 * 60_000
let warmedAt = -WARM_EVERY_MS

/**
 * 검색창에 들어가면 서버에 임베딩 모델을 미리 올려 달라고 한다. 운영 서버는 쓰지 않을 때 모델을 내려 두므로,
 * 검색어를 치는 동안 올려 두면 첫 검색도 의미 검색까지 제때 끝난다. 결과는 기다리지 않고, 실패해도 검색에는 상관없다.
 * @return 이번에 보냈으면 true (5분 안에 다시 들어가면 보내지 않는다)
 */
export function warmSearch(now = Date.now()): boolean {
  if (now - warmedAt < WARM_EVERY_MS) return false
  warmedAt = now
  void fetch('/api/search/warm', { credentials: 'same-origin' }).catch(() => undefined)
  return true
}

export const NOTICE_TEXT: Record<Exclude<SearchNotice, null>, string> = {
  TWO_CHAR_TITLE_TAG_ONLY: t('두 글자 단어는 제목·태그에서만 찾았어요'),
  TOO_SHORT: t('두 글자 이상 입력해 주세요'),
}

export function searchPath(q: string, tab: SearchTab = 'posts', sort: SearchSort = 'relevance'): string {
  const params = new URLSearchParams({ q: q.trim() })
  if (tab !== 'posts') params.set('tab', tab)
  if (sort !== 'relevance') params.set('sort', sort)
  return `/search?${params.toString().replace(/\+/g, '%20')}`
}

export function postsEndpoint(q: string, sort: SearchSort, blog?: string): string {
  const params = new URLSearchParams({ q })
  if (sort === 'latest') params.set('sort', 'latest')
  if (blog) params.set('blog', blog)
  return `/api/search/posts?${params.toString().replace(/\+/g, '%20')}`
}

export function emptyMessage(q: string): string {
  return t('\'{0}\'에 대한 글이 없어요', { 0: q })
}

/** 검색어에 2글자 단어가 있는지 (안내를 미리 보이려고). 1글자는 무시되므로 세지 않는다. */
export function hasShortWord(q: string): boolean {
  return q.normalize('NFC').trim().split(/\s+/).some((w) => [...w].length === 2)
}

export function parseSort(raw: string | null): SearchSort {
  return raw === 'latest' ? 'latest' : 'relevance'
}

export const searchApi = {
  people: (q: string) => api<PeoplePage>(`/api/search/people?q=${encodeURIComponent(q)}`),
}

export type SearchAuthor = CardAuthor
