import { useEffect } from 'react'
import { Link } from '../lib/router'
import { t, tNodes } from '../lib/i18n'
import { useSiteAbout } from './AboutPage'

/**
 * 연락처 (spec 107 FR-016). 로그인 없이 운영자에게 닿는 방법을 한 화면에 모은다. 서버(PageController.contact)가 같은 내용을
 * 먼저 그려 두어 검색 엔진·심사 도구도 읽는다. 메일·공개 문의 창구는 소개 화면과 같은 서버 설정값이다.
 */
export function ContactPage() {
  const about = useSiteAbout()
  useEffect(() => {
    document.title = t('연락처 - devlog')
  }, [])
  const email = about?.contactEmail
  const desk = about?.contactUrl
  return (
    <main className="container narrow terms site-about">
      <h1 className="page-title">{t('연락처')}</h1>
      <p>{t('devlog 운영자에게 연락하는 방법이에요.')}</p>
      <ul>
        {email && <li>{tNodes('메일: {0}', { 0: <a href={`mailto:${email}`} data-tip={t('메일 앱으로 열려요')}>{email}</a> })}</li>}
        {desk && (
          <li>
            {tNodes('{0}: 버그와 제안은 누구나 볼 수 있는 곳에 남겨요.', {
              0: <a href={desk} target="_blank" rel="noopener noreferrer" data-tip={t('새 창에서 열려요')}>{t('공개 문의 창구')}</a>,
            })}
          </li>
        )}
        <li>{tNodes('로그인했다면 {0}: 답변을 알림으로 받아요.', { 0: <Link to="/support" data-tip={t('질문·버그·신고 남기기')}>{t('문의·신고')}</Link> })}</li>
      </ul>
      <p>{t('개인 정보가 담긴 내용은 공개 문의 창구 대신 메일로 보내 주세요. 권리 침해 신고는 글 주소를 함께 적어 주세요.')}</p>
      <p className="muted small">
        <Link to="/about">{t('devlog 소개')}</Link> · <Link to="/privacy">{t('개인정보 처리방침')}</Link>
      </p>
    </main>
  )
}
