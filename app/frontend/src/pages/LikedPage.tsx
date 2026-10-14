import { useEffect, useState } from 'react'
import { Feed } from '../components/Feed'
import { useAuth } from '../lib/auth'
import { relativeDate } from '../lib/format'
import { clearReads, forgetRead, recentReads } from '../lib/recentReads'
import { Link } from '../lib/router'
import { t } from '../lib/i18n'

/** 읽기 목록 탭 (097): velog처럼 좋아한 글과 최근 읽은 글을 한 화면의 두 탭으로 오간다 */
function ReadingTabs({ current }: { current: 'liked' | 'read' }) {
  const { me } = useAuth()
  return (
    <nav className="tabs" aria-label={t('읽기 목록')}>
      {me?.authenticated && (
        <Link to="/lists/liked" aria-current={current === 'liked' ? 'page' : undefined} data-tip={t('내가 좋아요를 누른 글')}>{t('좋아한 글')}</Link>
      )}
      <Link to="/lists/read" aria-current={current === 'read' ? 'page' : undefined} data-tip={t('이 기기에서 최근에 연 글')}>{t('최근 읽은 글')}</Link>
    </nav>
  )
}

/** 좋아한 글 /lists/liked (027). 좋아요를 누른 최근 순, 지금 읽을 수 있는 글만. */
export function LikedPage() {
  useEffect(() => { document.title = t('좋아한 글 - devlog') }, [])
  return (
    <main className="container">
      <h1 className="page-title">{t('읽기 목록')}</h1>
      <ReadingTabs current="liked" />
      <Feed endpoint="/api/me/liked-posts" storageKey="feed:liked" initial={null}
            empty={<><p>{t('아직 좋아요를 누른 글이 없어요')}</p><Link to="/" className="btn btn-primary">{t('홈에서 글 찾기')}</Link></>} />
    </main>
  )
}

/**
 * 최근 읽은 글 /lists/read (097 FR-008). 이 브라우저에 남은 기록만 보여 주고 서버에는 묻지 않는다.
 * 그 사이 지워지거나 비공개가 된 글은 눌렀을 때 글 화면이 "볼 수 없는 페이지"를 보여 준다.
 */
export function RecentReadsPage() {
  const [items, setItems] = useState(() => recentReads())
  useEffect(() => { document.title = t('최근 읽은 글 - devlog') }, [])
  return (
    <main className="container">
      <h1 className="page-title">{t('읽기 목록')}</h1>
      <ReadingTabs current="read" />
      {items.length === 0 ? (
        <div className="empty">
          <p>{t('아직 이 기기에서 읽은 글이 없어요')}</p>
          <Link to="/" className="btn btn-primary">{t('홈에서 글 찾기')}</Link>
        </div>
      ) : (
        <>
          <p className="muted small recent-note">
            {t('이 기기에만 남는 기록이에요. 최근 50편까지 보여요.')}{' '}
            <button type="button" className="btn btn-text btn-small" data-tip={t('이 기기의 읽은 기록을 모두 지워요')}
                    onClick={() => { if (confirm(t('최근 읽은 글 기록을 모두 지울까요?'))) { clearReads(); setItems([]) } }}>{t('모두 지우기')}</button>
          </p>
          <ul className="recent-list">
            {items.map((r) => (
              <li key={r.id} className="recent-item">
                <Link to={r.url} className="recent-link">
                  {r.thumbnailUrl ? <img src={r.thumbnailUrl} alt="" loading="lazy" className="recent-thumb" /> : <span className="recent-thumb recent-thumb-empty" aria-hidden="true" />}
                  <span className="recent-text">
                    <b className="recent-title">{r.title}</b>
                    <span className="muted small">{r.author} · {t('{0} 읽음', { 0: relativeDate(r.readAt) })}</span>
                  </span>
                </Link>
                <button type="button" className="recent-remove" aria-label={t('\'{0}\' 기록 지우기', { 0: r.title })} data-tip={t('이 글을 기록에서 지우기')}
                        onClick={() => { forgetRead(r.id); setItems(recentReads()) }}>×</button>
              </li>
            ))}
          </ul>
        </>
      )}
    </main>
  )
}
