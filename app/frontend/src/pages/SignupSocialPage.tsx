import { useEffect, useRef, useState, type FormEvent } from 'react'
import { NicknameStatus } from '../components/NicknameStatus'
import { SignupAgreements, type AgreementChecks } from '../components/SignupAgreements'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { setFlash } from '../lib/flash'
import { attachProfileImage, prepareSocialAvatar, socialAvatarSource } from '../lib/image'
import { useNicknameCheck } from '../lib/nickname'
import { navigate } from '../lib/router'
import { t } from '../lib/i18n'

interface Terms { termsVersion: string; termsEffectiveDate: string; privacyVersion: string; privacyEffectiveDate: string }
interface Draft {
  provider: string; prefix: string; handleBody: string; nickname: string; email: string | null; avatarUrl: string | null
  /** 소셜이 인증된 이메일을 주지 않았으면 여기서 받아 메일로 인증한다 (004 FR-028) */
  emailRequired: boolean; terms: Terms
}
interface Check { available: boolean; message: string | null; suggestion?: string | null }

/** 소셜 가입 마무리 (docs/08·09): 접두어 고정 + 본문 입력, 0.5초 뒤 중복 확인, 닉네임, 약관 동의. */
const PROVIDER_NAMES: Record<string, string> = { GITHUB: 'GitHub', GOOGLE: 'Google', KAKAO: t('카카오'), FACEBOOK: 'Facebook', NAVER: t('네이버') }

export function SignupSocialPage() {
  const { refresh } = useAuth()
  const [draft, setDraft] = useState<Draft | null>(null)
  const [expired, setExpired] = useState(false)
  const [body, setBody] = useState('')
  const [nickname, setNickname] = useState('')
  const [email, setEmail] = useState('')
  const [agreed, setAgreed] = useState<AgreementChecks>({ terms: false, privacy: false, ai: false })
  const [usePhoto, setUsePhoto] = useState(true)
  const [photoBroken, setPhotoBroken] = useState(false)
  const [handleCheck, setHandleCheck] = useState<Check | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const timers = useRef<{ h?: number }>({})
  // 가입 요청이 오류로 돌아와 다시 눌러도 사진은 한 번만 만든다
  const preparedPhoto = useRef<Promise<Blob | null> | null>(null)

  useEffect(() => {
    api<Draft>('/api/auth/signup').then((d) => {
      setDraft(d)
      setBody(d.handleBody)
      setNickname(d.nickname ?? '')
    }).catch(() => setExpired(true))
  }, [])

  useEffect(() => {
    if (!draft || !body) return setHandleCheck(null)
    clearTimeout(timers.current.h)
    timers.current.h = window.setTimeout(() => {
      api<Check>(`/api/handles/availability?handle=${encodeURIComponent(draft.prefix + body)}`).then(setHandleCheck).catch(() => setHandleCheck(null))
    }, 500)
  }, [body, draft])

  const nickCheck = useNicknameCheck(nickname)

  if (expired) {
    return (
      <main className="container narrow auth-page">
        <h1>{t('가입 시간이 지났어요')}</h1>
        <p className="muted">{t('소셜 로그인부터 다시 시작해 주세요.')}</p>
        <a className="btn btn-primary" href="/login">{t('로그인으로')}</a>
      </main>
    )
  }
  if (!draft) return <main className="container narrow"><p className="muted center">{t('불러오는 중…')}</p></main>

  const providerName = PROVIDER_NAMES[draft.provider] ?? draft.provider
  // 메일 인증 전에는 사진을 올릴 수 없어(005 FR-017) 이메일을 따로 받는 가입은 복사하지 않는다
  const photo = draft.emailRequired || photoBroken ? null : socialAvatarSource(draft.avatarUrl)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setErrors({})
    // 서버 대신 받기는 가입 대기 정보가 있을 때만 되므로 가입 요청보다 먼저 사진을 만들어 둔다
    const wantPhoto = !!photo && usePhoto
    if (wantPhoto) preparedPhoto.current ??= prepareSocialAvatar(draft.avatarUrl!)
    const blob = wantPhoto ? await preparedPhoto.current : null
    try {
      const r = await api<{ handle: string; redirect: string }>('/api/auth/signup', {
        method: 'POST', body: { handleBody: body, nickname, agreeTerms: agreed.terms, agreePrivacy: agreed.privacy, agreeAi: agreed.ai, email: draft.emailRequired ? email : undefined },
      })
      if (wantPhoto && !(blob && (await attachProfileImage(blob)))) {
        setFlash(t('소셜 사진을 가져오지 못했어요. 설정에서 직접 올릴 수 있어요.'))
      }
      await refresh()
      navigate(r.redirect || '/', { replace: true })
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'SIGNUP_EXPIRED') return setExpired(true)
        const map: Record<string, string> = {}
        err.errors.forEach((f) => (map[f.field] = f.message))
        if (err.code === 'HANDLE_TAKEN') {
          const s = (err.details as { suggestion?: string } | null)?.suggestion
          map.handleBody = (s ? t('이미 쓰는 주소예요. "{0}"는 어때요?', { 0: s }) : t('이미 쓰는 주소예요.'))
        } else if (err.code === 'NICKNAME_TAKEN') map.nickname = err.message
        else if (!err.errors.length) map.form = err.message
        setErrors(map)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="container narrow auth-page">
      <h1>{t('가입 마무리')}</h1>
      <p className="muted">{t('블로그 주소는 가입 뒤 바꿀 수 없어요.')}</p>
      <form onSubmit={submit} className="form">
        {draft.emailRequired && (
          <label className="field">
            <span>{t('이메일')}</span>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" maxLength={254} required
                   spellCheck={false} autoCapitalize="none" aria-describedby="email-help" aria-invalid={!!errors.email} />
            <small id="email-help" className={errors.email ? 'error' : 'muted'}>
              {errors.email ?? t('{0} 계정에 인증된 이메일이 없어요. 받을 수 있는 이메일을 넣으면 인증 메일을 보내요.', { 0: providerName })}
            </small>
          </label>
        )}
        <label className="field">
          <span>{t('블로그 주소')}</span>
          <div className="input-prefix">
            <span>devlog/@{draft.prefix}</span>
            <input value={body} onChange={(e) => setBody(e.target.value.toLowerCase())} maxLength={20}
                   autoComplete="off" autoCapitalize="none" autoCorrect="off" spellCheck={false} aria-describedby="handle-help" required />
          </div>
          <small id="handle-help" aria-live="polite" className={errors.handleBody || handleCheck?.available === false ? 'error' : 'muted'}>
            {errors.handleBody ?? (handleCheck == null ? t('영문 소문자·숫자·_ 3~20자') : handleCheck.available ? t('쓸 수 있는 주소예요.')
              : `${handleCheck.message}${handleCheck.suggestion ? t(' "{0}"는 어때요?', { 0: handleCheck.suggestion }) : ''}`)}
          </small>
          {handleCheck?.suggestion && !handleCheck.available && (
            <button type="button" className="btn btn-text" onClick={() => setBody(handleCheck.suggestion!)}>{t('추천 주소 쓰기')}</button>
          )}
        </label>
        <label className="field">
          <span>{t('닉네임')}</span>
          <input value={nickname} onChange={(e) => { setNickname(e.target.value); setErrors(({ nickname: _, ...rest }) => rest) }}
                 maxLength={10} required autoComplete="nickname" aria-describedby="nickname-help"
                 aria-invalid={!!errors.nickname || nickCheck.state === 'taken' || nickCheck.state === 'rules'} />
          <NicknameStatus id="nickname-help" value={nickname} check={nickCheck} error={errors.nickname} hint={t('한글·영문·숫자 2~10자')} />
        </label>
        {photo && (
          <label className="field social-photo">
            <span className="row">
              <input type="checkbox" checked={usePhoto} onChange={(e) => setUsePhoto(e.target.checked)} />
              {providerName}  {t('프로필 사진 사용')}
            </span>
            <img src={photo} alt="" width={64} height={64} className="avatar" referrerPolicy="no-referrer"
                 onError={() => setPhotoBroken(true)} />
            <small className="muted">{t('가입할 때 한 번 복사해 와요. 나중에 설정에서 바꿀 수 있어요.')}</small>
          </label>
        )}
        <SignupAgreements value={agreed} onChange={setAgreed} termsDate={draft.terms.termsEffectiveDate}
                          privacyDate={draft.terms.privacyEffectiveDate} error={errors.agreeTerms ?? errors.agreePrivacy} />
        {errors.form && <div className="banner banner-warn" role="alert">{errors.form}</div>}
        <button className="btn btn-primary btn-block" disabled={submitting || !agreed.terms || !agreed.privacy || nickCheck.state !== 'available'}>
          {submitting ? (photo && usePhoto ? t('가입하고 사진을 가져오는 중…') : t('가입하는 중…')) : t('가입하기')}
        </button>
      </form>
    </main>
  )
}
