import { useEffect, useMemo, useState } from 'react'
import { LogoCover, PostCard } from '../components/PostCard'
import { ProjectEditor } from '../components/ProjectEditor'
import { ApiError } from '../lib/api'
import { loginPath, useAuth } from '../lib/auth'
import { readPosts, seriesProgress } from '../lib/branch'
import { move } from '../lib/files'
import { fullDate, relativeDate } from '../lib/format'
import { Link, navigate } from '../lib/router'
import { SERIES_NAME_MAX, seriesApi, seriesNameError, seriesPath, type SeriesDetail } from '../lib/series'
import type { Card } from '../lib/types'
import { NotFoundPage } from './NotFoundPage'
import { t } from '../lib/i18n'

/** 시리즈 페이지 /@블로그/series/이름 (024 US2-2·US3). 주인은 순서 바꾸기·빼기·이름 바꾸기·삭제를 한다. */
export function SeriesPage({ handle, slug }: { handle: string; slug: string }) {
  const [series, setSeries] = useState<SeriesDetail | null>(null)
  const [missing, setMissing] = useState(false)
  const [editing, setEditing] = useState<Card[] | null>(null)
  const [renaming, setRenaming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const read = useMemo(() => readPosts(), [handle, slug])
  const { me } = useAuth()

  useEffect(() => {
    let alive = true
    seriesApi.detail(handle, slug)
      .then((s) => { if (alive) { setSeries(s); document.title = t('{0} - 시리즈', { 0: s.name }) } })
      .catch((e) => { if (alive && e instanceof ApiError && e.status === 404) setMissing(true) })
    return () => { alive = false }
  }, [handle, slug])

  if (missing) return <NotFoundPage />
  if (!series) return <main className="container narrow"><p className="muted center">{t('불러오는 중…')}</p></main>

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    try {
      await fn()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('저장하지 못했어요. 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }

  const saveOrder = (posts: Card[]) => run(async () => {
    await seriesApi.reorder(series.id, posts.map((p) => p.id))
    setSeries({ ...series, posts, updatedAt: new Date().toISOString() })
    setEditing(null)
  })

  const rename = (name: string) => {
    const invalid = seriesNameError(name)
    if (invalid) return setError(invalid)
    void run(async () => {
      const r = await seriesApi.rename(series.id, name)
      setRenaming(null)
      if (r.slug !== series.slug) navigate(seriesPath(handle, r.slug), { replace: true })
      else setSeries({ ...series, name: r.name })
    })
  }

  const remove = () => {
    if (!confirm(t('\'{0}\' 시리즈를 지울까요? 글은 지워지지 않아요.', { 0: series.name }))) return
    void run(async () => {
      await seriesApi.remove(series.id)
      navigate(`/@${handle}/series`, { replace: true })
    })
  }

  const subscribe = (on: boolean) => run(async () => {
    await seriesApi.subscribe(series.id, on)
    setSeries({ ...series, subscribed: on })
  })
  const progress = seriesProgress(series.posts, read)

  return (
    <main className="container narrow series-page">
      <p className="muted small"><Link to={`/@${handle}/series`}>{t('@{0}의 시리즈', { 0: handle })}</Link></p>
      {renaming != null ? (
        <>
        {/* 이름을 고치는 동안에도 쪽 제목은 남겨 둔다(화면 읽기 프로그램의 제목 이동) */}
        <h1 className="sr-only">{series.name}</h1>
        <form className="row series-rename" onSubmit={(e) => { e.preventDefault(); rename(renaming) }}>
          <input aria-label={t('시리즈 이름')} value={renaming} maxLength={SERIES_NAME_MAX} autoFocus onChange={(e) => setRenaming(e.target.value)} />
          <button className="btn btn-primary" disabled={busy}>{t('저장')}</button>
          <button type="button" className="btn btn-text" onClick={() => { setRenaming(null); setError(null) }}>{t('취소')}</button>
        </form>
        </>
      ) : <h1>{series.name}</h1>}
      <p className="muted small">
        
        {t('글 {0}개', { 0: series.posts.length })} · <time dateTime={series.updatedAt} title={fullDate(series.updatedAt)}>{relativeDate(series.updatedAt)}  {t('수정')}</time>
      </p>
      {!series.mine && series.posts.length > 0 && !editing && (
        <section className="series-progress" aria-label={t('이어 읽기')}>
          <div className="series-progress-row">
            <span>{t('{0}편 중', { 0: series.posts.length })} <b>{t('{0}편 읽음', { 0: progress.done })}</b></span>
            <span className="muted small">{t('이 기기에서 연 글 기준')}</span>
          </div>
          <div className="series-progress-bar" role="progressbar" aria-label={t('읽은 편 수')} aria-valuemin={0}
               aria-valuemax={series.posts.length} aria-valuenow={progress.done}>
            <span style={{ width: `${(progress.done / series.posts.length) * 100}%` }} />
          </div>
          <div className="row series-progress-actions">
            {progress.next
              ? <Link to={progress.next.url} className="btn btn-primary" data-tip={progress.next.title}>
                  {progress.done === 0 ? t('1편부터 읽기') : t('{0}편 이어 읽기', { 0: series.posts.indexOf(progress.next) + 1 })}
                </Link>
              : <span className="muted small">{t('모두 읽었어요')}</span>}
            {me?.authenticated
              ? <button type="button" className="btn btn-outline" disabled={busy}
                        aria-pressed={!!series.subscribed} onClick={() => void subscribe(!series.subscribed)}
                        data-tip={series.subscribed ? t('이 시리즈 새 글 알림을 그만 받아요') : t('이 시리즈에 새 글이 올라오면 알려 드려요')}>
                  {series.subscribed ? t('새 글 알림 받는 중') : t('새 글 알림 받기')}
                </button>
              : <Link to={loginPath(seriesPath(handle, slug))} className="btn btn-outline" data-tip={t('로그인하면 새 글 알림을 받을 수 있어요')}>{t('새 글 알림 받기')}</Link>}
          </div>
        </section>
      )}
      {series.mine && !editing && renaming == null && (
        <div className="row series-owner">
          <button type="button" className="btn btn-text" onClick={() => setEditing(series.posts)} disabled={series.posts.length === 0}>{t('순서 편집')}</button>
          <button type="button" className="btn btn-text" onClick={() => setRenaming(series.name)}>{t('이름 바꾸기')}</button>
          <button type="button" className="btn btn-text danger" onClick={remove} disabled={busy}>{t('시리즈 삭제')}</button>
        </div>
      )}
      {series.mine && !editing && renaming == null && <ProjectEditor seriesId={series.id} handle={handle} />}
      {error && <p className="error" role="alert">{error}</p>}
      {editing ? (
        <>
          <ol className="series-edit">
            {editing.map((p, i) => (
              <li key={p.id} className="row">
                <span className="grow">{p.title}</span>
                <button type="button" className="btn btn-text" aria-label={t('{0} 위로', { 0: p.title })} disabled={i === 0}
                        onClick={() => setEditing(move(editing, i, -1))}>↑</button>
                <button type="button" className="btn btn-text" aria-label={t('{0} 아래로', { 0: p.title })} disabled={i === editing.length - 1}
                        onClick={() => setEditing(move(editing, i, 1))}>↓</button>
                <button type="button" className="btn btn-text danger" aria-label={t('{0} 시리즈에서 빼기', { 0: p.title })}
                        onClick={() => setEditing(editing.filter((x) => x.id !== p.id))}>{t('빼기')}</button>
              </li>
            ))}
          </ol>
          <div className="row">
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => saveOrder(editing)}>{t('저장')}</button>
            <button type="button" className="btn btn-text" onClick={() => { setEditing(null); setError(null) }}>{t('취소')}</button>
          </div>
        </>
      ) : series.posts.length === 0 ? (
        <p className="muted center">{t('아직 이 시리즈에 발행한 글이 없어요. 글쓰기 화면의 [시리즈]에서 넣을 수 있어요.')}</p>
      ) : (
        <ol className="series-posts">
          {series.posts.map((p, i) => (
            <li key={p.id} className={read.has(p.id) ? 'is-read' : undefined}>
              <span className="series-no" aria-hidden="true">{read.has(p.id) ? '✓' : `${i + 1}.`}</span>
              {read.has(p.id) && <span className="sr-only">{t('{0}편, 읽음', { 0: i + 1 })}</span>}
              <PostCard card={p} showAuthor={false} />
            </li>
          ))}
        </ol>
      )}
    </main>
  )
}

/** 블로그의 [시리즈] 탭 (024 US2-2). 남에게는 읽을 수 있는 글이 있는 시리즈만 온다. */
export function BlogSeries({ handle, mine }: { handle: string; mine: boolean }) {
  const [list, setList] = useState<Awaited<ReturnType<typeof seriesApi.list>> | null>(null)
  useEffect(() => {
    let alive = true
    seriesApi.list(handle).then((l) => { if (alive) setList(l) }).catch(() => { if (alive) setList([]) })
    return () => { alive = false }
  }, [handle])
  if (!list) return <p className="muted center">{t('불러오는 중…')}</p>
  if (list.length === 0) {
    return <p className="muted center">{mine ? t('아직 시리즈가 없어요. 글쓰기 화면의 [시리즈]에서 만들 수 있어요.') : t('아직 시리즈가 없어요.')}</p>
  }
  return (
    <div className="card-grid">
      {list.map((s) => (
        <article key={s.id} className="card">
          <Link to={seriesPath(handle, s.slug)} className="card-thumb" tabIndex={-1} aria-hidden="true">
            {s.thumbnailUrl ? <img src={s.thumbnailUrl} alt="" loading="lazy" /> : <LogoCover />}
          </Link>
          <div className="card-body">
            <h2 className="card-title"><Link to={seriesPath(handle, s.slug)}>{s.name}</Link></h2>
            <div className="card-meta">
              
              {t('글 {0}개', { 0: s.postCount })} · <time dateTime={s.updatedAt} title={fullDate(s.updatedAt)}>{relativeDate(s.updatedAt)}  {t('수정')}</time>
            </div>
          </div>
        </article>
      ))}
    </div>
  )
}
