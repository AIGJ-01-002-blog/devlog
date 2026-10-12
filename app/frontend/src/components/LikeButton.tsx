import { useEffect, useRef, useState } from 'react'
import { loginPath, useAuth } from '../lib/auth'
import { compactNumber } from '../lib/format'
import { createLikeSync, likesApi, type LikeState } from '../lib/likes'
import { Link } from '../lib/router'
import { t } from '../lib/i18n'

/**
 * 글 상세 좋아요 (spec 012, docs/30 §5). 작성자에게는 개수만, 비회원·인증 전 회원에게는 버튼을 보이고 누르면 안내한다.
 * 누르는 즉시 ♡/♥와 숫자가 바뀌고, 마지막 상태만 0.3초 뒤에 보낸다.
 * 버튼 이름은 그대로 두고 눌림 여부는 aria-pressed로만 알린다(토글 버튼 규칙).
 */
export function LikeButton({ postId, mine, initial, onChange }: {
  postId: number
  mine: boolean
  initial: LikeState
  onChange?: (s: LikeState) => void
}) {
  const { me } = useAuth()
  const [state, setState] = useState(initial)
  // 막 누른 좋아요만 하트가 한 번 튄다. 처음부터 눌려 있던 글은 움직이지 않는다 (095)
  const [pop, setPop] = useState(false)
  const [notice, setNotice] = useState<'login' | 'verify' | 'failed' | null>(null)
  const sync = useRef<ReturnType<typeof createLikeSync> | null>(null)
  const changed = useRef(onChange)
  changed.current = onChange

  useEffect(() => {
    const s = createLikeSync({
      initial,
      send: (liked) => likesApi.set(postId, liked),
      onChange: (next) => { setState(next); changed.current?.(next) },
      onError: () => setNotice('failed'),
    })
    sync.current = s
    return () => s.dispose()
    // 글이 바뀔 때만 새로 만든다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [postId])

  const count = compactNumber(state.likeCount)
  if (mine) return <span className="like-count"><span aria-hidden="true">♥ {count}</span><span className="sr-only">{t('좋아요 {0}개', { 0: state.likeCount })}</span></span>

  const press = () => {
    if (!me?.authenticated) return setNotice('login')
    if (!me.emailVerified) return setNotice('verify')
    setNotice(null)
    setPop(!state.liked)
    sync.current?.toggle()
  }

  return (
    <span className="like">
      <button type="button" className={`like-button${state.liked ? ' liked' : ''}${pop && state.liked ? ' pop' : ''}`} aria-pressed={state.liked}
              aria-label={t('좋아요 {0}개', { 0: state.likeCount })} onClick={press}>
        <span aria-hidden="true"><span className="like-heart" onAnimationEnd={() => setPop(false)}>{state.liked ? '♥' : '♡'}</span> {count}</span>
      </button>
      {notice === 'login' && (
        <span className="like-notice" role="status">{t('로그인하고 좋아요를 눌러 보세요')} <Link to={loginPath()} className="btn btn-text">{t('로그인')}</Link></span>
      )}
      {notice === 'verify' && <span className="like-notice" role="status">{t('이메일 인증 후 누를 수 있어요')}</span>}
      {notice === 'failed' && <span className="like-notice error" role="alert">{t('좋아요를 반영하지 못했어요')}</span>}
    </span>
  )
}
