import { ReportButton } from './ReportButton'
import { useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { loginPath, useAuth } from '../lib/auth'
import {
  appendUnique, commentLength, commentsApi, countsTowardTotal, insertComment, MAX_COMMENT, removeComment, replaceComment,
  type CommentPage, type CommentView,
} from '../lib/comments'
import { fullDate, relativeDate } from '../lib/format'
import { Link } from '../lib/router'
import { Avatar } from './Avatar'
import { t } from '../lib/i18n'

/**
 * 글 상세 아래 댓글 (docs/21 §3, spec 011). 첫 20개는 글과 함께 오고, [댓글 더 보기]·[답글 N개 더 보기]·[이전 댓글 보기]로
 * 이어 붙인다. 내용은 글자(텍스트 노드)로만 그리고 줄바꿈은 CSS(pre-line)로 살린다.
 * ?comment={id}로 들어오면 그 댓글까지 불러와 스크롤하고 잠깐 강조한다.
 */
export function Comments({ postId, initial, onCount }: {
  postId: number
  initial: CommentPage | null
  onCount: (n: number) => void
}) {
  const target = new URLSearchParams(location.search).get('comment')
  const [page, setPage] = useState<CommentPage | null>(target ? null : initial)
  const [items, setItems] = useState<CommentView[]>(target ? [] : initial?.items ?? [])
  const [error, setError] = useState(false)
  const [loadingMore, setLoadingMore] = useState<'next' | 'prev' | null>(null)
  const [moreFailed, setMoreFailed] = useState<'next' | 'prev' | null>(null)
  const [replyingTo, setReplyingTo] = useState<number | null>(null)
  const [highlight, setHighlight] = useState<number | null>(null)

  const load = () => {
    setError(false)
    commentsApi.page(postId, target ? { around: target } : {})
      .then((p) => {
        setPage(p)
        setItems(p.items)
        onCount(p.commentCount)
        if (target) setHighlight(Number(target))
      })
      .catch(() => setError(true))
  }
  useEffect(() => {
    if (!page) load()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (highlight == null) return
    const el = document.getElementById(`comment-${highlight}`)
    el?.scrollIntoView({ block: 'center' })
    const t = setTimeout(() => setHighlight(null), 2500)
    return () => clearTimeout(t)
  }, [highlight])

  const more = async (dir: 'next' | 'prev') => {
    if (!page) return
    setLoadingMore(dir)
    setMoreFailed(null)
    try {
      const p = await commentsApi.page(postId, dir === 'next' ? { cursor: page.nextCursor! } : { before: page.prevCursor! })
      if (dir === 'next') {
        setItems((list) => appendUnique(list, p.items))
        setPage({ ...page, nextCursor: p.nextCursor })
      } else {
        setItems((list) => appendUnique(p.items, list))
        setPage({ ...page, prevCursor: p.prevCursor })
      }
    } catch {
      setMoreFailed(dir)
    } finally {
      setLoadingMore(null)
    }
  }

  const count = page?.commentCount ?? 0
  const setCount = (n: number) => {
    if (page) setPage({ ...page, commentCount: n })
    onCount(n)
  }
  const added = (c: CommentView, rootId: number | null) => {
    setItems((list) => insertComment(list, c, rootId))
    setCount(count + 1)
    setReplyingTo(null)
    setHighlight(c.id)
  }
  const removed = (c: CommentView) => {
    setItems((list) => removeComment(list, c.id))
    if (countsTowardTotal(c)) setCount(Math.max(0, count - 1))
  }

  if (error) {
    return <section className="comments"><p className="feed-error" role="alert">{t('댓글을 불러오지 못했어요')} <button type="button" className="btn btn-text" onClick={load}>{t('다시 시도')}</button></p></section>
  }
  if (!page) return <section className="comments"><p className="muted">{t('댓글을 불러오는 중…')}</p></section>

  const ctx: Ctx = { postId, canWrite: page.canWrite, replyingTo, setReplyingTo, highlight, added, removed,
    updated: (c) => setItems((list) => replaceComment(list, c)), setItems }
  return (
    <section className="comments" id="comments" aria-labelledby="comments-title">
      <h2 id="comments-title" className="comments-title">{t('댓글')} {count}</h2>
      <WriteBox ctx={ctx} />
      {page.prevCursor && (
        <MoreButton label={t('이전 댓글 보기')} loading={loadingMore === 'prev'} failed={moreFailed === 'prev'} onClick={() => more('prev')} />
      )}
      {items.length === 0 ? <p className="muted center">{t('첫 댓글을 남겨 보세요')}</p> : (
        <ol className="comment-list">
          {items.map((c) => <CommentItem key={c.id} c={c} rootId={null} ctx={ctx} />)}
        </ol>
      )}
      {page.nextCursor && (
        <MoreButton label={t('댓글 더 보기')} loading={loadingMore === 'next'} failed={moreFailed === 'next'} onClick={() => more('next')} />
      )}
    </section>
  )
}

interface Ctx {
  postId: number
  canWrite: boolean
  replyingTo: number | null
  setReplyingTo: (id: number | null) => void
  highlight: number | null
  added: (c: CommentView, rootId: number | null) => void
  removed: (c: CommentView) => void
  updated: (c: CommentView) => void
  setItems: (fn: (list: CommentView[]) => CommentView[]) => void
}

function MoreButton({ label, loading, failed, onClick }: { label: string; loading: boolean; failed: boolean; onClick: () => void }) {
  if (failed) return <p className="feed-error small" role="alert">{t('불러오지 못했어요')} <button type="button" className="btn btn-text" onClick={onClick}>{t('다시 시도')}</button></p>
  return (
    <div className="more">
      <button type="button" className="btn btn-outline" disabled={loading} onClick={onClick}>{loading ? t('불러오는 중…') : label}</button>
    </div>
  )
}

/** 최상위 입력칸, 또는 비회원·인증 전 회원 안내 (FR-022). */
function WriteBox({ ctx }: { ctx: Ctx }) {
  const { me } = useAuth()
  const [sent, setSent] = useState(false)
  const [resendFailed, setResendFailed] = useState(false)
  if (!me?.authenticated) {
    return <p className="comment-guest">{t('로그인하고 댓글을 남겨 보세요')} <Link to={loginPath()} className="btn btn-text">{t('로그인')}</Link></p>
  }
  if (!me.emailVerified) {
    return (
      <p className="comment-guest">{t('이메일 인증 후 댓글을 쓸 수 있어요')}{' '}
        <button type="button" className="btn btn-text" disabled={sent}
                onClick={() => {
                  setResendFailed(false)
                  api('/api/auth/email/resend', { method: 'POST' }).then(() => setSent(true)).catch(() => setResendFailed(true))
                }}>
          {sent ? t('보냈어요') : t('인증 메일 다시 보내기')}
        </button>
        {resendFailed && <small className="error" role="alert">  {t('메일을 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.')}</small>}
      </p>
    )
  }
  if (!ctx.canWrite) return null
  return <Editor placeholder={t('댓글을 남겨 보세요')} submitLabel={t('등록')} busyLabel={t('등록 중…')}
                 onSubmit={async (text) => ctx.added(await commentsApi.create(ctx.postId, text), null)} />
}

/** 쓰기·답글·수정이 같이 쓰는 입력칸. 실패하면 입력한 내용을 그대로 두고 이유를 보여 준다. */
function Editor({ initial = '', placeholder, submitLabel, busyLabel, onSubmit, onCancel, autoFocus }: {
  initial?: string
  placeholder: string
  submitLabel: string
  busyLabel: string
  onSubmit: (text: string) => Promise<void>
  onCancel?: () => void
  autoFocus?: boolean
}) {
  const [text, setText] = useState(initial)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ref = useRef<HTMLTextAreaElement>(null)
  useEffect(() => { if (autoFocus) ref.current?.focus() }, [autoFocus])
  const length = commentLength(text)

  const submit = async () => {
    if (busy) return
    if (length === 0) return setError(t('댓글 내용을 입력해 주세요.'))
    if (length > MAX_COMMENT) return setError(t('댓글은 {0}자까지 쓸 수 있어요.', { 0: MAX_COMMENT }))
    setBusy(true)
    setError(null)
    try {
      await onSubmit(text)
      setText('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('잠시 후 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="comment-editor">
      <textarea ref={ref} value={text} placeholder={placeholder} rows={3} aria-label={placeholder}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); void submit() } }} />
      <div className="comment-editor-foot">
        <small className={length > MAX_COMMENT ? 'error' : 'muted'}>{length} / {MAX_COMMENT}</small>
        {error && <small className="error" role="alert">{error}</small>}
        <span className="row">
          {onCancel && <button type="button" className="btn btn-text" onClick={onCancel} disabled={busy}>{t('취소')}</button>}
          <button type="button" className="btn btn-primary" onClick={submit} disabled={busy}>{busy ? busyLabel : submitLabel}</button>
        </span>
      </div>
    </div>
  )
}

function CommentItem({ c, rootId, ctx }: { c: CommentView; rootId: number | null; ctx: Ctx }) {
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [loadingReplies, setLoadingReplies] = useState(false)
  const [repliesFailed, setRepliesFailed] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const normal = c.state === 'NORMAL'
  const isRoot = rootId == null

  const remove = async () => {
    if (!confirm(t('댓글을 지울까요? 되돌릴 수 없어요.'))) return
    setDeleting(true)
    setError(null)
    try {
      await commentsApi.remove(c.id)
      ctx.removed(c)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('지우지 못했어요. 잠시 후 다시 시도해 주세요.'))
      setDeleting(false)
    }
  }

  const moreReplies = async () => {
    setLoadingReplies(true)
    setRepliesFailed(false)
    try {
      const r = await commentsApi.replies(c.id, c.repliesNextCursor!)
      ctx.setItems((list) => list.map((x) => x.id !== c.id ? x
        : { ...x, replies: appendUnique(x.replies ?? [], r.items), repliesNextCursor: r.nextCursor }))
    } catch {
      setRepliesFailed(true)
    } finally {
      setLoadingReplies(false)
    }
  }

  const shown = c.replies?.length ?? 0
  const hiddenReplies = (c.replyCount ?? 0) - shown
  return (
    <li id={`comment-${c.id}`} className={`comment${isRoot ? '' : ' reply'}${ctx.highlight === c.id ? ' highlight' : ''}`}>
      <div className="comment-head">
        {c.author ? (
          <>
            <Link to={`/@${c.author.handle}`} className="comment-author">
              <Avatar src={c.author.profileImageUrl} name={c.author.nickname} seed={c.author.handle} size={28} />
              <b>{c.author.nickname}</b> <span className="muted nowrap">@{c.author.handle}</span>
            </Link>
            {c.author.isPostAuthor && <span className="badge">{t('작성자')}</span>}
          </>
        ) : (
          <span className="comment-author muted"><span className="avatar-blank" aria-hidden="true" />
            {c.state === 'WITHDRAWN_AUTHOR' ? t('탈퇴한 사용자') : ''}</span>
        )}
        {c.author && (
          <span className="muted small">
            · <time dateTime={c.createdAt} title={fullDate(c.createdAt)}>{relativeDate(c.createdAt)}</time>
            {c.edited && t(' · 수정됨')}
          </span>
        )}
      </div>

      {c.state === 'DELETED' && <p className="comment-body muted">{t('삭제된 댓글이에요')}</p>}
      {c.state === 'WITHDRAWN_AUTHOR' && <p className="comment-body muted">{t('탈퇴한 사용자의 댓글이에요')}</p>}
      {c.state === 'HIDDEN' && !c.mine && <p className="comment-body muted">{t('운영 정책에 따라 숨겨진 댓글이에요')}</p>}
      {c.state === 'HIDDEN' && c.mine && <p className="small badge-warn badge">{t('숨겨졌어요 (나만 보여요)')}</p>}

      {c.content != null && !editing && (
        <>
          {c.replyTo && <p className="comment-reply-to">{c.replyTo.withdrawn ? t('탈퇴한 사용자에게') : t('@{0}에게', { 0: c.replyTo.nickname })}</p>}
          <p className="comment-body">{c.content}</p>
        </>
      )}
      {editing && (
        <Editor initial={c.content ?? ''} placeholder={t('댓글 고치기')} submitLabel={t('저장')} busyLabel={t('저장 중…')} autoFocus
                onCancel={() => setEditing(false)}
                onSubmit={async (text) => { ctx.updated(await commentsApi.update(c.id, text)); setEditing(false) }} />
      )}

      {!editing && (
        <div className="comment-actions">
          {normal && ctx.canWrite && (
            <button type="button" className="btn btn-text" onClick={() => ctx.setReplyingTo(ctx.replyingTo === c.id ? null : c.id)}>{t('답글')}</button>
          )}
          {c.mine && normal && <button type="button" className="btn btn-text" onClick={() => setEditing(true)}>{t('수정')}</button>}
          {c.mine && (normal || c.state === 'HIDDEN') && (
            <button type="button" className="btn btn-text danger" onClick={remove} disabled={deleting}>{deleting ? t('지우는 중…') : t('삭제')}</button>
          )}
          {!c.mine && normal && <ReportButton targetType="COMMENT" targetId={c.id} />}
        </div>
      )}
      {error && <p className="error small" role="alert">{error}</p>}

      {ctx.replyingTo === c.id && (
        <Editor placeholder={t('답글을 남겨 보세요')} submitLabel={t('등록')} busyLabel={t('등록 중…')} autoFocus onCancel={() => ctx.setReplyingTo(null)}
                onSubmit={async (text) => ctx.added(await commentsApi.create(ctx.postId, text, c.id), rootId ?? c.id)} />
      )}

      {isRoot && (c.replies?.length ?? 0) > 0 && (
        <ol className="comment-list replies">
          {c.replies!.map((r) => <CommentItem key={r.id} c={r} rootId={c.id} ctx={ctx} />)}
        </ol>
      )}
      {isRoot && hiddenReplies > 0 && c.repliesNextCursor && (
        repliesFailed
          ? <p className="feed-error small" role="alert">{t('불러오지 못했어요')} <button type="button" className="btn btn-text" onClick={moreReplies}>{t('다시 시도')}</button></p>
          : <button type="button" className="btn btn-text more-replies" disabled={loadingReplies} onClick={moreReplies}>
              {loadingReplies ? t('불러오는 중…') : t('답글 {0}개 더 보기', { 0: hiddenReplies })}
            </button>
      )}
    </li>
  )
}
