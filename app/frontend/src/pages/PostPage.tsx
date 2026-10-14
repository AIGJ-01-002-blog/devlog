import { ReportButton } from '../components/ReportButton'
import { reasonLabel } from '../lib/moderation'
import { useEffect, useRef, useState } from 'react'
import { Avatar } from '../components/Avatar'
import { FollowButton } from '../components/FollowButton'
import { Comments } from '../components/Comments'
import { AttachmentList } from '../components/AttachmentList'
import { LikeButton } from '../components/LikeButton'
import { AdjacentPosts } from '../components/AdjacentPosts'
import { SocialLinkList } from '../components/SocialLinkList'
import { BranchBox } from '../components/BranchBox'
import { BranchMark } from '../components/BranchList'
import { SimilarPosts } from '../components/SimilarPosts'
import { ReadProgress } from '../components/ReadProgress'
import { ShareButton } from '../components/ShareButton'
import { Toc } from '../components/Toc'
import { ImageZoom } from '../components/ImageZoom'
import { enhanceCodeBlocks, labelTaskItems } from '../lib/postEnhance'
import { rememberRead } from '../lib/recentReads'
import { readingMinutes } from '../lib/toc'
import { api, ApiError, takeInitialData } from '../lib/api'
import { markRead, usePostBranch } from '../lib/branch'
import { clock, compactNumber, fullDate, monthDay, relativeDate } from '../lib/format'
import { enhanceGifs } from '../lib/gifPlayer'
import { renderDiagramsWithin } from '../lib/diagram'
import { highlightWithin } from '../lib/highlight'
import { useAuth } from '../lib/auth'
import { setFlash } from '../lib/flash'
import { Link, navigate } from '../lib/router'
import type { CommentPage } from '../lib/comments'
import { tagPath } from '../lib/tags'
import { VISIBILITY_CHANGED } from '../lib/visibility'
import { useViewBeacon, VIEW_HINT } from '../lib/views'
import { TRASH_CONFIRM, trashedMessage, trashPost } from '../lib/trash'
import type { PostDetail, Visibility } from '../lib/types'
import { NotFoundPage } from './NotFoundPage'
import { t } from '../lib/i18n'

export function PostPage({ handle, id }: { handle: string; id: string }) {
  const [boot] = useState(() => {
    const data = takeInitialData<{ post: PostDetail; comments?: CommentPage }>('post')
    return data && String(data.post.id) === id ? data : null
  })
  const [post, setPost] = useState<PostDetail | null>(boot?.post ?? null)
  const [missing, setMissing] = useState(false)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  const [minutes, setMinutes] = useState<number | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const { me } = useAuth()
  useViewBeacon(bodyRef, post?.id, !!post && !post.mine && post.status === 'PUBLISHED')
  const branch = usePostBranch(Number(id)) ?? null
  // 시리즈 이어 읽기(072): 이 기기에서 연 글을 기억한다. 서버에는 남기지 않는다
  useEffect(() => { if (post?.status === 'PUBLISHED') markRead(post.id) }, [post?.id, post?.status])
  // 최근 읽은 글(097): 남의 공개 발행 글만 이 기기에 남긴다
  useEffect(() => {
    if (!post || post.status !== 'PUBLISHED' || post.mine) return
    rememberRead({ id: post.id, url: post.url, title: post.title, author: post.author.nickname, thumbnailUrl: post.thumbnailUrl ?? null })
  }, [post?.id, post?.status])

  useEffect(() => {
    if (post && String(post.id) === id) return
    api<PostDetail>(`/api/posts/${encodeURIComponent(id)}`)
      .then((p) => {
        if (p.status === 'DRAFT' && p.mine) return navigate(`/write/${p.id}`, { replace: true })
        if (p.author.handle !== handle) navigate(p.url, { replace: true })
        setPost(p)
      })
      .catch((e) => { if (e instanceof ApiError && e.status === 404) setMissing(true) })
  }, [handle, id, post])

  useEffect(() => {
    if (post) {
      document.title = `${post.title} - ${post.author.nickname}`
      void renderDiagramsWithin(bodyRef.current)
      void highlightWithin(bodyRef.current)
      enhanceGifs(bodyRef.current)
      enhanceCodeBlocks(bodyRef.current)
      labelTaskItems(bodyRef.current)
      const body = bodyRef.current
      if (body) setMinutes(readingMinutes(body.textContent ?? '', body.querySelectorAll('img').length))
    }
  }, [post])

  // 본문은 불러온 뒤에 그려지므로 주소의 #제목으로는 브라우저가 스스로 이동하지 못한다. 글이 바뀔 때만 한 번 맞춘다.
  const postId = post?.id
  useEffect(() => {
    if (postId == null || !window.location.hash) return
    let target: string
    try { target = decodeURIComponent(window.location.hash.slice(1)) } catch { return }
    document.getElementById(target)?.scrollIntoView({ block: 'start' })
  }, [postId])

  if (missing) return <NotFoundPage />
  if (!post) return <main className="container narrow"><p className="muted center">{t('불러오는 중…')}</p></main>

  const changeVisibility = async (to: Visibility) => {
    if (to === 'PUBLIC' && !confirm(t('모든 사람이 볼 수 있게 돼요. 공개할까요?'))) return
    try {
      const r = await api<{ visibility: Visibility; firstPublicAt: string | null }>(`/api/posts/${post.id}/visibility`,
        { method: 'PATCH', body: { visibility: to } })
      setPost({ ...post, visibility: r.visibility, firstPublicAt: r.firstPublicAt })
      setNotice({ ok: true, text: VISIBILITY_CHANGED[to] })
    } catch (e) {
      setNotice({ ok: false, text: e instanceof ApiError ? e.message : t('바꾸지 못했어요.') })
    }
  }

  const discard = async () => {
    if (!confirm(t('수정 중인 내용을 버리고 발행본으로 돌아갈까요?'))) return
    try {
      await api(`/api/posts/${post.id}/draft`, { method: 'DELETE' })
      setPost({ ...post, owner: post.owner ? { ...post.owner, editing: false, editingSavedAt: null } : null })
      setNotice({ ok: true, text: t('변경을 취소했어요.') })
    } catch (e) {
      setNotice({ ok: false, text: e instanceof ApiError ? e.message : t('변경을 취소하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    }
  }

  const remove = async () => {
    if (!confirm(TRASH_CONFIRM)) return
    try {
      const r = await trashPost(post.id, me?.member?.id)
      setFlash(trashedMessage(r))
      navigate('/manage/posts?tab=trash', { replace: true })
    } catch (e) {
      setNotice({ ok: false, text: e instanceof ApiError ? e.message : t('삭제하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    }
  }

  const date = post.firstPublicAt ?? post.publishedAt
  return (
    <main className="container narrow">
      <ReadProgress target={bodyRef} />
      <article className="post">
        {post.owner?.hidden && (
          <div className="banner banner-warn">
            
            {post.owner.hiddenReason ? t('운영 정책에 따라 숨겨진 글이에요 (사유: {0}). 다른 사람에게는 보이지 않아요.', { 0: reasonLabel(post.owner.hiddenReason) }) : t('운영 정책에 따라 숨겨진 글이에요. 다른 사람에게는 보이지 않아요.')}
          </div>
        )}
        {post.mine && post.owner?.editing && (
          <div className="banner">
            
            {t('수정 중인 내용이 있어요')}{post.owner.editingSavedAt ? t('({0} {1} 저장)', { 0: monthDay(post.owner.editingSavedAt), 1: clock(post.owner.editingSavedAt) }) : ''}.
            <span className="banner-actions">
              <Link to={`/write/${post.id}`} className="btn btn-text">{t('이어서 수정')}</Link>
              <button type="button" className="btn btn-text" onClick={discard}>{t('변경 취소')}</button>
            </span>
          </div>
        )}
        {post.mine && post.visibility === 'PRIVATE' && <div className="banner"><span aria-hidden="true">🔒</span>  {t('나만 볼 수 있는 글이에요.')}</div>}
        {post.visibility === 'FRIENDS' && (
          <div className="banner"><span aria-hidden="true">👥</span> {post.mine ? t('나와 친구만 볼 수 있는 글이에요.') : t('친구에게만 공개된 글이에요.')}</div>
        )}
        {notice && (
          <div className={notice.ok ? 'banner banner-ok' : 'banner banner-warn'} role={notice.ok ? 'status' : 'alert'}>{notice.text}</div>
        )}
        {branch && (
          <Link to={branch.url} className={`bl-branch bl-branch-${branch.kind.toLowerCase()} post-branch`}
                data-tip={branch.kind === 'SERIES' ? t('시리즈 글 모두 보기') : t('홈에서 자동으로 묶인 비슷한 글만 보기')}>
            <BranchMark kind={branch.kind} />{branch.name} {branch.kind === 'SERIES' ? t('시리즈') : t('브랜치')}
            {branch.index != null && t(', {0}편', { 0: branch.index })}
          </Link>
        )}
        <h1 className="post-title">{post.title}</h1>
        <div className="post-meta">
          <span className="post-byline">
            <Link to={`/@${post.author.handle}`} className="post-author">{post.author.nickname}</Link>
            {date && <time dateTime={date} title={fullDate(date)}> · {relativeDate(date)}</time>}
            {post.editedAt && <span className="muted">  {t('· 수정됨')} {monthDay(post.editedAt)}</span>}
            {minutes != null && <span className="muted"> · {t('{0}분 읽기', { 0: minutes })}</span>}
            {post.status === 'PUBLISHED' && (
              <span className="muted post-byline-stats">  {t('· 조회')} {compactNumber(post.viewCount)}  {t('· 댓글')} {compactNumber(post.commentCount)}  {t('· 좋아요')} {compactNumber(post.likeCount)}</span>
            )}
          </span>
          {post.mine && (
            <span className="post-owner-actions">
              <Link to={`/write/${post.id}`} className="btn btn-text" data-tip={t('에디터에서 이 글 고치기')}>{t('수정')}</Link>
              {/* 옆의 수정·삭제와 같은 글자 버튼 모양. 누르면 기기 기본 선택 메뉴가 떠서 휴대폰에서도 고르기 쉽다 */}
              <span className="visibility-picker">
                <select aria-label={t('공개 범위')} data-tip={t('누가 이 글을 볼 수 있는지 바로 바꿔요')} value={post.visibility}
                        onChange={(e) => changeVisibility(e.target.value as Visibility)}>
                  <option value="PUBLIC">{t('🌐 전체 공개')}</option>
                  <option value="FRIENDS">{t('👥 친구에게만')}</option>
                  <option value="PRIVATE">{t('🔒 비공개')}</option>
                </select>
                <svg className="visibility-chevron" width="12" height="12" viewBox="0 0 24 24" aria-hidden="true" fill="none"
                     stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
              </span>
              <button type="button" className="btn btn-text danger" data-tip={t('휴지통으로 옮겨요. 30일 안에 되돌릴 수 있어요')} onClick={remove}>{t('삭제')}</button>
            </span>
          )}
        </div>
        {post.tags.length > 0 && (
          <ul className="post-tags" aria-label={t('태그')}>
            {post.tags.map((t) => <li key={t}><Link to={tagPath(t)} className="tag-link">#{t}</Link></li>)}
          </ul>
        )}
        <Toc bodyRef={bodyRef} html={post.contentHtml} />
        <div className="post-body markdown" ref={bodyRef} dangerouslySetInnerHTML={{ __html: post.contentHtml }} />
        <ImageZoom bodyRef={bodyRef} html={post.contentHtml} />
        <AttachmentList key={post.id} postId={post.id} />
        {branch && <BranchBox nav={branch} postId={post.id} />}
        {post.status === 'PUBLISHED' && <SimilarPosts key={`similar-${post.id}`} postId={post.id} />}
        <div className="post-stats muted">
          <LikeButton postId={post.id} mine={post.mine} initial={{ liked: post.liked, likeCount: post.likeCount }}
            onChange={(l) => setPost((p) => p && { ...p, liked: l.liked, likeCount: l.likeCount })} />
          {post.visibility !== 'PRIVATE' && <ShareButton path={post.url} title={post.title} />}
          <a href="#comments" data-tip={t('댓글로 가기')}>{t('댓글')} {compactNumber(post.commentCount)}</a>
          <span className="view-count" tabIndex={0} title={VIEW_HINT} aria-label={t('조회 {0}회, {1}', { 0: post.viewCount, 1: VIEW_HINT })}>
            
            {t('조회')} {compactNumber(post.viewCount)}
          </span>
          {date && <span>{fullDate(date)}</span>}
          {!post.mine && <ReportButton targetType="POST" targetId={post.id} />}
        </div>
        <footer className="author-card">
          <Avatar src={post.author.profileImageUrl} name={post.author.nickname} seed={post.author.handle} size={64} />
          <div>
            <Link to={`/@${post.author.handle}`}><b>{post.author.nickname}</b> <span className="muted">@{post.author.handle}</span></Link>
            {post.author.bio && <p className="bio">{post.author.bio}</p>}
            <SocialLinkList links={post.author.socialLinks} />
          </div>
          {!post.mine && <FollowButton handle={post.author.handle} following={post.author.following} />}
        </footer>
        {post.status === 'PUBLISHED' && <AdjacentPosts key={`adjacent-${post.id}-${post.visibility}`} postId={post.id} />}
      </article>
      {post.status === 'PUBLISHED' && (
        <Comments key={post.id} postId={post.id} initial={boot?.post.id === post.id ? boot.comments ?? null : null}
          onCount={(n) => setPost((p) => p && { ...p, commentCount: n })} />
      )}
    </main>
  )
}
