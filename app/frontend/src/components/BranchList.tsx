import type { CSSProperties } from 'react'
import { branchLabel, branchLanes, dayLabel, type GraphRow } from '../lib/branchLanes'
import { fullDate, relativeDate } from '../lib/format'
import { Link } from '../lib/router'
import type { Branch, Card } from '../lib/types'
import { Avatar } from './Avatar'
import { CardStats, CardTags } from './PostCard'
import { t } from '../lib/i18n'

/**
 * 홈 최신 글의 브랜치 그래프 목록 (072). 왼쪽 그래프 칸에 main과 브랜치 줄을 그리고, 오른쪽에 글을 한 줄씩 보인다.
 * graph=false면 그래프 없이 같은 모양의 목록만 그린다(트렌딩: 순위라 시간 흐름이 없다).
 * 그래프는 장식이라 화면 읽기 프로그램에는 숨기고, 브랜치는 이름표 글자로 알린다.
 */
export function BranchList({ items, hasMore, graph = true }: { items: Card[]; hasMore: boolean; graph?: boolean }) {
  const rows = branchLanes(items, hasMore)
  const lanes = graph ? Math.max(0, ...rows.flatMap((r) => [r.lane, ...r.segments.map((s) => s.lane)])) : 0
  const style = { '--lanes': lanes } as CSSProperties
  let prevDay = ''
  return (
    <ol className={`branch-list${graph ? '' : ' branch-list-plain'}`} style={style}>
      {rows.flatMap((row, i) => {
        const at = row.card.firstPublicAt ?? row.card.publishedAt
        const day = graph ? dayLabel(at) : ''
        const out = []
        if (graph && day !== prevDay) {
          out.push(
            <li key={`d${row.card.id}`} className="bl-day" aria-hidden="true">
              <span className="bl-graph">
                {i > 0 && <span className="bl-line bl-full" style={laneStyle(0)} />}
                {row.above.map((a) => <span key={a.lane} className={`bl-line bl-full bl-${a.kind.toLowerCase()}`} style={laneStyle(a.lane)} />)}
              </span>
              <span className="bl-day-label">{day}</span>
            </li>,
          )
        }
        prevDay = day
        out.push(<BranchRow key={row.card.id} row={row} graph={graph} />)
        return out
      })}
    </ol>
  )
}

function laneStyle(lane: number): CSSProperties {
  return { '--lane': lane } as CSSProperties
}

function BranchRow({ row, graph }: { row: GraphRow; graph: boolean }) {
  const { card } = row
  const b = card.branch && card.branch.total > 1 ? card.branch : null
  const at = card.firstPublicAt ?? card.publishedAt
  return (
    <li className="bl-row">
      {graph && (
        <span className="bl-graph" aria-hidden="true">
          {row.mainTop && <span className="bl-line bl-top" style={laneStyle(0)} />}
          {row.mainBottom && <span className="bl-line bl-bottom" style={laneStyle(0)} />}
          {row.segments.map((s) => {
            const kind = `bl-${s.kind.toLowerCase()}`
            return (
              <span key={s.lane}>
                {s.top && <span className={`bl-line bl-top ${kind}`} style={laneStyle(s.lane)} />}
                {s.bottom && <span className={`bl-line bl-bottom ${kind}`} style={laneStyle(s.lane)} />}
                {s.fork && <span className={`bl-fork ${kind}`} style={laneStyle(s.lane)} />}
              </span>
            )
          })}
          <span className={`bl-dot bl-dot-${row.dot.toLowerCase()}`} style={laneStyle(row.lane)} />
        </span>
      )}
      <article className="bl-body">
        <div className="bl-text">
          {b && (
            <Link to={b.url} className={`bl-branch bl-branch-${b.kind.toLowerCase()}`}
                  data-tip={b.kind === 'SERIES' ? t('이 시리즈 글 모두 보기') : t('태그·내용이 비슷해 자동으로 묶인 글만 보기')}>
              <BranchMark kind={b.kind} />{branchLabel(b)}
            </Link>
          )}
          <h2 className="bl-title"><Link to={card.url}>{card.title}</Link></h2>
          {card.excerpt && <p className="bl-excerpt">{card.excerpt}</p>}
          <CardTags tags={card.tags ?? []} />
          <div className="bl-meta">
            <Link to={`/@${card.author.handle}`} className="bl-author">
              <Avatar src={card.author.profileImageUrl} name={card.author.nickname} seed={card.author.handle} size={20} />
              <span>{card.author.nickname}</span>
            </Link>
            <span aria-hidden="true">·</span>
            <time dateTime={at} title={fullDate(at)}>{relativeDate(at)}</time>
            <CardStats card={card} />
          </div>
        </div>
        {card.thumbnailUrl && (
          <Link to={card.url} className="bl-thumb" tabIndex={-1} aria-hidden="true" aria-label={card.title}>
            <img src={card.thumbnailUrl} alt="" width={640} height={360} loading="lazy" />
          </Link>
        )}
      </article>
    </li>
  )
}

/** 이름표 앞 작은 갈래 모양. 시리즈는 실선, 주제는 점선 */
export function BranchMark({ kind }: { kind: Branch['kind'] }) {
  return (
    <svg className="bl-mark" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
      <circle cx="3" cy="2.5" r="1.6" fill="currentColor" />
      <circle cx="9" cy="9.5" r="1.6" fill="currentColor" />
      <path d="M3 4v2.5c0 1.5 1 3 4.5 3" fill="none" stroke="currentColor" strokeWidth="1.4"
            strokeDasharray={kind === 'TOPIC' ? '1.6 1.4' : undefined} />
    </svg>
  )
}
