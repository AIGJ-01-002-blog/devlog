import { useCallback, useEffect, useRef, useState } from 'react'
import { cappedCount, relativeDate } from '../lib/format'
import { Link } from '../lib/router'
import { tagPath, type TagRowData } from '../lib/tags'
import type { Card } from '../lib/types'
import { LogoCover } from './PostCard'
import { NavIcon } from './NavIcons'
import { intlTag, t } from '../lib/i18n'

/**
 * 태그 한 줄 (spec 089): 태그 이름·글 수 아래로 그 태그의 인기 글 카드를 가로로 넘겨 본다.
 * 세로 휠은 가로로 바꾸지 않는다(페이지 무한 스크롤을 가로채지 않게). 트랙패드·Shift+휠·밀기·[이전]/[다음]으로 넘긴다.
 * 글이 줄에 실린 것보다 많으면 끝에 [전체 보기] 카드를 단다.
 */
export function TagRow({ row }: { row: TagRowData }) {
  const track = useRef<HTMLUListElement>(null)
  const [edge, setEdge] = useState({ start: true, end: true })
  const more = row.postCount > row.posts.length
  const headingId = `tag-row-${row.name}`

  const measure = useCallback(() => {
    const el = track.current
    if (!el) return
    // 소수점 픽셀 때문에 끝에서 1px 남는 화면이 있다
    setEdge({ start: el.scrollLeft <= 1, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 1 })
  }, [])

  useEffect(() => {
    measure()
    const el = track.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  }, [measure, row.posts.length])

  const page = (dir: 1 | -1) => {
    const el = track.current
    if (!el) return
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    el.scrollBy({ left: dir * el.clientWidth * 0.85, behavior: reduce ? 'auto' : 'smooth' })
  }

  return (
    <section className="tag-row" aria-labelledby={headingId}>
      <header className="tag-row-head">
        <h2 id={headingId} className="tag-row-title">
          <Link to={tagPath(row.name)} data-tip={t('#{0} 태그 글 보기', { 0: row.name })}>#{row.name}</Link>
        </h2>
        <span className="muted tag-row-count">{t('글 {0}편', { 0: row.postCount.toLocaleString(intlTag()) })}</span>
        {row.lastUsedAt && <span className="muted tag-row-recent">· {t('최근 {0}', { 0: relativeDate(row.lastUsedAt) })}</span>}
        <span className="tag-row-nav">
          <button type="button" className="btn btn-icon" aria-label={t('#{0} 이전 글', { 0: row.name })} data-tip={t('이전 글')}
                  disabled={edge.start} onClick={() => page(-1)}>‹</button>
          <button type="button" className="btn btn-icon" aria-label={t('#{0} 다음 글', { 0: row.name })} data-tip={t('다음 글')}
                  disabled={edge.end} onClick={() => page(1)}>›</button>
        </span>
      </header>
      <ul ref={track} className="tag-row-track" onScroll={measure} aria-label={t('#{0} 인기 글', { 0: row.name })}>
        {row.posts.map((c) => <li key={c.id}><MiniCard card={c} /></li>)}
        {more && (
          <li className="tag-row-all-item">
            <Link to={tagPath(row.name)} className="tag-row-all" data-tip={t('#{0} 태그 글 {1}편 모두 보기', { 0: row.name, 1: row.postCount })}>
              <span className="tag-row-all-count">{t('+{0}편', { 0: row.postCount - row.posts.length })}</span>
              <span>{t('전체 보기')} →</span>
            </Link>
          </li>
        )}
      </ul>
    </section>
  )
}

/** 줄 안의 작은 카드: 표지·제목·글쓴이·좋아요만 */
function MiniCard({ card }: { card: Card }) {
  return (
    <article className="mini-card">
      <Link to={card.url} className="mini-card-link">
        <span className="card-thumb mini-card-thumb">
          {card.thumbnailUrl ? <img src={card.thumbnailUrl} alt="" width={320} height={180} loading="lazy" /> : <LogoCover />}
        </span>
        <span className="mini-card-title">{card.title}</span>
      </Link>
      <div className="mini-card-meta muted">
        <Link to={`/@${card.author.handle}`} className="mini-card-author">{card.author.nickname}</Link>
        <span className="mini-card-likes" data-tip={t('좋아요 {0}개', { 0: card.likeCount.toLocaleString(intlTag()) })}>
          <span aria-hidden="true"><NavIcon name="heart" size={12} />{cappedCount(card.likeCount)}</span>
          <span className="sr-only">{t('좋아요 {0}개', { 0: card.likeCount.toLocaleString(intlTag()) })}</span>
        </span>
      </div>
    </article>
  )
}
