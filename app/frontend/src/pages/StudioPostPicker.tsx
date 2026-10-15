// 쇼츠 새 대본의 바탕 글 고르기 (#308). 최신순 목록을 끝까지 내리면 다음 글을 더 불러오고,
// 검색 칸에 제목 일부를 적으면 내 공개 글 전체에서 찾는다(/api/me/posts?q=).
import { useEffect, useRef, useState } from 'react'
import { api } from '../lib/api'
import { t } from '../lib/i18n'
import type { ManagePage } from '../lib/types'

export interface PickedPost { id: number; title: string }

/** 검색어를 다 친 뒤에 한 번만 부르도록 기다리는 시간(ms) */
const DEBOUNCE_MS = 250

const pageUrl = (q: string, cursor: string | null) => {
  const params = new URLSearchParams({ tab: 'published', visibility: 'public' })
  if (q) params.set('q', q)
  if (cursor) params.set('cursor', cursor)
  return `/api/me/posts?${params}`
}

export function StudioPostPicker({ value, onChange }: { value: number | null; onChange: (post: PickedPost | null) => void }) {
  const [query, setQuery] = useState('')
  const [posts, setPosts] = useState<PickedPost[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  // 늦게 온 응답이 새 검색 결과를 덮지 않게 요청마다 번호를 매긴다
  const request = useRef(0)
  const list = useRef<HTMLDivElement>(null)

  const load = (q: string, after: string | null) => {
    const id = ++request.current
    setLoading(true)
    setFailed(false)
    api<ManagePage>(pageUrl(q, after))
      .then((page) => {
        if (id !== request.current) return
        // 운영 정책으로 숨겨진 글은 시청자가 열 수 없어 고르지 않는다
        const more = page.items.filter((p) => !p.hidden).map((p) => ({ id: p.id, title: p.title }))
        setPosts((prev) => (after ? [...prev, ...more] : more))
        setCursor(page.nextCursor)
      })
      .catch(() => { if (id === request.current) setFailed(true) })
      .finally(() => { if (id === request.current) setLoading(false) })
  }

  useEffect(() => {
    const q = query.trim()
    const timer = window.setTimeout(() => {
      list.current?.scrollTo?.({ top: 0 })
      load(q, null)
    }, q ? DEBOUNCE_MS : 0)
    return () => window.clearTimeout(timer)
  }, [query])

  // 검색어가 바뀌면 기다리는 동안 이전 응답·커서가 남지 않게 바로 비운다
  const changeQuery = (next: string) => {
    request.current++
    setQuery(next)
    setPosts([])
    setCursor(null)
  }

  // 목록 끝 가까이 내리면 다음 쪽을 부른다
  const onScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget
    if (cursor && !loading && el.scrollTop + el.clientHeight >= el.scrollHeight - 48) load(query.trim(), cursor)
  }

  return (
    <div className="post-picker">
      <input id="shorts-post" type="search" value={query} placeholder={t('제목으로 내 글 찾기')} aria-label={t('바탕이 될 글 검색')}
        aria-controls="shorts-post-list" onChange={(e) => changeQuery(e.target.value)} />
      <div id="shorts-post-list" ref={list} className="post-picker-list" role="radiogroup" aria-label={t('바탕이 될 글')} aria-busy={loading} onScroll={onScroll}>
        <label className={`post-picker-item${value === null ? ' picked' : ''}`}>
          <input type="radio" name="shorts-post" checked={value === null} onChange={() => onChange(null)} />
          <span>{t('글 없이 쓰기')}</span>
        </label>
        {posts.map((p) => (
          <label key={p.id} className={`post-picker-item${value === p.id ? ' picked' : ''}`}>
            <input type="radio" name="shorts-post" checked={value === p.id} onChange={() => onChange(p)} />
            <span>{p.title || t('(제목 없음)')}</span>
          </label>
        ))}
        {!loading && !failed && posts.length === 0 && (
          <p className="post-picker-note muted small">{query.trim() ? t('"{0}"이 들어간 공개 글이 없어요.', { 0: query.trim() }) : t('아직 공개한 글이 없어요.')}</p>
        )}
        {failed && (
          <p className="post-picker-note small">
            {t('글 목록을 불러오지 못했어요.')}{' '}
            <button type="button" className="btn btn-text btn-small" onClick={() => load(query.trim(), posts.length ? cursor : null)}>{t('다시 불러오기')}</button>
          </p>
        )}
        {cursor && !failed && (
          <button type="button" className="btn btn-text btn-small post-picker-more" disabled={loading} onClick={() => load(query.trim(), cursor)}>
            {loading ? t('불러오는 중…') : t('글 더 보기')}
          </button>
        )}
      </div>
    </div>
  )
}
