import { useEffect, useState } from 'react'
import { Avatar } from '../components/Avatar'
import { Feed } from '../components/Feed'
import { GraphLogo } from '../components/GraphLogo'
import { FollowButton } from '../components/FollowButton'
import { SearchBox } from '../components/SearchBox'
import { SocialLinkList } from '../components/SocialLinkList'
import { parseSort } from '../lib/search'
import { PostResults } from './SearchPage'
import { api, ApiError, takeInitialData } from '../lib/api'
import { loginPath, useAuth } from '../lib/auth'
import { friendsApi, lastActiveLabel } from '../lib/friends'
import { Link, navigate, useLocation } from '../lib/router'
import { blogTagPath, normalizeTag, tagFormatError, tagsApi, type TagCount } from '../lib/tags'
import type { BlogProfile, FeedPage, FriendRelation } from '../lib/types'
import { NotFoundPage } from './NotFoundPage'
import { BlogSeries } from './SeriesPage'
import { BlogAbout } from '../components/BlogAbout'
import { NavIcon } from '../components/NavIcons'
import { avatarColor, AVATAR_COLORS } from '../lib/avatar'
import { compactNumber } from '../lib/format'
import { t } from '../lib/i18n'
import { loadBadges, type StreakBadge } from '../lib/points'
import { StreakBadgeIcon } from '../components/StreakBadge'
import { bannerProps, frameProps, loadDecorations, themeStyle, type Decorations } from '../lib/shop'

/** 블로그 표지 색(0~3). 같은 블로그는 언제나 같은 색이다. */
export function blogTone(handle: string): number {
  return AVATAR_COLORS.indexOf(avatarColor(handle) as (typeof AVATAR_COLORS)[number]) % 4
}

/** tab: 블로그 글 목록(기본), [시리즈] 탭 (024), [소개] 탭 (042) */
export function BlogPage({ handle, tab = 'posts' }: { handle: string; tab?: 'posts' | 'series' | 'about' }) {
  const [initial] = useState(() => takeInitialData<{ profile: BlogProfile; feed: FeedPage; blogTags: TagCount[]; tag?: string }>('blog'))
  const [profile, setProfile] = useState<BlogProfile | null>(initial?.profile.handle === handle ? initial.profile : null)
  const [missing, setMissing] = useState(false)
  const { search } = useLocation()
  const rawTag = search.get('tag')
  const tag = rawTag ? normalizeTag(rawTag) : null
  const badTag = tag != null && tagFormatError(tag) != null
  const q = (search.get('q') ?? '').trim()
  const searchIn = (nq: string) => navigate(`/@${handle}?q=${encodeURIComponent(nq)}`)
  const { me } = useAuth()
  const pointsOn = !!me?.features?.points
  const [badges, setBadges] = useState<StreakBadge[]>([])
  // 연속 출석 휘장(#266)은 포인트가 켜졌을 때만 받는다. 가장 높은 휘장 하나를 이름 옆에 둔다
  useEffect(() => {
    if (!pointsOn) return
    let alive = true
    loadBadges(handle).then((b) => { if (alive) setBadges(b) }).catch(() => { if (alive) setBadges([]) })
    return () => { alive = false }
  }, [handle, pointsOn])
  const topBadge = badges.length > 0 ? badges[badges.length - 1] : null
  const [deco, setDeco] = useState<Decorations | null>(null)

  useEffect(() => {
    // 블로그 꾸미기 (#268): 포인트를 켠 서버에서만 받는다. 못 받아도 기본 모습으로 보인다
    setDeco(null)
    if (pointsOn) loadDecorations(handle).then(setDeco).catch(() => {})
  }, [handle, pointsOn])

  useEffect(() => {
    // 정규화되지 않은 필터 값은 정규화된 주소로 바꾼다 (서버는 301)
    if (tag && !badTag && tag !== rawTag) navigate(blogTagPath(handle, tag), { replace: true })
  }, [tag, rawTag, badTag, handle])

  useEffect(() => {
    if (profile?.handle === handle) return
    api<BlogProfile>(`/api/members/${encodeURIComponent(handle)}`)
      .then(setProfile)
      .catch((e) => { if (e instanceof ApiError && e.status === 404) setMissing(true) })
  }, [handle, profile])

  if (missing || badTag) return <NotFoundPage />
  if (!profile) return <main className="container"><p className="muted center">{t('불러오는 중…')}</p></main>
  const banner = bannerProps(deco?.banner)
  const frame = frameProps(deco?.frame)
  const theme = themeStyle(deco?.theme)
  return (
    <main className={`container${theme ? ' blog-themed' : ''}`} style={theme}>
      <header className="blog-hero">
        <div className={`blog-cover${deco?.banner ? ' deco-banner' : ''}${banner.image ? ' has-image' : ''}`} data-tone={blogTone(profile.handle)}
          data-banner={banner['data-banner']} style={banner.style} aria-hidden="true" />
        <div className="blog-hero-body">
          <div className="blog-hero-top">
            <span className={`blog-avatar${frame['data-frame'] ? ' deco-frame' : ''}`} data-frame={frame['data-frame']} style={frame.style}
              title={deco?.frame ? t('{0} 테두리', { 0: deco.frame.name }) : undefined}><Avatar src={profile.profileImageUrl} name={profile.nickname} seed={profile.handle} size={112} /></span>
            <div className="blog-hero-actions">
              <Link to={`/@${profile.handle}/rss`} className="btn btn-outline btn-icon" aria-label={t('RSS 구독')}
                    data-tip={t('RSS 구독: 구독 앱으로 이 블로그의 새 글 받기')}><NavIcon name="rss" /></Link>
              {profile.mine ? <>
                <Link to="/settings#profile" className="btn btn-outline" data-tip={t('사진·닉네임·소개 바꾸기')}>{t('프로필 편집')}</Link>
                <Link to="/manage/posts" className="btn btn-outline" data-tip={t('임시글·발행 글·휴지통 관리')}>{t('글 관리')}</Link>
                {pointsOn && <Link to="/shop" className="btn btn-outline" data-tip={t('상점 포인트로 테마 색·프로필 테두리·표지를 사서 블로그 꾸미기')}>{t('꾸미기')}</Link>}
                <Link to="/write" className="btn btn-primary" data-tip={t('새 글 쓰기')}><NavIcon name="pen" size={16} />{t('새 글')}</Link>
              </> : <>
                <FriendButton profile={profile} onChange={(f) => {
                  setProfile((p) => p && { ...p, friendship: f, lastActiveDaysAgo: null })
                  // 친구가 되면 최근 활동을 다시 받아 온다 (서버가 조건을 판단한다)
                  if (f === 'FRIENDS') void api<BlogProfile>(`/api/members/${encodeURIComponent(handle)}`).then(setProfile).catch(() => {})
                }} />
                <FollowButton handle={profile.handle} following={profile.following}
                  onChange={(st) => setProfile((p) => p && { ...p, following: st.following, followerCount: st.followerCount })} />
              </>}
            </div>
          </div>
          <h1 className="blog-name">{profile.nickname}
            {topBadge && <StreakBadgeIcon days={topBadge.days} size={30} />}
          </h1>
          <p className="blog-handle muted">@{profile.handle}
            {lastActiveLabel(profile.lastActiveDaysAgo) && <span className="blog-active">  {t('· 최근 활동')} {lastActiveLabel(profile.lastActiveDaysAgo)}</span>}
          </p>
          {profile.bio && <p className="bio">{profile.bio}</p>}
          <SocialLinkList links={profile.socialLinks} />
          <ul className="blog-stats">
            <li><span className="blog-stat"><b>{compactNumber(profile.publicPostCount)}</b><span>{t('공개 글')}</span></span></li>
            <li><Link to={`/@${profile.handle}/followers`} className="blog-stat" data-tip={t('나를 팔로우하는 사람')}><b>{compactNumber(profile.followerCount)}</b><span>{t('팔로워')}</span></Link></li>
            <li><Link to={`/@${profile.handle}/following`} className="blog-stat" data-tip={t('내가 팔로우하는 사람')}><b>{compactNumber(profile.followingCount)}</b><span>{t('팔로잉')}</span></Link></li>
          </ul>
        </div>
      </header>
      <nav className="blog-tabs" aria-label={t('블로그 메뉴')}>
        <Link to={`/@${profile.handle}`} aria-current={tab === 'posts' ? 'page' : undefined}>{t('글')}</Link>
        <Link to={`/@${profile.handle}/series`} aria-current={tab === 'series' ? 'page' : undefined}>{t('시리즈')}</Link>
        <Link to={`/@${profile.handle}/about`} aria-current={tab === 'about' ? 'page' : undefined}>{t('소개')}</Link>
        <Link to={`/@${profile.handle}/portfolio`} className="blog-tab-portfolio" data-tip={t('프로젝트 위주로 정리한 포트폴리오 화면')}>
          <GraphLogo size={16} />  {t('포트폴리오로 보기')}
        </Link>
      </nav>
      {tab === 'about' ? <BlogAbout handle={profile.handle} /> : tab === 'series' ? <BlogSeries handle={profile.handle} mine={profile.mine} /> : <>
      <SearchBox initial={q} placeholder={t('{0}님의 글 검색', { 0: profile.nickname })} onSearch={searchIn} />
      {q ? (
        <>
          <div className="filter-head row">
            <b>{t('\'{0}\' 검색 결과', { 0: q })}</b>
            <Link to={`/@${handle}`} className="btn btn-text">{t('검색 해제')}</Link>
          </div>
          {/* 블로그 안 검색 (014 FR-003): 본인이 봐도 공개 글만 */}
          <PostResults key={q} q={q} sort={parseSort(search.get('sort'))} initial={null} blog={handle} />
        </>
      ) : <>
      <BlogTags handle={handle} initial={initial?.profile.handle === handle ? initial.blogTags : null} active={tag} />
      <Feed key={tag ?? ''} showAuthor={false}
            endpoint={`/api/members/${encodeURIComponent(handle)}/posts${tag ? `?tag=${encodeURIComponent(tag)}` : ''}`}
            storageKey={`feed:blog:${handle}${tag ? `:tag:${tag}` : ''}`}
            initial={initial?.profile.handle === handle && (initial.tag ?? null) === tag ? initial.feed : null}
            empty={tag ? <p>{t('이 태그로 공개한 글이 없어요.')}</p> : profile.mine
              ? <><p>{t('아직 공개한 글이 없어요.')}</p><Link to="/write" className="btn btn-primary">{t('첫 글 쓰기')}</Link></>
              : <p>{t('아직 공개한 글이 없어요.')}</p>} />
      </>}
      </>}
    </main>
  )
}

/** 블로그 태그 줄과 필터 머리 (010 FR-030·FR-031): 공개 글의 태그, 글 수 많은 순 처음 10개 + [태그 더 보기]. */
function BlogTags({ handle, initial, active }: { handle: string; initial: TagCount[] | null; active: string | null }) {
  const [tags, setTags] = useState<TagCount[] | null>(initial)
  const [all, setAll] = useState(false)
  useEffect(() => {
    if (!initial) tagsApi.blogTags(handle).then(setTags).catch(() => setTags([]))
  }, [handle, initial])
  if (!tags) return null
  const shown = all ? tags : tags.slice(0, 10)
  const activeCount = active ? tags.find((t) => t.name === active)?.postCount ?? 0 : 0
  return (
    <>
      {tags.length > 0 && (
        <nav className="blog-tags" aria-label={t('이 블로그의 태그')}>
          {shown.map((t) => (
            <Link key={t.name} to={blogTagPath(handle, t.name)} className={`tag-link${t.name === active ? ' active' : ''}`}
                  aria-current={t.name === active ? 'page' : undefined}>
              #{t.name} <span className="muted">{t.postCount}</span>
            </Link>
          ))}
          {tags.length > 10 && !all && <button type="button" className="btn btn-text" onClick={() => setAll(true)}>{t('태그 더 보기')}</button>}
        </nav>
      )}
      {active && (
        <div className="filter-head row">
          <b>#{active}  {t('글 {0}개', { 0: activeCount })}</b>
          <Link to={`/@${handle}`} className="btn btn-text">{t('필터 해제')}</Link>
        </div>
      )}
    </>
  )
}

/** 친구 요청·수락·취소·끊기 (008 US1). 요청과 수락은 상대에게 알린다(015 A-1). 거절·취소·끊기는 알리지 않는다. */
function FriendButton({ profile, onChange }: { profile: BlogProfile; onChange: (f: FriendRelation) => void }) {
  const { me } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const relation = profile.friendship ?? 'NONE'

  const run = async (fn: () => Promise<FriendRelation>) => {
    if (!me?.member) return navigate(loginPath())
    setBusy(true)
    setError(null)
    try {
      onChange(await fn())
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('처리하지 못했어요. 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }
  const removeAs = (next: FriendRelation) => async () => {
    await friendsApi.remove(profile.handle)
    return next
  }

  return (
    <div className="friend-actions row">
      {relation === 'NONE' && (
        <button type="button" className="btn btn-outline" disabled={busy} data-tip={t('상대가 수락하면 친구가 돼 친구 공개 글을 서로 볼 수 있어요. 상대에게 알림이 가요')}
                onClick={() => run(() => friendsApi.request(profile.handle))}>{t('친구 요청')}</button>
      )}
      {relation === 'SENT' && (
        <>
          <span className="muted small">{t('친구 요청을 보냈어요')}</span>
          <button type="button" className="btn btn-text" disabled={busy} data-tip={t('보낸 요청을 거둬요(상대에게 알리지 않아요)')}
                  onClick={() => run(removeAs('NONE'))}>{t('요청 취소')}</button>
        </>
      )}
      {relation === 'RECEIVED' && (
        <>
          <span className="muted small">{t('나에게 친구 요청을 보냈어요')}</span>
          <button type="button" className="btn btn-primary" disabled={busy} data-tip={t('친구가 되고 상대에게 수락 알림이 가요')}
                  onClick={() => run(() => friendsApi.accept(profile.handle))}>{t('수락')}</button>
          <button type="button" className="btn btn-text" disabled={busy} data-tip={t('요청을 지워요(상대에게 알리지 않아요)')}
                  onClick={() => run(removeAs('NONE'))}>{t('거절')}</button>
        </>
      )}
      {relation === 'FRIENDS' && (
        <>
          <span className="badge"><span aria-hidden="true">👥</span>  {t('친구')}</span>
          <button type="button" className="btn btn-text" disabled={busy} data-tip={t('친구 관계를 끊어요(상대에게 알리지 않아요)')} onClick={() => {
            if (confirm(t('{0}님과 친구를 끊을까요? 상대에게 알림은 가지 않아요.', { 0: profile.nickname }))) void run(removeAs('NONE'))
          }}>{t('친구 끊기')}</button>
        </>
      )}
      {error && <p className="error small" role="alert">{error}</p>}
    </div>
  )
}
