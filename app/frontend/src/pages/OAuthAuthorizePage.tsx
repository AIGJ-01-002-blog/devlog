import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { oauthApi, oauthParams, type OAuthView } from '../lib/mcp'
import { Link } from '../lib/router'
import { t, tNodes } from '../lib/i18n'

/**
 * AI 앱 연결 동의 (052). ChatGPT 같은 앱이 OAuth로 devlog에 연결할 때 이 화면으로 보낸다.
 * [허용]을 누르면 앱이 내 이름으로 MCP 도구를 쓸 수 있다. 발행은 여전히 내가 화면에서 한다.
 */
export function OAuthAuthorizePage() {
  const { me } = useAuth()
  const [params] = useState(() => oauthParams(window.location.search))
  const [view, setView] = useState<OAuthView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // 관리자만 보는 선택. 켜야 이 연결에 문의 관리 도구가 보인다 (#378)
  const [adminTools, setAdminTools] = useState(false)
  const isAdmin = me?.member?.role === 'ADMIN'

  useEffect(() => { document.title = t('AI 앱 연결 - devlog') }, [])
  useEffect(() => {
    oauthApi.view(window.location.search).then(setView)
      .catch((e) => setError(e instanceof ApiError ? e.message : t('연결 요청을 확인하지 못했어요.')))
  }, [])

  const decide = async (approve: boolean) => {
    setBusy(true)
    setError(null)
    try {
      const { redirect } = await api<{ redirect: string }>('/api/oauth/authorize', { method: 'POST', body: { ...params, approve, adminTools: isAdmin && adminTools } })
      window.location.assign(redirect)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('처리하지 못했어요. 다시 시도해 주세요.'))
      setBusy(false)
    }
  }

  return (
    <main className="container narrow oauth-page">
      <h1 className="page-title">{t('AI 앱 연결')}</h1>
      {error && <p className="error" role="alert">{error}</p>}
      {!view && !error && <p className="muted">{t('확인하는 중…')}</p>}
      {view && (
        <section className="oauth-card">
          <p className="oauth-unverified" role="note">{t('devlog가 확인하지 않은 앱이에요. 이름은 앱이 스스로 정한 것이라 아래 주소로 진짜 앱인지 확인해 주세요.')}</p>
          <p className="oauth-lead">{me?.member?.nickname
              ? tNodes('{0}이(가) {1}님의 devlog에 연결하려고 해요.', { 0: <b>{view.clientName}</b>, 1: <b>{me.member.nickname}</b> })
              : tNodes('{0}이(가) 내 devlog에 연결하려고 해요.', { 0: <b>{view.clientName}</b> })}</p>
          <ul className="oauth-scope">
            <li>{t('내 글과 공개 글 읽기 (웹에서 볼 수 있는 글만)')}</li>
            {view.scope === 'WRITE' && <li>{t('임시글 만들기·고치기, "발행 대기" 표시')}</li>}
            <li className="muted">{t('발행·삭제는 내가 설정 › AI 연결에서 허용했을 때만 할 수 있어요(기본은 꺼짐). 공개 범위만 바꾸는 일은 할 수 없어요.')}</li>
          </ul>
          {isAdmin && (
            <label className="check oauth-admin">
              <input type="checkbox" checked={adminTools} onChange={(e) => setAdminTools(e.target.checked)} />
              <span>{t('관리자 도구도 허용 (문의·신고 읽기·처리)')}</span>
            </label>
          )}
          <div className="oauth-host">
            <span className="muted small">{t('허용하면 이 주소로 돌아가요')}</span>
            <strong>{view.redirectHost}</strong>
          </div>
          <p className="muted small">{t('설정 › AI 연결에서 언제든 연결을 끊을 수 있어요.')}</p>
          <div className="oauth-actions">
            <button type="button" className="btn btn-primary btn-lg" disabled={busy} onClick={() => decide(true)}>{t('허용')}</button>
            <button type="button" className="btn btn-outline btn-lg" disabled={busy} onClick={() => decide(false)}>{t('거부')}</button>
          </div>
          <p className="muted small">{tNodes('처음 보는 앱이거나 직접 연결을 시작하지 않았다면 거부해 주세요. {0}', { 0: <Link to="/mcp">{t('AI 연결 안내')}</Link> })}</p>
        </section>
      )}
    </main>
  )
}
