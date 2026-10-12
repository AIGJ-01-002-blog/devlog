import { api } from './api'
import type { Card } from './types'
import { t } from './i18n'

// 태그 정규화·주소 (docs/22 §2·§5). 서버 TagNormalizer와 같은 순서다. 화면 정규화는 안내용이고 서버가 다시 검사한다.

export const MAX_TAGS = 10
export const MAX_TAG_LENGTH = 30
const INVISIBLE = /[​-‏‪-‮⁠-⁩﻿\p{Cc}]/gu
const ALLOWED = /^[가-힣a-z0-9._+#-]+$/
const HAS_WORD = /[가-힣a-z0-9]/

export function normalizeTag(raw: string): string {
  return raw.normalize('NFKC').replace(INVISIBLE, '').trim()
    .replace(/^#+/, '').trim()
    .toLowerCase() // JS의 toLowerCase는 지역 설정을 타지 않는다
    .replace(/[\s\p{Z}]+/gu, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
}

/** 금칙어를 뺀 형식 검사. 문제가 없으면 null. */
export function tagFormatError(name: string): string | null {
  if (!name || !ALLOWED.test(name) || !HAS_WORD.test(name)) return t('한글·영문·숫자와 - _ . + #만 쓸 수 있어요.')
  if ([...name].length > MAX_TAG_LENGTH) return t('{0}자까지 쓸 수 있어요.', { 0: MAX_TAG_LENGTH })
  return null
}

/** 경로 조각 인코딩: #은 %23, +와 .은 그대로 (서버 UriUtils.encodePathSegment와 같은 모양). */
export function tagPath(name: string): string {
  return '/tags/' + encodeURIComponent(name).replace(/%2B/g, '+')
}

/** 블로그 안 태그 필터 주소. 쿼리에서는 +가 공백으로 읽히므로 그대로 인코딩한다. */
export function blogTagPath(handle: string, name: string): string {
  return `/@${handle}?tag=${encodeURIComponent(name)}`
}

export interface TagCount { name: string; postCount: number }
export interface TagSuggestion extends TagCount { mine: boolean }
export interface TagPageData { name: string; postCount: number | null; items: Card[]; nextCursor: string | null }

/** 태그 둘러보기 정렬 (spec 089): 인기순(공개 글 수)·이름순·최근 사용순 */
export type TagSort = 'popular' | 'name' | 'recent'
export const TAG_SORTS: TagSort[] = ['popular', 'name', 'recent']

export function parseTagSort(raw: string | null | undefined): TagSort {
  return raw === 'name' || raw === 'recent' ? raw : 'popular'
}

/** 태그 한 줄: 공개 글 수와 그 태그의 인기 글 카드(최대 10개) */
export interface TagRowData { name: string; postCount: number; lastUsedAt: string | null; posts: Card[] }
/** total은 첫 쪽에만 온다 */
export interface TagBrowsePage { items: TagRowData[]; nextCursor: string | null; total: number | null }

/** 검색어·정렬을 주소에 남긴다. 기본값(빈 검색어, 인기순)은 빼서 /tags 그대로 둔다 */
export function tagsIndexPath(q: string, sort: TagSort): string {
  const params = new URLSearchParams()
  if (q.trim()) params.set('q', q.trim())
  if (sort !== 'popular') params.set('sort', sort)
  const qs = params.toString()
  return qs ? `/tags?${qs}` : '/tags'
}

export function browseEndpoint(q: string, sort: TagSort, cursor?: string | null): string {
  const params = new URLSearchParams({ sort })
  if (q.trim()) params.set('q', q.trim())
  if (cursor) params.set('cursor', cursor)
  return `/api/tags/browse?${params}`
}

export const tagsApi = {
  top: () => api<TagCount[]>('/api/tags'),
  browse: (q: string, sort: TagSort, cursor?: string | null) => api<TagBrowsePage>(browseEndpoint(q, sort, cursor)),
  suggest: (q: string) => api<TagSuggestion[]>(`/api/tags/suggest?q=${encodeURIComponent(q)}`),
  page: (name: string) => api<TagPageData>(`/api/tags/${encodeURIComponent(name)}/posts`),
  blogTags: (handle: string) => api<TagCount[]>(`/api/members/${encodeURIComponent(handle)}/tags`),
}

/** 칩 목록에 하나 더한다. 정규화한 모양이 이미 있거나 비면 그대로 둔다 (FR-009). */
export function addTag(tags: string[], raw: string): string[] {
  const name = normalizeTag(raw)
  if (!name || tags.includes(name)) return tags
  return [...tags, name]
}

/** 칩 하나를 앞(-1)이나 뒤(+1)로 옮긴다 (Alt + 방향키, 끌기). */
export function moveTag(tags: string[], from: number, to: number): string[] {
  if (from === to || from < 0 || to < 0 || from >= tags.length || to >= tags.length) return tags
  const next = [...tags]
  const [t] = next.splice(from, 1)
  next.splice(to, 0, t)
  return next
}

/** 서버 오류(tags[i])를 칩 번호별 문구로. 'tags'(개수 초과)는 -1에 담는다. */
export function tagErrors(errors: Record<string, string>): Map<number, string> {
  const out = new Map<number, string>()
  for (const [field, message] of Object.entries(errors)) {
    const m = field.match(/^tags\[(\d+)]$/)
    if (m) out.set(Number(m[1]), message)
    else if (field === 'tags') out.set(-1, message)
  }
  return out
}
