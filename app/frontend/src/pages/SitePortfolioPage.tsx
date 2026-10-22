import { useEffect, useState } from 'react'
import { api, takeInitialData } from '../lib/api'
import { getLocale, t } from '../lib/i18n'

interface SitePortfolio { ownerHandle: string | null; html: string }

/**
 * 사이트 포트폴리오 (spec 107 FR-015). 운영자 한 줄 소개, 사례 6개(문제 → 결정 → 결과 숫자, 근거 글), 기술 두 줄.
 * 문안은 서버의 site/portfolio.html 한 곳에 있고, 서버가 같은 본문을 첫 화면에 그려 둔다. 저장소에 든 문안이라 그대로 넣는다.
 * 블로그별 포트폴리오(/@handle/portfolio, PortfolioPage)와는 다른 화면이다. 인쇄하면 A4 한 장에 들어간다(styles.css @media print).
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
        <button type="button" className="btn btn-outline btn-small site-portfolio-print" onClick={() => window.print()} data-tip={t('A4 한 장으로 인쇄하거나 PDF로 저장해요')}>
          {t('인쇄')}
        </button>
      </div>
      {getLocale() !== 'ko' && <p className="muted small" lang={getLocale()}>{t('이 화면은 한국어 원문만 있어요.')}</p>}
      {doc && <div lang="ko" dangerouslySetInnerHTML={{ __html: doc.html }} />}
      {!doc && failed && <p className="muted">{t('문안을 불러오지 못했어요. 잠시 뒤 새로 고쳐 주세요.')}</p>}
    </main>
  )
}
