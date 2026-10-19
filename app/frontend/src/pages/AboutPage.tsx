import { useEffect, useState } from 'react'
import { api, takeInitialData } from '../lib/api'
import { Link } from '../lib/router'
import { t, tNodes } from '../lib/i18n'

export interface About { ownerHandle: string | null; contactEmail?: string | null; contactUrl?: string | null }

/**
 * 사이트 소개 (077). 무엇을 하는 곳인지, 누가 운영하는지, 어디로 연락하는지. 서버(PageController.siteAbout)가 같은 내용을
 * 먼저 그려 두어 검색 엔진·애드센스 심사도 읽는다. 블로그별 소개(@handle/about)와는 다른, 사이트 전체의 소개다.
 */
export function AboutPage() {
  const [about, setAbout] = useState<About | null>(() => takeInitialData<About>('about'))

  useEffect(() => {
    document.title = t('소개 - devlog')
    if (!about) api<About>('/api/site/about').then(setAbout).catch(() => setAbout({ ownerHandle: null }))
  }, [about])

  const owner = about?.ownerHandle
  return (
    <main className="container narrow terms site-about">
      <h1 className="page-title">{t('devlog 소개')}</h1>
      <p className="site-about-slogan">{t('코딩은 AI와, 기록은 devlog가.')}</p>
      <p>
        
        {tNodes('devlog는 개발자가 개발 일지와 기술 글을 쓰고 읽는 블로그예요. Claude 같은 AI 도구에 devlog를 연결하면 그날 작업한 내용을 정리해 개발 일지 임시글로 올려 주고, 발행은 글쓴이가 직접 정해요. {0}', { 0: <Link to="/mcp">{t('AI에 연결하는 방법')}</Link> })}
      </p>
      <h2>{t('무엇을 할 수 있나요')}</h2>
      <ul>
        <li>{t('마크다운으로 글을 쓰고, 쓰는 동안 자동 저장돼요.')}</li>
        <li>{t('태그와 시리즈로 글을 묶고, 시리즈를 포트폴리오로 보여 줄 수 있어요.')}</li>
        <li>{t('키워드와 의미를 함께 보는 검색, RSS 구독을 지원해요.')}</li>
      </ul>
      <h2>{t('누가 운영하나요')}</h2>
      <p>
        
        {t('학교 팀 프로젝트로 시작했고, 지금은 운영자 한 명이 서버와 서비스를 맡고 있어요.')}
        {owner && <>  {tNodes('운영자 블로그: {0}', { 0: <Link to={`/@${owner}`}>@{owner}</Link> })}</>}
      </p>
      <h2>{t('연락하기')}</h2>
      <p>
        {tNodes('{0}에서 질문, 버그, 권리 침해 신고를 받아요. 답변을 알림으로 드리려고 로그인한 뒤 남기도록 했어요.', { 0: <Link to="/support">{t('문의·신고')}</Link> })}
      </p>
      <GuestContact email={about?.contactEmail ?? null} url={about?.contactUrl ?? null} />
      <p className="muted small">
        <Link to="/terms">{t('이용약관')}</Link> · <Link to="/privacy">{t('개인정보 처리방침')}</Link> · <Link to="/releases">{t('릴리스 노트')}</Link>
      </p>
    </main>
  )
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
