import { useEffect } from 'react'
import { Link } from '../lib/router'
import { t } from '../lib/i18n'

/** 없음·볼 수 없음·권한 없음을 모두 같은 화면으로 (docs/42 P-4). */
export function NotFoundPage() {
  // 서버가 준 탭 제목은 한국어라 고른 언어로 다시 쓴다 (097)
  useEffect(() => { document.title = t('볼 수 없는 페이지예요 - devlog') }, [])
  return (
    <main className="container narrow center not-found">
      <h1>{t('볼 수 없는 페이지예요')}</h1>
      <p className="muted">{t('주소가 바뀌었거나, 삭제·비공개된 글일 수 있어요.')}</p>
      <Link to="/" className="btn btn-primary">{t('홈으로')}</Link>
    </main>
  )
}
