import { useEffect, useState } from 'react'
import { BranchList, BranchMark } from '../components/BranchList'
import { Feed } from '../components/Feed'
import { GitHubLink } from '../components/GitHubLink'
import { api, takeInitialData } from '../lib/api'
import { branchChips } from '../lib/branch'
import { loginPath, useAuth } from '../lib/auth'
import { Link, useLocation } from '../lib/router'
import { TRENDING_ENDPOINT, TRENDING_HINT } from '../lib/trending'
import { tagPath } from '../lib/tags'
import type { Branch, Card, FeedPage } from '../lib/types'
import { t } from '../lib/i18n'
import { takeHeroIntro } from '../lib/motion'

/**
 * 홈 (003, 017, 072): [최신] [트렌딩] 탭. 기본은 최신, 트렌딩은 `/?tab=trending`. 순위 숫자는 보이지 않는다.
 * 최신은 브랜치 그래프 목록이고 `/?branch=s12`처럼 한 브랜치 글만 걸러 볼 수 있다. 넓은 화면에는 옆 칸이 있다.
 */
export function HomePage() {
  const { search } = useLocation()
  const trending = search.get('tab') === 'trending'
  const branch = trending ? null : search.get('branch')
  const [boot] = useState(() => takeInitialData<{ feed?: FeedPage; trending?: FeedPage }>('home'))
  const { me, loading } = useAuth()
  // 브랜치 버튼은 거르지 않은 최신 목록에서 뽑는다. 걸러 본 목록에서 뽑으면 다른 브랜치 버튼이 사라진다
  const [latest, setLatest] = useState<Card[] | null>(() => boot?.feed?.items ?? null)
  useEffect(() => { document.title = trending ? t('트렌딩 - devlog') : 'devlog' }, [trending])
  useEffect(() => {
    if (!branch || latest) return
    let live = true
    // 걸러 본 주소로 바로 들어와 최신 목록이 없을 때만 첫 쪽을 한 번 받는다. 실패하면 고른 버튼만 보인다
    api<FeedPage>('/api/posts').then((p) => { if (live) setLatest(p.items) }, () => {})
    return () => { live = false }
  }, [branch, latest])
  return (
    <main className="container home-layout">
      <div className="home-main">
        {!loading && !me?.authenticated && <HomeHero />}
        <div className="home-head">
          <h1 className="page-title">{trending ? t('트렌딩') : t('개발 기록')}</h1>
          <p className="muted small">{trending ? TRENDING_HINT : t('새 글은 main에 쌓이고, 이어지는 글은 시리즈·주제 브랜치로 갈라져요.')}</p>
        </div>
        <nav className="tabs home-tabs" aria-label={t('글 목록')}>
          <Link to="/" aria-current={trending ? undefined : 'page'}>{t('최신')}</Link>
          <Link to="/?tab=trending" aria-current={trending ? 'page' : undefined}>{t('트렌딩')}</Link>
        </nav>
        {trending ? (
          <Feed key="trending" endpoint={TRENDING_ENDPOINT} storageKey="feed:trending" initial={boot?.trending ?? null}
            renderItems={(items, hasMore) => <BranchList items={items} hasMore={hasMore} graph={false} />} empty={
            <>
              <p>{t('아직 트렌딩 글이 없어요')}</p>
              <Link to="/" className="btn btn-primary">{t('최신 글 보기')}</Link>
            </>
          } />
        ) : (
          // 서버가 처음 넣어 준 목록은 거르지 않은 목록이라, 브랜치로 거를 때는 쓰지 않는다
          <Feed key={branch ?? 'latest'} endpoint={branch ? `/api/posts?branch=${encodeURIComponent(branch)}` : '/api/posts'}
            storageKey={branch ? `feed:home:${branch}` : 'feed:home'} initial={branch ? null : boot?.feed ?? null}
            renderItems={(items, hasMore) => (
              <>
                <BranchChips base={branch ? latest ?? [] : items} visible={items} active={branch}
                  onBase={branch ? undefined : setLatest} />
                <BranchList items={items} hasMore={hasMore} />
              </>
            )}
            empty={branch ? (
              <>
                <p>{t('이 브랜치에 보이는 글이 없어요')}</p>
                <Link to="/" className="btn btn-primary">{t('모든 글 보기')}</Link>
              </>
            ) : (
              <>
                <p>{t('아직 올라온 글이 없어요. 첫 글의 주인공이 되어 보세요.')}</p>
                {me?.authenticated ? <Link to="/write" className="btn btn-primary">{t('글쓰기')}</Link>
                  : <Link to={loginPath('/write')} className="btn btn-primary">{t('로그인')}</Link>}
              </>
            )} />
        )}
      </div>
      <HomeAside />
    </main>
  )
}

/** 최신 목록 위 브랜치 버튼 (072). 긴 이름은 버튼 안에서 말줄임하고, 마우스를 올리면 전체 이름이 보인다 */
function BranchChips({ base, visible, active, onBase }: {
  base: Card[]; visible: Card[]; active: string | null; onBase?: (items: Card[]) => void
}) {
  // 거르지 않은 목록을 보는 동안 더 받은 쪽까지 기억해 두었다가 걸러 볼 때 같은 버튼을 보인다
  useEffect(() => { onBase?.(base) }, [base, onBase])
  const chips = branchChips(base, active, visible)
  if (chips.length === 0 && !active) return null
  return (
    <nav className="branch-chips" aria-label={t('브랜치로 걸러 보기')}>
      <Link to="/" className="branch-chip" aria-current={active ? undefined : 'page'} data-tip={t('모든 글을 시간순으로 봐요')}>{t('모든 글')}</Link>
      {chips.map((b) => (
        <Link key={b.key} to={`/?branch=${b.key}`} className={`branch-chip branch-chip-${b.kind.toLowerCase()}`}
              aria-current={active === b.key ? 'page' : undefined}
              data-tip={branchTip(b)}>
          <BranchMark kind={b.kind} /><span className="branch-chip-name">{b.name}</span>
        </Link>
      ))}
    </nav>
  )
}

/** 브랜치 버튼 설명. 주제 브랜치는 글쓴이가 만든 시리즈가 아니라 자동으로 묶인 것임을 알린다 */
function branchTip(b: Branch): string {
  return b.kind === 'SERIES'
    ? t('시리즈: 글쓴이가 엮은 {0} {1}편만 봐요', { 0: b.name, 1: b.total })
    : t('주제 브랜치: 태그·내용이 비슷해 자동으로 묶인 글 {0}편만 봐요', { 0: b.total })
}

interface PopularTopic { key: string; name: string; postCount: number; url: string }
interface PopularTag { name: string; postCount: number }

/** 홈 옆 칸 (072): 이어지는 주제, 많이 쓰는 태그, AI 연결, 릴리스 노트·문의. 좁은 화면에서는 숨긴다(무한 스크롤 끝에 닿지 않는다) */
function HomeAside() {
  const [topics, setTopics] = useState<PopularTopic[]>([])
  const [tags, setTags] = useState<PopularTag[]>([])
  useEffect(() => {
    // 곁들이 정보라 실패하면 그 칸만 비운다
    api<PopularTopic[]>('/api/topics/popular').then(setTopics, () => setTopics([]))
    api<PopularTag[]>('/api/tags?limit=8').then(setTags, () => setTags([]))
  }, [])
  return (
    <aside className="home-aside" aria-label={t('둘러보기')}>
      {topics.length > 0 && (
        <section className="aside-box">
          <h2 className="aside-title">{t('이어지는 주제')}</h2>
          <ul className="aside-topics">
            {topics.map((topic) => (
              <li key={topic.key}>
                <Link to={topic.url} data-tip={t('주제 브랜치: 태그·내용이 비슷해 자동으로 묶인 글 {0}편이에요', { 0: topic.postCount })}>
                  <BranchMark kind="TOPIC" /><span>{topic.name}</span><span className="muted small">{t('{0}편', { 0: topic.postCount })}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
      {tags.length > 0 && (
        <section className="aside-box">
          <h2 className="aside-title">{t('많이 쓰는 태그')}</h2>
          <ul className="card-tags">
            {tags.map((tag) => <li key={tag.name}><Link to={tagPath(tag.name)} className="card-tag" data-tip={t('글 {0}편', { 0: tag.postCount })}>#{tag.name}</Link></li>)}
          </ul>
        </section>
      )}
      <section className="aside-box aside-ai">
        <h2 className="aside-title">{t('AI가 쓰는 개발 일지')}</h2>
        <p className="muted small">{t('Claude·Cursor에 devlog를 연결하면 오늘 한 작업을 임시글로 정리해 줘요.')}</p>
        <Link to="/mcp" className="btn btn-outline btn-small">{t('AI에 연결하기')}</Link>
      </section>
      <nav className="aside-links" aria-label={t('도움말')}>
        <Link to="/releases">{t('릴리스 노트')}</Link>
        <span aria-hidden="true">·</span>
        <Link to="/support">{t('문의·신고')}</Link>
        <span aria-hidden="true">·</span>
        <GitHubLink className="aside-github" size={14} />
      </nav>
    </aside>
  )
}

/**
 * 비회원 첫 화면 소개 (048, 051). 슬로건과 "AI에 devlog 연결하기"·GitHub·"직접 글쓰기" 버튼, 그리고 AI가 개발 일지를 쓰는 모습을 보여 준다.
 * 로그인한 사람에게는 바로 글 목록을 보인다. 제목 구조를 흐트리지 않게 소제목 태그는 쓰지 않는다.
 */
/**
 * 로그인 전 첫 화면 소개 (048, 051, 097). 이번 방문에서 처음 볼 때만 글귀가 차례로 올라오고, 오른쪽 장면이
 * 대화처럼 한 줄씩 이어진다(약 2.4초). 제목은 0.06초 뒤 바로 올라와 첫 화면 시간을 늦추지 않는다. 움직임은 styles.css의 .home-hero-intro.
 */
function HomeHero() {
  const [intro] = useState(takeHeroIntro)
  return (
    <section className={intro ? 'home-hero home-hero-intro' : 'home-hero'} aria-label={t('devlog 소개')}>
      <div className="home-hero-copy">
        <p className="home-hero-eyebrow"><span className="home-hero-dot" aria-hidden="true" />{t('MCP 개발 일지 · Claude · Cursor')}</p>
        <p className="home-hero-title">{t('코딩은 AI와,')}<br /><span className="home-hero-accent">{t('기록은 devlog가.')}</span></p>
        <p className="home-hero-sub">{t('내 AI 도구에 devlog를 연결하면, 오늘 작업한 대화와 커밋을 정리해 개발 일지 초안을 써 줘요. 나는 확인하고 [발행]만 누르면 돼요.')}</p>
        <div className="home-hero-actions">
          <Link to="/mcp" className="btn btn-primary btn-lg" data-tip={t('내 AI 도구에 devlog를 연결하는 방법을 봐요')}>{t('AI에 devlog 연결하기')}</Link>
          <GitHubLink className="btn btn-outline btn-lg home-hero-github" size={18} />
          <Link to={loginPath('/write')} className="btn btn-outline btn-lg" data-tip={t('로그인하고 직접 글을 써요')}>{t('직접 글쓰기')}</Link>
        </div>
      </div>
      <HeroDemo />
    </section>
  )
}

/** AI 도구 안에서 개발 일지를 부탁하는 장면. 장식이라 화면 읽기 프로그램에는 한 줄 설명만 읽힌다. */
function HeroDemo() {
  return (
    <figure className="hero-demo">
      <figcaption className="sr-only">{t('예시: AI에게 "오늘 개발 일지 써 줘"라고 하면 devlog에 임시글이 생긴다')}</figcaption>
      <div className="hero-demo-bar" aria-hidden="true"><span /><span /><span /><b>Claude Code</b></div>
      <div className="hero-demo-body" aria-hidden="true">
        <p className="hero-demo-me"><span>›</span>  {t('오늘 한 작업으로 개발 일지 써 줘')}</p>
        <p className="hero-demo-tool"><span className="hero-demo-ok">●</span> devlog · <code>write_devlog</code></p>
        <div className="hero-demo-card">
          <span className="hero-demo-label">{t('임시글')}</span>
          <b>{t('Gemini 한도 넘으면 Ollama로 넘기기')}</b>
          <span className="hero-demo-meta">{t('커밋 4개 · 대화 요약 · 태그 spring-ai, ollama')}</span>
        </div>
        <p className="hero-demo-ai">{t('devlog에 임시글을 만들었어요. 읽어 보고 발행해 주세요.')}</p>
      </div>
    </figure>
  )
}
