import { useEffect, useState } from 'react'
import { api, takeInitialData } from '../lib/api'
import { Link } from '../lib/router'
import { t, tNodes } from '../lib/i18n'

export interface About { ownerHandle: string | null; contactEmail?: string | null; contactUrl?: string | null }

/**
 * 사이트 소개 (077, spec 107). 무엇을 하는 곳인지, 누가 운영하는지, 글을 어떻게 쓰고 AI를 어디까지 쓰는지(#ai), 어디로 연락하는지. 서버(PageController.siteAbout)가 같은 내용을
 * 먼저 그려 두어 검색 엔진·애드센스 심사도 읽는다. 블로그별 소개(@handle/about)와는 다른, 사이트 전체의 소개다.
 */
export function AboutPage() {
  const about = useSiteAbout()
  useEffect(() => {
    document.title = t('소개 - devlog')
  }, [])
  // 다른 화면의 "글 쓰는 방식"(/about#ai) 링크로 왔으면 그 항목으로 내려간다
  useEffect(() => {
    if (window.location.hash === '#ai') document.getElementById('ai')?.scrollIntoView({ block: 'start' })
  }, [])

  const owner = about?.ownerHandle
  return (
    <main className="container narrow terms site-about">
      <h1 className="page-title">{t('devlog 소개')}</h1>
      <p className="site-about-slogan">{t('코딩은 AI와, 기록은 devlog가.')}</p>
      <h2>{t('무엇을 하는 곳인가요')}</h2>
      <p>
        {t('devlog는 개발자가 직접 겪은 문제와 해결 과정을 기술 글로 남기는 블로그예요.')}{' '}
        {t('운영자는 이 서비스를 만들고 운영하면서 나온 설계 결정, 장애 원인, 보안 점검을 글로 정리해요.')}{' '}
        {tNodes('Claude 같은 AI 도구에 devlog를 연결하면 그날 작업한 내용을 정리해 개발 일지 임시글로 올려 줘요. {0}', { 0: <Link to="/mcp">{t('AI에 연결하는 방법')}</Link> })}
      </p>
      <h2>{t('누가 운영하나요')}</h2>
      <p>
        {t('학교 팀 프로젝트로 시작했고, 지금은 운영자 한 명이 서버와 서비스를 맡고 있어요.')}
        {owner && <> {tNodes('운영자 블로그: {0}', { 0: <Link to={`/@${owner}`}>@{owner}</Link> })}</>}{' '}
        {tNodes('운영자가 풀어 온 문제는 {0}에 모았어요.', { 0: <Link to="/portfolio" data-tip={t('문제 → 결정 → 결과 숫자, 사례 5개')}>{t('포트폴리오')}</Link> })}
      </p>
      <h2 id="ai">{t('글 쓰는 방식과 AI 사용 범위')}</h2>
      <ul>
        {AI_SCOPE.map((line) => <li key={line}>{t(line)}</li>)}
      </ul>
      <h2>{t('연락하기')}</h2>
      <p>
        {tNodes('{0}에서 메일과 공개 문의 창구를 볼 수 있어요.', { 0: <Link to="/contact">{t('연락처')}</Link> })}{' '}
        {tNodes('로그인했다면 {0}에 남겨 주세요. 답변을 알림으로 드려요.', { 0: <Link to="/support">{t('문의·신고')}</Link> })}
      </p>
      <GuestContact email={about?.contactEmail ?? null} url={about?.contactUrl ?? null} />
      <p className="muted small">
        <Link to="/terms">{t('이용약관')}</Link> · <Link to="/privacy">{t('개인정보 처리방침')}</Link> · <Link to="/releases">{t('릴리스 노트')}</Link>
      </p>
    </main>
  )
}

/** "글 쓰는 방식과 AI 사용 범위" (spec 107). 서버(PageController.AI_SCOPE)와 같은 문장이다 */
const AI_SCOPE = [
  '주제, 글의 결론, 글에 넣는 숫자는 글쓴이가 정하고 확인해요. 측정값은 실제로 실행한 결과에서 가져오고, 근거가 되는 PR·커밋을 글 끝에 붙여요.',
  'AI 도구(devlog MCP)로 초안을 만들거나 고친 글은 글 끝에 "AI 보조 작성"이라고 표시해요.',
  'AI 도구가 만든 글은 기본으로 임시글이에요. 글쓴이가 설정에서 AI 발행을 허용했을 때만 AI 도구가 발행할 수 있어요.',
  '개발 일기는 작업 메모를 AI가 하루 한 편으로 묶은 기록이에요. 검색 엔진 수집 대상에서 빼요.',
  '매주 일요일 그 주의 일기로 주간 회고 초안을 만들어요. 도입과 배운 점을 사람이 직접 써야 발행할 수 있어요.',
]

/** 소개·연락처 화면이 함께 쓰는 운영자·연락처 데이터 (077, spec 107). 서버가 첫 화면에 넣었으면 그것을 쓴다 */
export function useSiteAbout(): About | null {
  const [about, setAbout] = useState<About | null>(() => takeInitialData<About>('about') ?? takeInitialData<About>('contact'))
  useEffect(() => {
    if (!about) api<About>('/api/site/about').then(setAbout).catch(() => setAbout({ ownerHandle: null }))
  }, [about])
  return about
}

/** 로그인하지 않아도 운영자에게 닿는 연락 방법 (spec 104 FR-006). 서버 설정이 둘 다 비면 그리지 않는다 */
export function GuestContact({ email, url }: { email: string | null; url: string | null }) {
  if (!email && !url) return null
  const mail = email && <a href={`mailto:${email}`}>{email}</a>
  const desk = url && <a href={url} target="_blank" rel="noopener noreferrer" title={t('새 창에서 열려요')}>{t('공개 문의 창구')}</a>
  return (
    <p>
      {mail && desk
        ? tNodes('로그인하지 않았다면 메일 {0}이나 {1}로 연락해 주세요.', { 0: mail, 1: desk })
        : mail
          ? tNodes('로그인하지 않았다면 메일 {0}로 연락해 주세요.', { 0: mail })
          : tNodes('로그인하지 않았다면 {0}로 연락해 주세요.', { 0: desk })}
    </p>
  )
}
