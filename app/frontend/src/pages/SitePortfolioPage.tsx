import { useEffect, useState } from 'react'
import { api, takeInitialData } from '../lib/api'
import { getLocale, t } from '../lib/i18n'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-700.css'
import '../portfolio-fonts.css'

interface SitePortfolio { ownerHandle: string | null; html: string }

/**
 * 사이트 포트폴리오 (spec 107 FR-015). 지원용 PDF와 같은 구조: 머리 띠(한 줄 소개·링크), 숫자 4칸, 프로젝트 2칸, 기술 4칸, 사례 6개.
 * 문안은 서버의 site/portfolio.html 한 곳에 있고, 서버가 같은 본문을 첫 화면에 그려 둔다. 저장소에 든 문안이라 그대로 넣는다.
 * 블로그별 포트폴리오(/@handle/portfolio, PortfolioPage)와는 다른 화면이다. 인쇄하면 A4 두 장(요약 + 사례)이다(styles.css @media print).
 */
export function SitePortfolioPage() {
  const [doc, setDoc] = useState<SitePortfolio | null>(() => takeInitialData<SitePortfolio>('site-portfolio'))
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    document.title = t('포트폴리오 - devlog')
    if (!doc) api<SitePortfolio>('/api/site/portfolio').then(setDoc).catch(() => setFailed(true))
  }, [doc])
  return (
    <main className="container narrow terms site-portfolio">
      <div className="site-portfolio-head">
        <h1 className="page-title">{t('포트폴리오')}</h1>
        <button type="button" className="btn btn-outline btn-small site-portfolio-print" onClick={() => window.print()} data-tip={t('A4 두 장(요약 + 사례)으로 인쇄하거나 PDF로 저장해요')}>
          {t('인쇄')}
        </button>
      </div>
      {getLocale() !== 'ko' && <p className="muted small" lang={getLocale()}>{t('이 화면은 한국어 원문만 있어요.')}</p>}
      {doc && <div lang="ko" dangerouslySetInnerHTML={{ __html: doc.html }} />}
      {!doc && failed && <p className="muted">{t('문안을 불러오지 못했어요. 잠시 뒤 새로 고쳐 주세요.')}</p>}
    </main>
  )
}
