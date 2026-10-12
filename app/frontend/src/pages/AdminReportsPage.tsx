import { useCallback, useEffect, useRef, useState } from 'react'
import { AdminNav } from '../components/AdminNav'
import { fullDate, relativeDate } from '../lib/format'
import { adminApi, reasonSummary, STATUS_LABEL, type CaseRow } from '../lib/moderation'
import { Link, useLocation } from '../lib/router'

/** 신고 관리 (019 US2): 대상별로 묶은 신고. [대기]는 신고 수 많은 순 → 최근 순, [처리됨]은 처리한 순. */
export function AdminReportsPage() {
  const { search } = useLocation()
  const tab = search.get('tab') === 'handled' ? 'handled' : 'pending'
  const [items, setItems] = useState<CaseRow[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  // 탭을 바꾼 뒤 늦게 온 이전 탭의 응답은 버린다
  const current = useRef(tab)
  current.current = tab

  useEffect(() => { document.title = '신고 관리 - devlog' }, [])

  const load = useCallback(async (from: string | null) => {
    setError(false)
    try {
      const page = await adminApi.list(tab, from)
      if (current.current !== tab) return
      setItems((prev) => (from ? [...prev, ...page.items.filter((r) => !prev.some((p) => p.caseId === r.caseId))] : page.items))
      setCursor(page.nextCursor)
    } catch {
      if (current.current === tab) setError(true)
    } finally {
      if (current.current === tab) setLoaded(true)
    }
  }, [tab])

  useEffect(() => {
    setItems([])
    setLoaded(false)
    void load(null)
  }, [load])

  return (
    <main className="container narrow admin">
      <h1 className="page-title">신고 관리</h1>
      <AdminNav />
      <nav className="tabs" aria-label="신고 목록">
        <Link to="/admin/reports" aria-current={tab === 'pending' ? 'page' : undefined}>대기</Link>
        <Link to="/admin/reports?tab=handled" aria-current={tab === 'handled' ? 'page' : undefined}>처리됨</Link>
      </nav>
      {error && <p className="error" role="alert">목록을 불러오지 못했어요.</p>}
      {loaded && !error && items.length === 0 && (
        <div className="empty"><p>{tab === 'pending' ? '처리할 신고가 없어요.' : '처리한 신고가 없어요.'}</p></div>
      )}
      <ul className="admin-cases">
        {items.map((r) => (
          <li key={r.caseId} className="admin-case">
            <div className="admin-case-main">
              <span className="badge">{r.targetType === 'POST' ? '글' : '댓글'}</span>
              {tab === 'handled' && <span className={`badge${r.status === 'HIDDEN' ? ' badge-warn' : ''}`}>{STATUS_LABEL[r.status]}</span>}
              <Link to={`/admin/reports/${r.caseId}`} className="admin-case-title">{r.preview || '(내용 없음)'}</Link>
            </div>
            <div className="muted small">
              @{r.authorHandle} · 신고 {r.reportCount}건 · {reasonSummary(r.reasons)} ·{' '}
              {tab === 'pending'
                ? <time dateTime={r.latestAt} title={fullDate(r.latestAt)}>최근 {relativeDate(r.latestAt)}</time>
                : r.handledAt && <time dateTime={r.handledAt} title={fullDate(r.handledAt)}>처리 {relativeDate(r.handledAt)}</time>}
              {tab === 'handled' && r.status === 'HIDDEN' && !r.hiddenNow && ' · 숨김 해제됨'}
            </div>
          </li>
        ))}
      </ul>
      {cursor && <div className="more"><button type="button" className="btn btn-outline" onClick={() => load(cursor)}>더 보기</button></div>}
    </main>
  )
}
