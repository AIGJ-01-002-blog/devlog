import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api } from '../lib/api'
import { fieldErrors } from '../lib/fieldErrors'
import { SOCIAL_FIELDS, type SocialKind, type SocialLinks } from '../lib/socialLinks'
import { t } from '../lib/i18n'

const toDraft = (l: SocialLinks) => Object.fromEntries(SOCIAL_FIELDS.map((f) => [f.kind, l[f.kind] ?? ''])) as Record<SocialKind, string>

/** 설정의 소셜 정보 (spec 043, 쇼츠 채널 spec 103). 모든 칸을 한 번에 저장하고, 서버가 정리한 값(주소 → 아이디 등)으로 칸을 바꾼다. */
export function SocialLinksForm({ initial, onSaved }: { initial: SocialLinks; onSaved: (l: SocialLinks) => void }) {
  const [saved, setSavedLinks] = useState(() => toDraft(initial))
  const [draft, setDraft] = useState(saved)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const formRef = useRef<HTMLFormElement>(null)
  // 저장에 실패하면 첫 잘못된 칸으로 초점을 옮긴다
  useEffect(() => {
    formRef.current?.querySelector<HTMLInputElement>('input[aria-invalid="true"]')?.focus()
  }, [errors])
  const dirty = SOCIAL_FIELDS.some((f) => draft[f.kind] !== saved[f.kind])

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setErrors({})
    setDone(false)
    try {
      const links = await api<SocialLinks>('/api/me/social-links', { method: 'PUT', body: draft })
      const next = toDraft(links)
      setSavedLinks(next)
      setDraft(next)
      setDone(true)
      onSaved(links)
    } catch (err) {
      setErrors(fieldErrors(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="settings-section">
      <h2>{t('소셜 정보')}</h2>
      <p className="muted small">{t('블로그 머리에 링크로 보여요. 비워 두면 보이지 않아요.')} {t('쇼츠를 올리는 YouTube·Instagram·TikTok 채널을 넣으면 방문자가 블로그에서 바로 채널로 가요.')}</p>
      <form className="form" ref={formRef} onSubmit={save} noValidate>
        {SOCIAL_FIELDS.map((f) => (
          <label className="field" key={f.kind}>
            <span>{f.label}</span>
            <input name={f.kind} value={draft[f.kind]} spellCheck={false} autoCapitalize="none" autoCorrect="off" placeholder={f.placeholder} maxLength={254}
                   type={f.kind === 'email' ? 'email' : f.kind === 'homepage' ? 'url' : 'text'}
                   autoComplete={f.kind === 'email' ? 'email' : f.kind === 'homepage' ? 'url' : 'off'}
                   aria-invalid={errors[f.kind] ? true : undefined}
                   aria-describedby={errors[f.kind] ? `social-${f.kind}-error` : undefined}
                   onChange={(e) => { setDraft({ ...draft, [f.kind]: e.target.value }); setDone(false) }} />
            {errors[f.kind] && <small id={`social-${f.kind}-error`} className="error">{errors[f.kind]}</small>}
          </label>
        ))}
        {errors.form && <p className="error" role="alert">{errors.form}</p>}
        {done && <p className="ok" role="status">{t('저장했어요.')}</p>}
        <div>
          <button className="btn btn-primary" disabled={busy || !dirty}>{busy ? t('저장하는 중…') : t('저장')}</button>
        </div>
      </form>
    </section>
  )
}
