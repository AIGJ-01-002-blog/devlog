import { useEffect, useState } from 'react'
import { t } from '../lib/i18n'
import { Link } from '../lib/router'
import { platformSpec, postShorts, type BlogShortsCard, type Platform } from '../lib/shorts'

const PLATFORM_LABEL: Record<Platform, string> = { YOUTUBE: 'YouTube', INSTAGRAM: 'Instagram', TIKTOK: 'TikTok' }

/** 31000 → "0:31" */
function clock(ms: number | null): string | null {
  if (ms == null) return null
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/**
 * 블로그·글 화면의 쇼츠 카드 (spec 103). 회원이 직접 올리고 남긴 영상 주소로만 그린다.
 * YouTube 영상은 누르기 전까지 썸네일만 보이고, 누르면 그 자리에서 youtube-nocookie로 재생한다(쿠키·추적 스크립트를 미리 싣지 않는다).
 * Instagram·TikTok은 임베드 스크립트가 무거워 그 앱으로 가는 링크 카드로 둔다.
 */
export function ShortsGrid({ cards, showPost }: { cards: BlogShortsCard[]; showPost: boolean }) {
  return (
    <ul className="shorts-grid">
      {cards.map((c) => <ShortsCard key={c.id} card={c} showPost={showPost} />)}
    </ul>
  )
}

function ShortsCard({ card, showPost }: { card: BlogShortsCard; showPost: boolean }) {
  const [playing, setPlaying] = useState(false)
  // 썸네일을 못 받으면(막힌 네트워크·지운 영상) 제목 카드로 보인다
  const [thumbFailed, setThumbFailed] = useState(false)
  const youtube = card.links.find((l) => l.platform === 'YOUTUBE' && l.embedId)
  const first = card.links[0]
  const duration = clock(card.durationMs)
  return (
    <li className="shorts-card">
      <div className="shorts-media">
        {youtube && playing ? (
          <iframe src={`https://www.youtube-nocookie.com/embed/${youtube.embedId}?autoplay=1&playsinline=1&rel=0`}
            title={card.title} allow="autoplay; encrypted-media; picture-in-picture" allowFullScreen loading="lazy" />
        ) : youtube ? (
          <button type="button" className={`shorts-play${thumbFailed ? ' shorts-placeholder' : ''}`} onClick={() => setPlaying(true)}
            aria-label={t('{0} 재생', { 0: card.title })} data-tip={t('여기서 바로 재생해요 (YouTube)')}>
            {thumbFailed
              ? <span className="shorts-placeholder-title" aria-hidden="true">{card.title}</span>
              : <img src={`https://i.ytimg.com/vi/${youtube.embedId}/hqdefault.jpg`} alt="" loading="lazy" referrerPolicy="no-referrer"
                  onError={() => setThumbFailed(true)} />}
            <span className="shorts-play-icon" aria-hidden="true">▶</span>
            {duration && <span className="shorts-duration">{duration}</span>}
          </button>
        ) : (
          <a className="shorts-placeholder" href={first.url} target="_blank" rel="noopener noreferrer nofollow" data-platform={first.platform}
            data-tip={t('{0}에서 보기', { 0: platformSpec(first.platform).name })}>
            <span className="shorts-placeholder-title">{card.title}</span>
            <span className="shorts-play-icon" aria-hidden="true">▶</span>
            {duration && <span className="shorts-duration">{duration}</span>}
          </a>
        )}
      </div>
      <p className="shorts-title">{card.title}</p>
      {showPost && card.postUrl && card.postTitle && (
        <Link to={card.postUrl} className="shorts-post small" data-tip={t('이 쇼츠의 바탕 글 읽기')}>{card.postTitle}</Link>
      )}
      <div className="shorts-links">
        {card.links.map((l) => (
          <a key={l.platform} href={l.url} target="_blank" rel="noopener noreferrer nofollow" className="chip"
            data-tip={t('{0}에서 보기', { 0: platformSpec(l.platform).name })}>{PLATFORM_LABEL[l.platform]}</a>
        ))}
      </div>
    </li>
  )
}

/** 글 상세의 "이 글의 쇼츠" (spec 103). 공개 글이고 쇼츠를 켠 서버에서만 받고, 없으면 아무것도 그리지 않는다 */
export function PostShorts({ postId, enabled }: { postId: number; enabled: boolean }) {
  const [cards, setCards] = useState<BlogShortsCard[]>([])
  useEffect(() => {
    setCards([])
    if (!enabled) return
    let alive = true
    postShorts(postId).then((c) => { if (alive) setCards(c) }).catch(() => {})
    return () => { alive = false }
  }, [postId, enabled])
  if (cards.length === 0) return null
  return (
    <section className="post-shorts" aria-labelledby="post-shorts-title">
      <h2 id="post-shorts-title">{t('이 글의 쇼츠')}</h2>
      <ShortsGrid cards={cards} showPost={false} />
    </section>
  )
}
