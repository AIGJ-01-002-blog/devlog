import { useCallback, useEffect, useRef, useState } from 'react'
import { AdminNav } from '../components/AdminNav'
import { fullDate, relativeDate } from '../lib/format'
import { adminInquiryApi, CATEGORIES, STATUS_HINT, STATUS_LABEL, categoryLabel, type Inquiry, type InquiryCategory } from '../lib/inquiry'
import { Link, useLocation } from '../lib/router'

/** 문의 관리 (054): [처리할 것]은 오래된 순(먼저 온 것부터), [처리함]은 최근 순. 종류로 거른다. */
export function AdminInquiriesPage() {
  const { search } = useLocation()
  const tab = search.get('tab') === 'done' ? 'done' : 'open'
  const category = (CATEGORIES.find((c) => c.code === search.get('category'))?.code ?? null) as InquiryCategory | null
  const [items, setItems] = useState<Inquiry[]>([])
  const [next, setNext] = useState<number | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(false)
  // 탭·종류를 바꾼 뒤 늦게 온 이전 응답은 버린다
  const key = `${tab}:${category}`
  const current = useRef(key)
  current.current = key

  useEffect(() => { document.title = '문의 관리 - devlog' }, [])

  const load = useCallback(async (before: number | null) => {
    setError(false)
    try {
      const page = await adminInquiryApi.list(tab, category, before)
      if (current.current !== key) return
      setItems((prev) => (before != null ? [...prev, ...page.items.filter((r) => !prev.some((p) => p.id === r.id))] : page.items))
      setNext(page.nextBefore)
    } catch {
      if (current.current === key) setError(true)
    } finally {
      if (current.current === key) setLoaded(true)
    }
  }, [tab, category, key])

  useEffect(() => {
    setItems([])
    setLoaded(false)
    void load(null)
  }, [load])

  const href = (t: string, c: InquiryCategory | null) => `/admin/inquiries?tab=${t}${c ? `&category=${c}` : ''}`

  return (
    <main className="container narrow admin">
      <h1 className="page-title">문의 관리</h1>
      <AdminNav />
      <nav className="tabs" aria-label="문의 목록">
        <Link to={href('open', category)} aria-current={tab === 'open' ? 'page' : undefined} title="접수·처리 중인 문의 (오래된 순)">처리할 것</Link>
        <Link to={href('done', category)} aria-current={tab === 'done' ? 'page' : undefined} title="해결·닫힌 문의 (최근 순)">처리함</Link>
      </nav>
      <div className="filters" role="group" aria-label="종류">
        <Link to={href(tab, null)} className={`chip${category ? '' : ' active'}`} aria-current={category ? undefined : 'true'}>전체</Link>
        {CATEGORIES.map((c) => (
          <Link key={c.code} to={href(tab, c.code)} className={`chip${category === c.code ? ' active' : ''}`}
                aria-current={category === c.code ? 'true' : undefined} title={c.hint}>{c.label}</Link>
        ))}
      </div>
      {error && <p className="error" role="alert">목록을 불러오지 못했어요.</p>}
      {loaded && !error && items.length === 0 && (
        <div className="empty"><p>{tab === 'open' ? '처리할 문의가 없어요.' : '처리한 문의가 없어요.'}</p></div>
      )}
      <ul className="admin-cases">
        {items.map((i) => (
          <li key={i.id} className="admin-case">
            <div className="admin-case-main">
              <span className="badge">{categoryLabel(i.category)}</span>
              <span className={`badge status-${i.status.toLowerCase()}`} title={STATUS_HINT[i.status]}>{STATUS_LABEL[i.status]}</span>
              {i.source === 'MCP' && <span className="badge" title="연결한 AI가 report_bug로 보낸 신고">AI 신고</span>}
              <Link to={`/admin/inquiries/${i.id}`} className="admin-case-title">{i.title}</Link>
            </div>
            <div className="muted small">
              #{i.id} · @{i.memberHandle}
              {i.toolName && <> · 도구 {i.toolName}</>}
              {i.fixedVersion && <> · v{i.fixedVersion}</>}
              {' · '}<time dateTime={i.createdAt} title={fullDate(i.createdAt)}>{relativeDate(i.createdAt)}</time>
            </div>
          </li>
        ))}
      </ul>
      {next != null && <div className="more"><button type="button" className="btn btn-outline" onClick={() => load(next)}>더 보기</button></div>}
    </main>
  )
}
