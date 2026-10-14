import { useEffect, useRef, useState, type FormEvent } from 'react'
import { NicknameStatus } from '../components/NicknameStatus'
import { PasswordRules } from '../components/PasswordRules'
import { SignupAgreements, type AgreementChecks } from '../components/SignupAgreements'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { fieldErrors } from '../lib/fieldErrors'
import { useNicknameCheck } from '../lib/nickname'
import { cleanHandleInput, passwordOk } from '../lib/password'
import { Link, navigate, useLocation } from '../lib/router'
import { t } from '../lib/i18n'

interface Terms { termsEffectiveDate: string; privacyEffectiveDate: string }
interface Check { available: boolean; message: string | null; suggestion?: string | null; code?: string | null }

const RESEND_SECONDS = 60
const normEmail = (v: string) => v.trim().toLowerCase()
const mmss = (sec: number) => `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`

/**
 * 이메일 가입 (004 US1, docs/08 §3): 이메일을 치는 동안 주소를 미리 채우고, 주소를 직접 고치면 더는 바꾸지 않는다.
 * 비밀번호는 규칙별 ✓로 보여 주고, 최종 검사는 서버가 한다.
 * 아이디(이메일)는 치는 동안 가입 여부를 확인하고, [인증]으로 받은 6자리 번호를 이 화면에서 넣어 인증한다 (spec 065).
 * 아이디 사용 가능·블로그 주소 사용 가능·이메일 인증이 모두 끝나야 가입 버튼이 켜진다.
 */
export function SignupEmailPage() {
  const { me, refresh } = useAuth()
  const { search } = useLocation()
  const redirect = search.get('redirect') ?? '/'
  const [terms, setTermsInfo] = useState<Terms | null>(null)
  const [email, setEmail] = useState('')
  const [body, setBody] = useState('')
  const [bodyTouched, setBodyTouched] = useState(false)
  const [autoFilled, setAutoFilled] = useState(false)
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [nickname, setNickname] = useState('')
  const [agreed, setAgreed] = useState<AgreementChecks>({ terms: false, privacy: false, ai: false })
  const [handleCheck, setHandleCheck] = useState<Check | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [emailTaken, setEmailTaken] = useState(false)
  const [withdrawnAccount, setWithdrawnAccount] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [codeRequired, setCodeRequired] = useState(false)
  const [emailCheck, setEmailCheck] = useState<Check | null>(null)
  const [code, setCode] = useState('')
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [expiresAt, setExpiresAt] = useState(0)
  const [resendAt, setResendAt] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const [verifiedEmail, setVerifiedEmail] = useState<string | null>(null)
  const [codeMsg, setCodeMsg] = useState<{ text: string; error: boolean } | null>(null)
  const [sending, setSending] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const timers = useRef<{ s?: number; h?: number; e?: number }>({})

  useEffect(() => {
    if (me?.authenticated) navigate('/', { replace: true })
  }, [me])

  useEffect(() => {
    api<Terms>('/api/terms/current').then(setTermsInfo).catch(() => undefined)
    api<{ emailCode?: boolean }>('/api/auth/providers').then((r) => setCodeRequired(!!r.emailCode)).catch(() => undefined)
  }, [])

  // 아이디(이메일) 확인: 형식과 가입 여부
  useEffect(() => {
    const e = normEmail(email)
    clearTimeout(timers.current.e)
    if (!e) return setEmailCheck(null)
    timers.current.e = window.setTimeout(() => {
      api<Check>(`/api/emails/availability?email=${encodeURIComponent(e)}`).then(setEmailCheck).catch(() => setEmailCheck(null))
    }, 500)
  }, [email])

  // 인증번호 남은 시간·다시 보내기 대기 시간 표시
  useEffect(() => {
    if (!sentTo || verifiedEmail) return
    const t = window.setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [sentTo, verifiedEmail])

  // 이메일 앞부분으로 주소 미리 채우기 (주소 칸을 직접 고친 뒤로는 하지 않는다)
  useEffect(() => {
    if (bodyTouched) return
    const at = email.indexOf('@')
    const local = (at < 0 ? email : email.slice(0, at)).trim()
    clearTimeout(timers.current.s)
    if (!local) {
      setBody('')
      setAutoFilled(false)
      return
    }
    timers.current.s = window.setTimeout(() => {
      api<{ handleBody: string }>(`/api/handles/suggestion?material=${encodeURIComponent(local)}`)
        .then((r) => { setBody(r.handleBody); setAutoFilled(true) })
        .catch(() => undefined)
    }, 400)
  }, [email, bodyTouched])

  useEffect(() => {
    if (!body) return setHandleCheck(null)
    clearTimeout(timers.current.h)
    timers.current.h = window.setTimeout(() => {
      api<Check>(`/api/handles/availability?handle=${encodeURIComponent(body)}`).then(setHandleCheck).catch(() => setHandleCheck(null))
    }, 500)
  }, [body])

  const nickCheck = useNicknameCheck(nickname)

  const verified = !!verifiedEmail && verifiedEmail === normEmail(email)
  const codeOpen = !!sentTo && sentTo === normEmail(email) && !verified
  const resendLeft = Math.max(0, Math.ceil((resendAt - now) / 1000))
  const expireLeft = Math.max(0, Math.ceil((expiresAt - now) / 1000))

  const sendCode = async () => {
    const target = normEmail(email)
    setSending(true)
    setCodeMsg(null)
    setEmailTaken(false)
    setWithdrawnAccount(false)
    try {
      const r = await api<{ expiresInSeconds: number }>('/api/auth/signup/email-code', { method: 'POST', body: { email: target } })
      const sentAt = Date.now()
      setSentTo(target)
      setCode('')
      setNow(sentAt)
      setExpiresAt(sentAt + r.expiresInSeconds * 1000)
      setResendAt(sentAt + RESEND_SECONDS * 1000)
      setCodeMsg({ text: t('인증번호를 보냈어요. 메일함(스팸함 포함)을 확인해 주세요.'), error: false })
    } catch (err) {
      if (err instanceof ApiError && err.code === 'EMAIL_TAKEN') setEmailTaken(true)
      else if (err instanceof ApiError && err.code === 'WITHDRAWN_ACCOUNT') setWithdrawnAccount(true)
      else setCodeMsg({ text: err instanceof ApiError ? err.message : t('인증번호를 보내지 못했어요.'), error: true })
    } finally {
      setSending(false)
    }
  }

  const confirmCode = async () => {
    setConfirming(true)
    try {
      const r = await api<{ email: string }>('/api/auth/signup/email-code/verify', { method: 'POST', body: { email: sentTo, code } })
      setVerifiedEmail(r.email)
      setCodeMsg(null)
      setErrors((prev) => { const { email: _drop, ...rest } = prev; return rest })
    } catch (err) {
      if (err instanceof ApiError && (err.code === 'CODE_EXPIRED' || err.code === 'CODE_TOO_MANY_TRIES')) setResendAt(0)
      setCodeMsg({ text: err instanceof ApiError ? err.message : t('인증하지 못했어요.'), error: true })
    } finally {
      setConfirming(false)
    }
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setErrors({})
    setEmailTaken(false)
    setWithdrawnAccount(false)
    try {
      await api('/api/auth/redirect', { method: 'POST', body: { redirect } })
      const r = await api<{ handle: string; redirect: string }>('/api/auth/signup/email', {
        method: 'POST',
        body: { email, handleBody: body, password, passwordConfirm: confirm, nickname, agreeTerms: agreed.terms, agreePrivacy: agreed.privacy, agreeAi: agreed.ai },
      })
      await refresh()
      navigate(r.redirect || '/', { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.code === 'EMAIL_TAKEN') {
        setEmailTaken(true)
      } else if (err instanceof ApiError && err.code === 'WITHDRAWN_ACCOUNT') {
        setWithdrawnAccount(true)
      } else if (err instanceof ApiError && err.code === 'HANDLE_TAKEN') {
        const s = (err.details as { suggestion?: string } | null)?.suggestion
        setErrors({ handleBody: (s ? t('이미 쓰는 주소예요. "{0}"는 어때요?', { 0: s }) : t('이미 쓰는 주소예요.')) })
      } else if (err instanceof ApiError && err.code === 'NICKNAME_TAKEN') {
        setErrors({ nickname: err.message })
      } else {
        setErrors(fieldErrors(err))
      }
    } finally {
      setSubmitting(false)
    }
  }

  const emailUsable = emailCheck?.available === true
  const ready = agreed.terms && agreed.privacy && passwordOk(password, email) && password === confirm && body.length >= 3
    && handleCheck?.available === true && emailUsable && (!codeRequired || verified) && nickCheck.state === 'available'
  const emailHelp = errors.email ?? (emailTaken || withdrawnAccount ? null
    : emailCheck == null ? t('로그인할 때 쓰는 아이디예요.')
    : emailCheck.available ? (verified ? t('인증을 마쳤어요.') : codeOpen ? t('쓸 수 있는 아이디예요. 메일로 받은 인증번호를 넣어 주세요.')
      : codeRequired ? t('쓸 수 있는 아이디예요. [인증]을 눌러 인증번호를 받아 주세요.') : t('쓸 수 있는 아이디예요.'))
    : emailCheck.message)
  const sendLabel = verified ? t('인증됨') : sending ? t('보내는 중…') : codeOpen && resendLeft > 0 ? t('다시 보내기 {0}초', { 0: resendLeft }) : codeOpen ? t('다시 보내기') : t('인증')

  return (
    <main className="container narrow auth-page">
      <h1>{t('이메일로 가입')}</h1>
      <form onSubmit={submit} className="form" noValidate>
        <div className="field">
          <span id="signup-email-label">{t('아이디(이메일)')}</span>
          <div className="input-action">
            <input id="signup-email" aria-labelledby="signup-email-label" type="email" value={email} onChange={(e) => { setEmail(e.target.value); setEmailTaken(false); setWithdrawnAccount(false) }}
                   maxLength={254} autoComplete="email" spellCheck={false} autoCapitalize="none" required aria-describedby="email-help"
                   aria-invalid={!!errors.email || emailTaken || withdrawnAccount || emailCheck?.available === false} />
            {codeRequired && (
              <button type="button" className={verified ? 'btn btn-outline ok' : 'btn btn-outline'} onClick={sendCode}
                      disabled={verified || sending || !emailUsable || (codeOpen && resendLeft > 0)}
                      title={verified ? t('이 이메일은 인증을 마쳤어요') : codeOpen ? t('인증번호를 새로 보내요. 앞서 보낸 번호는 쓸 수 없게 돼요')
                        : t('아이디를 확인하고 이 이메일로 6자리 인증번호를 보내요')}>
                {verified ? '✓ ' : ''}{sendLabel}
              </button>
            )}
          </div>
          {emailHelp && (
            <small id="email-help" aria-live="polite" className={errors.email || emailCheck?.available === false ? 'error' : verified ? 'ok' : 'muted'}>{emailHelp}</small>
          )}
          {codeOpen && (
            <div className="input-action">
              <input aria-label={t('인증번호 6자리')} value={code} inputMode="numeric" autoComplete="one-time-code" maxLength={6} spellCheck={false}
                     placeholder={t('인증번호 6자리')} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                     onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (code.length === 6) void confirmCode() } }} />
              <button type="button" className="btn btn-primary" onClick={confirmCode} disabled={confirming || code.length !== 6 || expireLeft === 0}
                      title={t('받은 인증번호가 맞는지 확인해요')}>
                {confirming ? t('확인 중…') : t('확인')}
              </button>
            </div>
          )}
          {codeOpen && expireLeft > 0 && <small className="muted">{t('{0} 안에 넣어 주세요.', { 0: mmss(expireLeft) })}</small>}
          {codeOpen && expireLeft === 0 && !codeMsg?.error && <small className="error">{t('인증번호가 만료됐어요. 다시 보내기를 눌러 주세요.')}</small>}
          {codeMsg && !verified && <small className={codeMsg.error ? 'error' : 'muted'} role={codeMsg.error ? 'alert' : undefined}>{codeMsg.text}</small>}
          {withdrawnAccount && (
            <div className="banner banner-warn" role="alert">
              
              {t('탈퇴 신청한 계정이 있어요. 로그인하면 복구할 수 있어요.')}
              <div className="banner-actions">
                <Link to="/login" className="btn btn-outline">{t('로그인')}</Link>
              </div>
            </div>
          )}
          {emailTaken && (
            <div className="banner banner-warn" role="alert">
              
              {t('이미 가입된 이메일이에요.')}
              <div className="banner-actions">
                <Link to="/login" className="btn btn-outline">{t('로그인')}</Link>
                <Link to="/forgot-password" className="btn btn-outline">{t('비밀번호 찾기')}</Link>
              </div>
            </div>
          )}
        </div>
        <label className="field">
          <span>{t('블로그 주소')}</span>
          <div className="input-prefix">
            <span>devlog/@</span>
            <input value={body} lang="en" inputMode="url" autoCapitalize="none" autoCorrect="off" spellCheck={false}
                   onChange={(e) => { setBody(cleanHandleInput(e.target.value)); setBodyTouched(true); setAutoFilled(false) }}
                   maxLength={20} autoComplete="off" aria-describedby="handle-help" required />
          </div>
          <small id="handle-help" aria-live="polite" className={errors.handleBody || handleCheck?.available === false ? 'error' : 'muted'}>
            {errors.handleBody ?? (handleCheck == null ? t('영문 소문자·숫자·_ 3~20자') : handleCheck.available ? t('쓸 수 있는 주소예요.')
              : `${handleCheck.message}${handleCheck.suggestion ? t(' "{0}"는 어때요?', { 0: handleCheck.suggestion }) : ''}`)}
          </small>
          {autoFilled && <small className="muted">{t('이메일 앞부분으로 미리 채웠어요. 이메일을 드러내고 싶지 않으면 바꿔 주세요.')}</small>}
          <small className="muted">{t('블로그 주소는 가입 후 바꿀 수 없어요.')}</small>
          {handleCheck?.suggestion && !handleCheck.available && (
            <button type="button" className="btn btn-text" onClick={() => { setBody(handleCheck.suggestion!); setBodyTouched(true) }}>{t('추천 주소 쓰기')}</button>
          )}
        </label>
        <label className="field">
          <span>{t('비밀번호')}</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} maxLength={64}
                 autoComplete="new-password" aria-describedby="pw-rules" required />
          <PasswordRules password={password} email={email} id="pw-rules" />
          {errors.password && <small className="error">{errors.password}</small>}
        </label>
        <label className="field">
          <span>{t('비밀번호 확인')}</span>
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} maxLength={64} autoComplete="new-password" required />
          {(errors.passwordConfirm || (confirm && confirm !== password)) && (
            <small className="error">{errors.passwordConfirm ?? t('비밀번호가 서로 달라요.')}</small>
          )}
        </label>
        <label className="field">
          <span>{t('닉네임')}</span>
          <input value={nickname} onChange={(e) => { setNickname(e.target.value); setErrors(({ nickname: _, ...rest }) => rest) }}
                 maxLength={10} required autoComplete="nickname" aria-describedby="nickname-help"
                 aria-invalid={!!errors.nickname || nickCheck.state === 'taken' || nickCheck.state === 'rules'} />
          <NicknameStatus id="nickname-help" value={nickname} check={nickCheck} error={errors.nickname} hint={t('한글·영문·숫자 2~10자')} />
        </label>
        <SignupAgreements value={agreed} onChange={setAgreed} termsDate={terms?.termsEffectiveDate}
                          privacyDate={terms?.privacyEffectiveDate} error={errors.agreeTerms ?? errors.agreePrivacy} />
        {errors.form && <div className="banner banner-warn" role="alert">{errors.form}</div>}
        <button className="btn btn-primary btn-block" disabled={submitting || !ready}>{submitting ? t('가입하는 중…') : t('가입하기')}</button>
        {!ready && !submitting && (
          <p className="muted small">
            {codeRequired && !verified ? t('아이디 확인과 이메일 인증을 마치면 가입할 수 있어요.') : t('빠진 항목을 채우면 가입할 수 있어요.')}
          </p>
        )}
        {!codeRequired && <p className="muted small">{t('가입하면 인증 메일을 보내요. 메일의 링크를 눌러야 글을 쓸 수 있어요.')}</p>}
      </form>
      <p className="auth-links small">{t('이미 계정이 있나요?')} <Link to="/login">{t('로그인')}</Link></p>
    </main>
  )
}
