import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { loginPath, useAuth } from '../lib/auth'
import { fullDate, relativeDate } from '../lib/format'
import {
  BUG_TEMPLATE, CATEGORIES, CONTENT_MAX, TITLE_MAX, STATUS_HINT, STATUS_LABEL, categoryLabel, inquiryApi, inquiryErrorText,
  releaseLink, reportedPage, type Inquiry, type InquiryCategory,
} from '../lib/inquiry'
import { Link, navigate, useLocation } from '../lib/router'
import { t, tNodes } from '../lib/i18n'
import { api } from '../lib/api'
import { GuestContact, type About } from './AboutPage'

/**
 * 문의·신고 (spec 054). 회원이 문의·버그·제안·신고를 남기고, 내가 남긴 것의 처리 상태·답변·고친 버전을 본다.
 * 연결한 AI가 report_bug로 남긴 신고도 같은 목록에 보인다. ?from=은 메뉴를 연 화면 주소(버그가 난 곳), ?id=는 알림에서 온 문의.
 */
export function SupportPage() {
  const { me, loading } = useAuth()
  const { search } = useLocation()
  const focusId = Number(search.get('id')) || null
  const tab = focusId || search.get('tab') === 'mine' ? 'mine' : 'new'

  useEffect(() => { document.title = t('문의·신고 - devlog') }, [])

  if (loading) return <main className="container narrow"><p className="muted center">{t('불러오는 중…')}</p></main>
  return (
    <main className="container narrow support">
      <h1 className="page-title">{t('문의·신고')}</h1>
      <p className="muted support-lead">{t('궁금한 점, 버그, 제안을 남겨 주세요. 운영자만 읽고, 답변이 오면 알림으로 알려 드려요.')}</p>
      {!me?.authenticated ? (
        <div className="support-card">
          <p>{t('문의는 로그인한 뒤 남길 수 있어요. 답변을 알림으로 보내 드리기 위해서예요.')}</p>
          <Link to={loginPath()} className="btn btn-dark">{t('로그인')}</Link>
          <GuestSupportContact />
        </div>
      ) : (
        <>
          <nav className="tabs" aria-label={t('문의')}>
            <Link to="/support" aria-current={tab === 'new' ? 'page' : undefined}>{t('새로 남기기')}</Link>
            <Link to="/support?tab=mine" aria-current={tab === 'mine' ? 'page' : undefined}>{t('내 문의')}</Link>
          </nav>
          {tab === 'new' ? <SupportForm from={reportedPage(search.get('from'))} /> : <MyInquiries focusId={focusId} />}
        </>
      )}
      <p className="muted small support-foot">
        
        {tNodes('devlog가 버전마다 무엇을 고쳤는지는 {0}에서 볼 수 있어요.', { 0: <Link to="/releases">{t('릴리스 노트')}</Link> })}
      </p>
    </main>
  )
}

function SupportForm({ from }: { from: string | null }) {
  const [category, setCategory] = useState<InquiryCategory | null>(from ? 'BUG' : null)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState(from ? BUG_TEMPLATE : '')
  const [page, setPage] = useState<string | null>(from)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const groupId = useId()

  const pick = (c: InquiryCategory) => {
    setCategory(c)
    setError(null)
    // 버그를 고르면 빈 본문에 틀을 채운다. 이미 쓴 내용은 건드리지 않는다
    if (c === 'BUG' && !content.trim()) setContent(BUG_TEMPLATE)
    if (c !== 'BUG' && content === BUG_TEMPLATE) setContent('')
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!category) return setError(t('종류를 골라 주세요.'))
    if (!title.trim()) return setError(t('제목을 적어 주세요.'))
    if (!content.trim() || content === BUG_TEMPLATE) return setError(t('내용을 적어 주세요.'))
    setBusy(true)
    setError(null)
    try {
      const { id } = await inquiryApi.submit(category, title.trim(), content, page)
      navigate(`/support?id=${id}&sent=1`)
    } catch (err) {
      setError(inquiryErrorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="support-form" onSubmit={submit} noValidate>
      <fieldset className="field">
        <legend id={groupId}>{t('종류')}</legend>
        <div className="support-categories" role="radiogroup" aria-labelledby={groupId}>
          {CATEGORIES.map((c) => (
            <label key={c.code} className={`support-category${category === c.code ? ' selected' : ''}`} title={c.hint}>
              <input type="radio" name="category" value={c.code} checked={category === c.code} onChange={() => pick(c.code)} />
              <b>{c.label}</b>
              <span className="muted small">{c.hint}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <label className="field">
        <span>{tNodes('제목 {0}', { 0: <span className="muted small">({[...title].length}/{TITLE_MAX})</span> })}</span>
        <input value={title} maxLength={TITLE_MAX} onChange={(e) => setTitle(e.target.value)} placeholder={t('한 줄로 알려 주세요')} />
      </label>
      <label className="field">
        <span>{tNodes('내용 {0}', { 0: <span className="muted small">(Markdown, {[...content].length.toLocaleString()}/{CONTENT_MAX.toLocaleString()})</span> })}</span>
        <textarea value={content} maxLength={CONTENT_MAX} rows={10} onChange={(e) => setContent(e.target.value)}
                  placeholder={t('어떤 상황인지 자세히 적어 주실수록 빨리 도와드릴 수 있어요')} />
      </label>
      {page && (
        <p className="support-page small">
          <span className="muted">{t('문제가 난 화면:')}</span> <code>{page}</code>
          <button type="button" className="btn btn-text" onClick={() => setPage(null)} aria-label={t('화면 주소 빼기')}
                  title={t('이 화면 주소를 함께 보내지 않아요')}>{t('빼기')}</button>
        </p>
      )}
      <p className="muted small">{t('비밀번호·토큰 같은 비밀 정보는 적지 마세요.')}</p>
      {error && <p className="error small" role="alert">{error}</p>}
      <div className="support-actions">
        <button type="submit" className="btn btn-primary" disabled={busy} title={t('운영자에게 보내요. 답변이 오면 알림으로 알려 드려요')}>
          {busy ? t('보내는 중…') : t('보내기')}
        </button>
      </div>
      <aside className="support-card support-ai">
        <b>{t('AI로 버그를 신고할 수도 있어요')}</b>
        <p className="small">
          
          {tNodes('devlog를 {0}했다면 AI에게 “devlog 버그 신고해 줘”라고 말해 보세요. AI가 어떤 도구에서 무엇이 잘못됐는지 정리해 {1}로 보내고, 여기 [내 문의]에 함께 보여요.', { 0: <Link to="/mcp">{t('AI 도구에 연결')}</Link>, 1: <code>report_bug</code> })}
        </p>
      </aside>
    </form>
  )
}

function MyInquiries({ focusId }: { focusId: number | null }) {
  const { search } = useLocation()
  const [items, setItems] = useState<Inquiry[] | null>(null)
  const [error, setError] = useState(false)
  const focused = useRef<HTMLDetailsElement>(null)

  useEffect(() => {
    inquiryApi.mine().then(setItems).catch(() => setError(true))
  }, [])

  useEffect(() => {
    focused.current?.scrollIntoView({ block: 'start' })
    focused.current?.querySelector('summary')?.focus()
  }, [items])

  if (error) return <p className="error" role="alert">{t('목록을 불러오지 못했어요.')}</p>
  if (!items) return <p className="muted center">{t('불러오는 중…')}</p>
  return (
    <>
      {search.get('sent') && <p className="banner banner-ok" role="status">{t('보냈어요. 답변이 오면 알림으로 알려 드릴게요.')}</p>}
      {items.length === 0 ? (
        <div className="empty"><p>{t('아직 남긴 문의가 없어요.')}</p><Link to="/support" className="btn btn-outline">{t('문의 남기기')}</Link></div>
      ) : (
        <ul className="support-list">
          {items.map((i) => (
            <li key={i.id}>
              <details className="support-item" open={i.id === focusId} ref={i.id === focusId ? focused : undefined}>
                <summary>
                  <span className="support-item-head">
                    <span className="badge">{categoryLabel(i.category)}</span>
                    <span className={`badge status-${i.status.toLowerCase()}`} title={STATUS_HINT[i.status]}>{STATUS_LABEL[i.status]}</span>
                    {i.source === 'MCP' && <span className="badge" title={t('연결한 AI가 report_bug로 보낸 신고예요')}>{t('AI 신고')}</span>}
                    {i.answer && <span className="badge badge-brand" title={t('운영자 답변이 있어요')}>{t('답변')}</span>}
                  </span>
                  <span className="support-item-title">{i.title}</span>
                  <time className="muted small" dateTime={i.createdAt} title={fullDate(i.createdAt)}>{relativeDate(i.createdAt)}</time>
                </summary>
                <div className="support-item-body">
                  {(i.pageUrl || i.toolName) && (
                    <p className="muted small">
                      {i.pageUrl && <>{tNodes('화면 {0}', { 0: <code>{i.pageUrl}</code> })}</>}
                      {i.toolName && <>{tNodes('도구 {0}', { 0: <code>{i.toolName}</code> })}</>}
                    </p>
                  )}
                  <div className="support-content">{i.content}</div>
                  {i.answer && (
                    <div className="support-answer">
                      <b>{t('운영자 답변')}</b>
                      {i.answeredAt && <time className="muted small" dateTime={i.answeredAt}> · {fullDate(i.answeredAt)}</time>}
                      <div className="support-content">{i.answer}</div>
                    </div>
                  )}
                  {i.fixedVersion && (
                    <p className="small">
                      <Link to={releaseLink(i.fixedVersion)} title={t('이 버전에서 무엇이 바뀌었는지 봐요')}>{tNodes('v{0}에서 고쳤어요 →', { 0: i.fixedVersion })}</Link>
                    </p>
                  )}
                </div>
              </details>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

/** 비회원에게 로그인 말고도 닿는 연락 방법을 함께 보인다 (spec 104 FR-006). 서버 HTML에도 같은 안내가 있다 */
function GuestSupportContact() {
  const [about, setAbout] = useState<About | null>(null)
  useEffect(() => { api<About>('/api/site/about').then(setAbout).catch(() => setAbout(null)) }, [])
  return <GuestContact email={about?.contactEmail ?? null} url={about?.contactUrl ?? null} />
}
