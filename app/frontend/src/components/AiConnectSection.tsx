import { useEffect, useState, type FormEvent } from 'react'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { fullDate, relativeDate } from '../lib/format'
import { Link } from '../lib/router'
import {
  aiDiaryApi, aiPublishApi, diaryHourLabel, type AiDiarySetting, claudeCodeCommand, TOKEN_EXPIRY_DAYS, TOKEN_NAME_MAX, tokensApi, type AccessToken, type IssuedToken, type TokenScope,
} from '../lib/mcp'
import { AiConnectGuide } from './AiConnectGuide'
import { CopyCode } from './CopyCode'
import { t, tNodes } from '../lib/i18n'

/**
 * 설정 › AI 연결 (052): MCP용 개인 접근 토큰을 만들고 폐기한다.
 * 원문은 만든 직후 이 화면에서 한 번만 보여 준다. 다시 볼 수 없으니 새로 만들게 안내한다.
 * 아래의 "AI가 발행·삭제하도록 허용"(053)은 기본 꺼짐이고, 켤 때 한 번 더 묻는다.
 */
export function AiConnectSection() {
  const { me } = useAuth()
  const isAdmin = me?.member?.role === 'ADMIN'
  // 관리자 도구(문의 관리)는 관리자만, 토큰마다 따로 켠다. 기본은 꺼짐 (#378)
  const [adminTools, setAdminTools] = useState(false)
  const [tokens, setTokens] = useState<AccessToken[] | null>(null)
  const [name, setName] = useState('Claude Code')
  const [scope, setScope] = useState<TokenScope>('WRITE')
  const [days, setDays] = useState<number>(90)
  const [issued, setIssued] = useState<IssuedToken | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => { tokensApi.list().then(setTokens).catch(() => setTokens([])) }, [])
  // 안내 페이지의 "토큰 만들기"(/settings#ai)로 오면 이 항목으로 내린다
  useEffect(() => { if (tokens && location.hash === '#ai') document.getElementById('ai')?.scrollIntoView() }, [tokens])

  const create = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setMessage(null)
    try {
      const created = await tokensApi.create(name.trim(), scope, days, isAdmin && adminTools)
      setIssued(created)
      setTokens((list) => [created.token, ...(list ?? [])])
    } catch (err) {
      setMessage({ ok: false, text: err instanceof ApiError ? (err.errors[0]?.message ?? err.message) : t('토큰을 만들지 못했어요. 다시 시도해 주세요.') })
    } finally {
      setBusy(false)
    }
  }

  const revoke = async (tok: AccessToken) => {
    const ask = tok.oauth
      ? t('\'{0}\' 연결을 끊을까요? 이 토큰으로 연결한 AI는 바로 devlog를 쓸 수 없게 돼요.', { 0: tok.name })
      : t('\'{0}\' 토큰을 폐기할까요? 이 토큰으로 연결한 AI는 바로 devlog를 쓸 수 없게 돼요.', { 0: tok.name })
    if (!confirm(ask)) return
    setBusy(true)
    setMessage(null)
    try {
      await tokensApi.revoke(tok.id)
      setTokens((list) => (list ?? []).filter((x) => x.id !== tok.id))
      if (issued?.token.id === tok.id) setIssued(null)
      setMessage({ ok: true, text: t('폐기했어요.') })
    } catch {
      setMessage({ ok: false, text: t('폐기하지 못했어요. 다시 시도해 주세요.') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="settings-section ai-connect" id="ai">
      <h2>{t('AI 연결')}</h2>
      <p className="muted small">
        
        {tNodes('Claude Code·Cursor·Codex 같은 AI 도구에 토큰으로 devlog를 연결하면 "개발 일지 써 줘" 한마디로 임시글이 만들어져요. ChatGPT처럼 로그인으로 연결한 앱도 여기에 보여요. 발행은 기본으로 내가 해요. {0}', { 0: <Link to="/mcp">{t('연결 방법 자세히 보기')}</Link> })}
      </p>

      <AiConnectGuide />

      {issued && (
        <div className="banner ai-connect-issued" role="status">
          <p>{tNodes('{0} 이 화면을 벗어나면 다시 볼 수 없으니 지금 복사해 두세요.', { 0: <b>{t('\'{0}\' 토큰을 만들었어요.', { 0: issued.token.name })}</b> })}</p>
          <CopyCode label={t('새 토큰')} code={issued.secret} />
          <p className="small">{t('Claude Code라면 터미널에 이 명령을 붙여 넣으면 연결돼요.')}</p>
          <CopyCode label={t('Claude Code 연결 명령')} code={claudeCodeCommand(window.location.origin, issued.secret)} />
          <button type="button" className="btn btn-text" onClick={() => setIssued(null)}>{t('다 복사했어요')}</button>
        </div>
      )}

      <form className="ai-connect-form" onSubmit={create}>
        <label>
          <span>{t('이름')}</span>
          <input name="tokenName" autoComplete="off" value={name} maxLength={TOKEN_NAME_MAX} required onChange={(e) => setName(e.target.value)} placeholder={t('예: 회사 노트북 Claude Code')} />
        </label>
        <label>
          <span>{t('권한')}</span>
          <select value={scope} onChange={(e) => setScope(e.target.value as TokenScope)}>
            <option value="WRITE">{t('쓰기 + 읽기')}</option>
            <option value="READ">{t('읽기만')}</option>
          </select>
        </label>
        <label>
          <span>{t('만료')}</span>
          <select value={days} onChange={(e) => setDays(Number(e.target.value))}>
            {TOKEN_EXPIRY_DAYS.map((d) => <option key={d} value={d}>{d === 365 ? t('1년') : t('{0}일', { 0: d })}</option>)}
          </select>
        </label>
        {isAdmin && (
          <label className="check">
            <input type="checkbox" checked={adminTools} onChange={(e) => setAdminTools(e.target.checked)} />
            <span>{t('관리자 도구도 켜기 (문의·신고 읽기·처리)')}</span>
            <span className="muted small">{t('꼭 필요한 토큰에만 켜세요. 이 토큰이 새면 문의 내용을 읽을 수 있어요.')}</span>
          </label>
        )}
        <button type="submit" className="btn btn-primary" disabled={busy || !name.trim()}>{t('토큰 만들기')}</button>
      </form>

      {tokens && tokens.length > 0 && (
        <ul className="token-list">
          {tokens.map((tok) => (
            <li key={tok.id} className="token-row">
              <span className="token-who">
                <b>{tok.name}</b> {tok.oauth ? <span className="token-kind">{t('로그인 연결')}</span> : <code>{tok.prefix}…</code>}
                <span className="muted small">
                  {tok.scope === 'WRITE' ? t('쓰기') : t('읽기')}
                  {tok.adminTools && <>{' · '}<span className="token-kind">{t('관리자 도구')}</span></>}
                  {' · '}{tok.expired ? <span className="error">{t('만료됨')}</span> : tok.expiresAt ? t('{0}까지', { 0: fullDate(tok.expiresAt) }) : t('만료 없음')}
                  {' · '}{tok.lastUsedAt ? t('{0} 사용', { 0: relativeDate(tok.lastUsedAt) }) : t('아직 안 씀')}
                </span>
              </span>
              <button type="button" className="btn btn-text danger" disabled={busy} onClick={() => revoke(tok)}>{tok.oauth ? t('연결 끊기') : t('폐기')}</button>
            </li>
          ))}
        </ul>
      )}
      {/* 알림 영역은 늘 두고 안의 글만 바꾼다 */}
      <div role="status">{message && <p className={message.ok ? 'ok' : 'error'}>{message.text}</p>}</div>

      <AiPublishToggle />
      <AiDiaryToggle />
    </section>
  )
}

const AI_PUBLISH_WARNING = t('켜면 연결한 AI가 글을 바로 발행하거나 삭제할 수 있어요. 삭제는 웹에서 지울 때와 같아요.')

/**
 * "AI가 발행·삭제하도록 허용" (053). 기본은 꺼짐. 켜면 쓰기 권한으로 연결한 AI에 publish_post·delete_post가 열린다.
 * 이 설정은 로그인한 이 화면에서만 바꿀 수 있어서 AI가 스스로 켤 수 없다.
 */
function AiPublishToggle() {
  const [allowed, setAllowed] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { aiPublishApi.get().then((s) => setAllowed(s.allowed)).catch(() => setAllowed(null)) }, [])

  const change = async (on: boolean) => {
    if (on && !confirm(t('AI가 발행·삭제하도록 허용할까요?\n쓰기 권한으로 연결한 AI가 내 확인 없이 글을 바로 발행하거나 휴지통으로 옮길 수 있어요. 언제든 다시 끌 수 있어요.'))) return
    setBusy(true)
    setError(null)
    try {
      setAllowed((await aiPublishApi.set(on)).allowed)
    } catch {
      setError(t('설정을 바꾸지 못했어요. 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="ai-publish">
      <h3>{t('AI 발행·삭제')}</h3>
      <label>
        <input type="checkbox" checked={allowed === true} disabled={busy || allowed === null} onChange={(e) => change(e.target.checked)} />
        {' '}{t('AI가 발행·삭제하도록 허용')}
      </label>
      <p className="muted small">
        {tNodes('{0} 지운 글은 휴지통에서 30일 안에 복구할 수 있어요. 꺼 두면 AI는 임시글과 "발행 대기"까지만 만들어요.', { 0: AI_PUBLISH_WARNING })}
      </p>
      <p className="muted small ai-publish-reconnect">
        
        {t('끄면 바로 막혀요. 켠 뒤에는 AI 앱에서 devlog 연결을 다시 시작해야 발행·삭제 도구가 보여요.')}
      </p>
      <div role="status">{error && <p className="error">{error}</p>}</div>
    </div>
  )
}

/**
 * "AI 일기 쓰기" (061·071). 기본은 꺼짐. 켜면 연결한 AI에 add_note가 열리고, 매일 고른 시각(KST, 기본 자정)에 메모가 일기로 묶인다.
 * 늘 임시글로 만들고, AI 발행을 허용했으면 바로 발행한다. 끄면 아직 묶지 않은 메모도 지운다.
 */
function AiDiaryToggle() {
  const [setting, setSetting] = useState<AiDiarySetting | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [loadFailed, setLoadFailed] = useState(false)

  const load = () => {
    setLoadFailed(false)
    aiDiaryApi.get().then(setSetting).catch(() => setLoadFailed(true))
  }
  useEffect(load, [])

  const save = async (on: boolean, hour?: number) => {
    setBusy(true)
    setError(null)
    try {
      setSetting(await aiDiaryApi.set(on, hour))
    } catch {
      setError(t('설정을 바꾸지 못했어요. 다시 시도해 주세요.'))
    } finally {
      setBusy(false)
    }
  }

  const change = (on: boolean) => {
    if (!on && !confirm(t('AI 일기 쓰기를 끌까요?\n아직 일기로 묶지 않은 AI 메모도 함께 지워져요. 이미 만든 일기는 그대로 남아요.'))) return
    void save(on)
  }

  const enabled = setting?.enabled === true
  const when = diaryHourLabel(setting?.hour ?? 0)
  return (
    <div className="ai-publish ai-diary">
      <h3>{t('AI 일기')}</h3>
      <label title={t('AI를 쓴 날마다 {0}에 작업 메모를 일기로 모아요', { 0: when })}>
        <input type="checkbox" checked={enabled} disabled={busy || setting === null} onChange={(e) => change(e.target.checked)} />
        {' '}{t('AI 일기 쓰기')}
      </label>
      <label className="ai-diary-hour">
        
        {t('일기 쓰는 시각')}{' '}
        <select value={setting?.hour ?? 0} disabled={busy || !enabled} title={t('매일 이 시각(한국 시간)에 그때까지의 메모를 일기로 묶어요')}
          onChange={(e) => void save(true, Number(e.target.value))}>
          {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{diaryHourLabel(h)}</option>)}
        </select>
      </label>
      <p className="muted small">
        
        {tNodes('켜면 연결한 AI가 작업을 마칠 때마다 한두 문장 메모를 남기고, 매일 {0}(한국 시간)에 그때까지의 메모가 주제별로 묶인 {1}가 돼요. AI를 쓰지 않은 날은 만들지 않아요. 일기는 임시글로 만들고, 위에서 {2}했으면 글에 정해진 공개 범위로 바로 발행해요.', { 0: when, 1: <b>{t('일기')}</b>, 2: <b>{t('AI 발행을 허용')}</b> })}
      </p>
      <p className="muted small ai-publish-reconnect">
        
        {t('켠 뒤에는 AI 앱에서 devlog 연결을 다시 시작해야 메모 도구가 보여요. 끄면 아직 묶지 않은 메모도 지워요.')}
      </p>
      {loadFailed && (
        <p className="error" role="status">
          
          {tNodes('설정을 불러오지 못했어요. {0}', { 0: <button type="button" className="btn btn-text" title={t('AI 일기 설정을 다시 불러와요')} onClick={load}>{t('다시 시도')}</button> })}
        </p>
      )}
      <div role="status">{error && <p className="error">{error}</p>}</div>
    </div>
  )
}
