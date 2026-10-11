import { useEffect, useState } from 'react'
import { api, takeInitialData } from '../lib/api'
import { getLocale, t } from '../lib/i18n'

interface PolicyDocument { kind: 'terms' | 'privacy'; title: string; effectiveDate: string | null; html: string }

/**
 * 이용약관·개인정보 처리방침 (005 FR-026, spec 085). 문안은 서버의 policy/*.html 한 곳에 있다. 서버가 같은 본문을 첫 화면에
 * 그려 두어 애드센스 심사·검색 엔진도 읽고, 화면 안에서 옮겨 오면 API로 받는다. 저장소에 든 문안이라 그대로 넣는다.
 * 본문은 법적 문서라 한국어 원문만 두고(spec 081 다음 단계 3), 제목·안내·링크만 화면 언어로 보인다.
 */
export function TermsPage({ kind }: { kind: 'terms' | 'privacy' }) {
  const [doc, setDoc] = useState<PolicyDocument | null>(() => {
    const initial = takeInitialData<{ policy: PolicyDocument }>('policy')?.policy
    return initial?.kind === kind ? initial : null
  })
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    if (doc?.kind === kind) return
    setFailed(false)
    api<PolicyDocument>(`/api/site/policies/${kind}`).then(setDoc).catch(() => setFailed(true))
  }, [kind, doc])
  const title = kind === 'terms' ? t('이용약관') : t('개인정보 처리방침')
  useEffect(() => {
    document.title = `${title} - devlog`
  }, [title])
  // 재동의 화면의 "전문 보기"(/privacy#ads)처럼 항목을 가리켜 왔으면 본문이 그려진 뒤 그 항목으로 내려간다
  const shown = doc?.kind === kind ? doc : null
  useEffect(() => {
    if (!shown) return
    let id = ''
    try {
      id = decodeURIComponent(window.location.hash.slice(1))
    } catch {
      return // 잘못 인코딩된 해시는 무시하고 맨 위에 둔다
    }
    if (id) document.getElementById(id)?.scrollIntoView({ block: 'start' })
  }, [shown])
  return (
    <main className="container narrow terms">
      <h1 className="page-title">{title}</h1>
      {shown?.effectiveDate && <p className="muted small">{t('시행일 {0}', { 0: shown.effectiveDate })}</p>}
      {getLocale() !== 'ko' && <p className="muted small" lang={getLocale()}>{t('이 문서는 한국어 원문만 있어요. 법적 효력이 있는 문서라 번역본은 따로 검토한 뒤 올릴게요.')}</p>}
      {shown && <div lang="ko" dangerouslySetInnerHTML={{ __html: shown.html }} />}
      {!shown && failed && <p className="muted">{t('문안을 불러오지 못했어요. 잠시 뒤 새로 고쳐 주세요.')}</p>}
      <p><a href={kind === 'terms' ? '/privacy' : '/terms'}>{kind === 'terms' ? t('개인정보 처리방침 보기') : t('이용약관 보기')}</a></p>
    </main>
  )
}
