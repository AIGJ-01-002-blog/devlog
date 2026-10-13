import { useEffect, useRef, useState, type ChangeEvent, type FormEvent, type ReactNode } from 'react'
import { ExportSection } from '../components/ExportSection'
import { AiConnectSection } from '../components/AiConnectSection'
import { Avatar } from '../components/Avatar'
import { ImageCropper } from '../components/ImageCropper'
import { PasswordRules } from '../components/PasswordRules'
import { SocialLinksForm } from '../components/SocialLinksForm'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { fieldErrors } from '../lib/fieldErrors'
import { friendsApi, lastActiveLabel } from '../lib/friends'
import { clock, fullDate, monthDay } from '../lib/format'
import { checkSourceFile, decodeFile, renderSquare, uploadProfileImage, type Crop } from '../lib/image'
import { passwordOk } from '../lib/password'
import { MUTABLE_TYPES, notificationsApi, type MutableType } from '../lib/notifications'
import { formatBytes, storageUsage, type StorageUsage } from '../lib/postImages'
import { Link } from '../lib/router'
import { NavIcon, type IconName } from '../components/NavIcons'
import { discordApi, isDiscordWebhookUrl, type DiscordStatus } from '../lib/discord'
import { LINK_POLL_MS, linkTimeLeft, telegramApi, type TelegramLink, type TelegramStatus } from '../lib/telegram'
import type { FriendOverview, FriendPerson, Visibility } from '../lib/types'
import { DEFAULT_VISIBILITY_CHANGED } from '../lib/visibility'
import type { SocialLinks } from '../lib/socialLinks'
import { t, tNodes } from '../lib/i18n'

interface Settings {
  handle: string
  nickname: string
  nicknameNextChangeableAt: string | null
  bio: string | null
  profileImageUrl: string | null
  email: string | null
  emailVerified: boolean
  provider: string
  hasPassword: boolean
  previousLogin: { at: string | null; provider: string }
  defaultVisibility: Visibility
  lastActiveVisible: boolean
  followListPublic: boolean
  aiAgreed: boolean
  socialLinks: SocialLinks
}

interface Profile { nickname: string; nicknameNextChangeableAt: string | null; bio: string | null; profileImageUrl: string | null }

const PROVIDER_NAMES: Record<string, string> = { GITHUB: 'GitHub', GOOGLE: 'Google', KAKAO: t('카카오'), FACEBOOK: 'Facebook', NAVER: t('네이버'), LOCAL: t('이메일') }
const BIO_MAX = 200
const BIO_LINES = 4

/** 서버(BioPolicy)와 같은 정리: 앞뒤·줄 끝 공백 제거, 연속 빈 줄은 하나로. 글자 수·줄 수는 정리한 뒤 센다. */
export const normalizeBio = (s: string) => s.replace(/\r\n?/g, '\n').normalize('NFC').trim().replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n')

/** 소개 글자 수: 서버와 같이 코드 포인트로 센다(이모지 하나 = 1자). */
const bioLength = (s: string) => Array.from(s).length

/** 내 설정 탭 (063). 주소 뒤 #이름으로 바로 열린다(/settings#ai). 예전 항목 주소(#telegram)는 그 항목이 든 탭으로 간다. */
export const SETTINGS_TABS = [
  { id: 'profile', label: t('프로필'), hint: t('사진·닉네임·소개·소셜 정보'), icon: 'user' },
  { id: 'account', label: t('계정'), hint: t('로그인·공개 범위·비밀번호·탈퇴'), icon: 'lock' },
  { id: 'notifications', label: t('알림'), hint: t('받을 알림·텔레그램'), icon: 'bell' },
  { id: 'friends', label: t('친구'), hint: t('친구 요청과 친구 목록'), icon: 'users' },
  { id: 'ai', label: t('AI 연결'), hint: t('AI 도구에 쓸 토큰'), icon: 'ai' },
  { id: 'export', label: t('내보내기'), hint: t('내 글을 Markdown으로 받기'), icon: 'download' },
] as const satisfies readonly { id: string; label: string; hint: string; icon: IconName }[]

export type SettingsTab = (typeof SETTINGS_TABS)[number]['id']

const HASH_ALIASES: Record<string, SettingsTab> = { telegram: 'notifications', discord: 'notifications', password: 'account', social: 'profile' }

export function tabFromHash(hash: string): SettingsTab {
  let key: string
  // 손으로 고친 주소(#%E0 같은 잘못된 인코딩)여도 화면이 깨지지 않게 프로필로 연다
  try { key = decodeURIComponent(hash.replace(/^#/, '')) } catch { return 'profile' }
  const found = SETTINGS_TABS.find((t) => t.id === key)
  return found ? found.id : HASH_ALIASES[key] ?? 'profile'
}

/** 내 설정 (005·063): 옆 탭 목록에서 고른 항목 하나만 보인다. 휴대폰에서는 탭 목록이 위에서 옆으로 밀린다. */
export function SettingsPage() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [error, setError] = useState(false)
  const [tab, setTab] = useState<SettingsTab>(() => tabFromHash(location.hash))

  useEffect(() => {
    api<Settings>('/api/me/settings').then(setSettings).catch(() => setError(true))
  }, [])

  // 다른 화면의 "설정에서 토큰 만들기"(/settings#ai) 같은 링크와 뒤로 가기를 따른다
  useEffect(() => {
    const sync = () => setTab(tabFromHash(location.hash))
    window.addEventListener('hashchange', sync)
    window.addEventListener('popstate', sync)
    return () => {
      window.removeEventListener('hashchange', sync)
      window.removeEventListener('popstate', sync)
    }
  }, [])

  const pick = (id: SettingsTab) => {
    if (id === tab) return
    history.replaceState(null, '', `/settings#${id}`)
    setTab(id)
  }

  const current = SETTINGS_TABS.find((t) => t.id === tab)!
  let body: ReactNode = null
  if (error) body = <p className="error center" role="alert">{t('설정을 불러오지 못했어요. 새로고침해 주세요.')}</p>
  else if (!settings) body = <p className="muted center">{t('불러오는 중…')}</p>
  else if (tab === 'profile') body = <>
    <ProfileSection settings={settings} onSaved={(p) => setSettings({ ...settings, ...p })} />
    <SocialLinksForm initial={settings.socialLinks} onSaved={(l) => setSettings({ ...settings, socialLinks: l })} />
  </>
  else if (tab === 'account') body = <>
    <AccountSection settings={settings} onChange={setSettings} />
    {settings.hasPassword && <PasswordSection />}
    <section className="settings-section withdraw-link">
      <h2>{t('회원 탈퇴')}</h2>
      <p className="muted small">{t('탈퇴를 신청해도 30일 안에 다시 로그인하면 모두 복구할 수 있어요.')}</p>
      <Link to="/settings/withdraw" className="btn btn-text danger">{t('회원 탈퇴')}</Link>
    </section>
  </>
  else if (tab === 'notifications') body = <><NotificationsSection /><TelegramSection /><DiscordSection /></>
  else if (tab === 'friends') body = <FriendsSection />
  else if (tab === 'ai') body = <AiConnectSection />
  else body = <ExportSection />

  return (
    <main className="container settings-page">
      <header className="settings-head">
        <h1 className="page-title">{t('내 설정')}</h1>
        <p className="muted">{t('프로필과 계정, 알림, AI 연결을 한곳에서 바꿔요.')}</p>
      </header>
      <div className="settings-layout">
        <nav className="settings-nav" aria-label={t('설정 항목')}>
          {SETTINGS_TABS.map((t) => (
            <a key={t.id} href={`/settings#${t.id}`} aria-current={t.id === tab ? 'page' : undefined} data-tip={t.hint}
               onClick={(e) => { e.preventDefault(); pick(t.id) }}>
              <NavIcon name={t.icon} /><span>{t.label}</span>
            </a>
          ))}
        </nav>
        <div className="settings-panel" aria-label={current.label} role="region">
          {body}
        </div>
      </div>
    </main>
  )
}

type PendingImage = { kind: 'keep' } | { kind: 'default' } | { kind: 'new'; id: number; previewUrl: string }

function ProfileSection({ settings, onSaved }: { settings: Settings; onSaved: (p: Profile) => void }) {
  const { me, refresh } = useAuth()
  const [nickname, setNickname] = useState(settings.nickname)
  const [bio, setBio] = useState(settings.bio ?? '')
  const [image, setImage] = useState<PendingImage>({ kind: 'keep' })
  const [source, setSource] = useState<ImageBitmap | null>(null)
  const [uploading, setUploading] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const nicknameLocked = settings.nicknameNextChangeableAt != null
  const cleanBio = normalizeBio(bio)
  const bioCount = bioLength(cleanBio)
  const bioLineCount = cleanBio ? cleanBio.split('\n').length : 0
  const shownImage = image.kind === 'new' ? image.previewUrl : image.kind === 'default' ? null : settings.profileImageUrl
  const dirty = nickname !== settings.nickname || bio !== (settings.bio ?? '') || image.kind !== 'keep'

  useEffect(() => () => { if (image.kind === 'new') URL.revokeObjectURL(image.previewUrl) }, [image])

  const pick = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    const problem = checkSourceFile(file)
    if (problem) return setErrors({ profileImageId: problem })
    try {
      setErrors({})
      setSource(await decodeFile(file))
    } catch {
      setErrors({ profileImageId: t('사진을 열 수 없어요. 다른 사진을 골라 주세요.') })
    }
  }

  const apply = async (crop: Crop) => {
    if (!source) return
    setUploading(true)
    try {
      const blob = await renderSquare(source, crop)
      const up = await uploadProfileImage(blob)
      setImage({ kind: 'new', id: up.id, previewUrl: URL.createObjectURL(blob) })
      setSource(null)
      setSaved(false)
    } catch (err) {
      setErrors({ profileImageId: err instanceof ApiError ? err.message : t('사진을 올리지 못했어요. 다시 시도해 주세요.') })
    } finally {
      setUploading(false)
    }
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setErrors({})
    setSaved(false)
    const body: Record<string, unknown> = {}
    if (nickname !== settings.nickname) body.nickname = nickname
    if (bio !== (settings.bio ?? '')) body.bio = bio
    if (image.kind === 'new') body.profileImageId = image.id
    if (image.kind === 'default') body.profileImageId = null
    try {
      const p = await api<Profile>('/api/me/profile', { method: 'PATCH', body })
      onSaved(p)
      setNickname(p.nickname)
      setBio(p.bio ?? '')
      setImage({ kind: 'keep' })
      setSaved(true)
      await refresh()
    } catch (err) {
      const map = fieldErrors(err)
      const next = err instanceof ApiError ? (err.details as { nextChangeableAt?: string } | null)?.nextChangeableAt : undefined
      if (next && map.nickname) map.nickname = t('{0} 다음 변경 가능일: {1}', { 0: map.nickname, 1: monthDay(next) })
      setErrors(map)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="settings-section">
      <h2>{t('프로필')}</h2>
      <form className="form" onSubmit={save}>
        <div className="profile-photo">
          <Avatar src={shownImage} name={nickname || settings.nickname} seed={settings.handle} size={96} />
          <div className="profile-photo-actions">
            {me?.emailVerified === false
              ? <p className="small muted">{t('이메일 인증을 마치면 사진을 올릴 수 있어요.')}</p>
              : <button type="button" className="btn btn-outline" onClick={() => fileInput.current?.click()} disabled={uploading}>{t('이미지 변경')}</button>}
            {shownImage && <button type="button" className="btn btn-text" onClick={() => { setImage({ kind: 'default' }); setSaved(false) }}>{t('기본 이미지로')}</button>}
            <input ref={fileInput} type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden onChange={pick} />
          </div>
        </div>
        {source && <ImageCropper image={source} busy={uploading} onApply={apply} onCancel={() => setSource(null)} />}
        {errors.profileImageId && <p className="error small" role="alert">{errors.profileImageId}</p>}
        {image.kind === 'new' && <p className="small muted">{t('[저장]을 눌러야 프로필 사진이 바뀌어요.')}</p>}

        <label className="field">
          <span>{t('닉네임')}</span>
          <input value={nickname} onChange={(e) => setNickname(e.target.value)} maxLength={10} disabled={nicknameLocked}
                 autoComplete="nickname" aria-describedby="nickname-help" aria-invalid={!!errors.nickname} />
          <small id="nickname-help" className={errors.nickname ? 'error' : 'muted'}>
            {errors.nickname ?? (nicknameLocked
              ? t('다음 변경 가능일: {0}', { 0: monthDay(settings.nicknameNextChangeableAt!) })
              : t('한 번 바꾸면 30일 동안 다시 바꿀 수 없어요.'))}
          </small>
        </label>

        <label className="field">
          <span>{t('소개')}</span>
          <textarea value={bio} onChange={(e) => setBio(e.target.value)} rows={4} aria-describedby="bio-help" />
          <small id="bio-help" className={errors.bio || bioCount > BIO_MAX || bioLineCount > BIO_LINES ? 'error' : 'muted'}>
            {errors.bio ?? (bioLineCount > BIO_LINES ? t('소개는 4줄까지 쓸 수 있어요.') : `${bioCount} / ${BIO_MAX}`)}
          </small>
        </label>

        <div className="field">
          <span>{t('블로그 주소')}</span>
          <p className="readonly">@{settings.handle} <span className="muted small">{t('변경할 수 없어요')}</span></p>
        </div>

        {errors.form && <p className="error" role="alert">{errors.form}</p>}
        {saved && <p className="ok" role="status">{t('저장했어요.')}</p>}
        <div>
          <button className="btn btn-primary" disabled={saving || uploading || !dirty || bioCount > BIO_MAX || bioLineCount > BIO_LINES}>
            {saving ? t('저장하는 중…') : t('저장')}
          </button>
        </div>
      </form>
    </section>
  )
}

function AccountSection({ settings, onChange }: { settings: Settings; onChange: (s: Settings) => void }) {
  const { refresh } = useAuth()
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const prev = settings.previousLogin

  const changeVisibility = async (v: Visibility) => {
    if (v === settings.defaultVisibility) return
    const before = settings
    onChange({ ...settings, defaultVisibility: v }) // 바로 바꿔 보이고, 실패하면 되돌린다
    try {
      await api('/api/me/settings', { method: 'PATCH', body: { defaultVisibility: v } })
      setMessage({ ok: true, text: DEFAULT_VISIBILITY_CHANGED[v] })
      await refresh()
    } catch {
      onChange(before)
      setMessage({ ok: false, text: t('바꾸지 못했어요. 다시 시도해 주세요.') })
    }
  }

  const changeLastActive = async (visible: boolean) => {
    const before = settings
    onChange({ ...settings, lastActiveVisible: visible })
    try {
      await api('/api/me/settings', { method: 'PATCH', body: { lastActiveVisible: visible } })
      setMessage({ ok: true, text: visible ? t('친구에게 최근 활동을 보여요.') : t('이제 친구에게 최근 활동이 보이지 않고, 나도 친구들의 최근 활동을 볼 수 없어요.') })
    } catch {
      onChange(before)
      setMessage({ ok: false, text: t('바꾸지 못했어요. 다시 시도해 주세요.') })
    }
  }

  const [savingFollowList, setSavingFollowList] = useState(false)
  const changeFollowList = async (open: boolean) => {
    const before = settings
    onChange({ ...settings, followListPublic: open })
    setSavingFollowList(true) // 저장이 끝나기 전에 다시 바꿔 요청 순서가 뒤집히지 않게
    try {
      await api('/api/me/settings', { method: 'PATCH', body: { followListPublic: open } })
      setMessage({ ok: true, text: open ? t('이제 누구나 내 팔로워·팔로잉 목록을 볼 수 있어요.') : t('이제 다른 사람에게는 "비공개 계정입니다"로 보여요. 수는 그대로 보여요.') })
    } catch {
      onChange(before)
      setMessage({ ok: false, text: t('바꾸지 못했어요. 다시 시도해 주세요.') })
    } finally {
      setSavingFollowList(false)
    }
  }

  const withdrawAi = async () => {
    if (!window.confirm(t('AI 기능 동의를 철회할까요? 다음에 AI 기능을 쓰려면 다시 동의해야 해요.'))) return
    try {
      await api('/api/me/agreements/ai', { method: 'DELETE' })
      onChange({ ...settings, aiAgreed: false })
      setMessage({ ok: true, text: t('AI 기능 동의를 철회했어요.') })
    } catch {
      setMessage({ ok: false, text: t('철회하지 못했어요. 다시 시도해 주세요.') })
    }
  }

  return (
    <section className="settings-section">
      <h2>{t('계정')}</h2>
      <dl className="settings-list">
        <dt>{t('이메일')}</dt>
        <dd>{settings.email ?? t('없음')} <span className="muted small">{t('변경할 수 없어요')}</span></dd>
        <dt>{t('로그인 수단')}</dt>
        <dd>{PROVIDER_NAMES[settings.provider] ?? settings.provider}</dd>
        <dt>{t('직전 로그인')}</dt>
        <dd>{prev.at ? `${fullDate(prev.at)} ${clock(prev.at)}, ${PROVIDER_NAMES[prev.provider] ?? prev.provider}` : t('첫 로그인')}</dd>
        <dt id="default-visibility">{t('새 글 기본 공개 범위')}</dt>
        <dd role="radiogroup" aria-labelledby="default-visibility" className="row">
          <label><input type="radio" name="defaultVisibility" checked={settings.defaultVisibility === 'PUBLIC'} onChange={() => changeVisibility('PUBLIC')} />  {t('전체 공개')}</label>
          <label><input type="radio" name="defaultVisibility" checked={settings.defaultVisibility === 'FRIENDS'} onChange={() => changeVisibility('FRIENDS')} />  {t('친구에게만')}</label>
          <label><input type="radio" name="defaultVisibility" checked={settings.defaultVisibility === 'PRIVATE'} onChange={() => changeVisibility('PRIVATE')} />  {t('나만 보기')}</label>
        </dd>
        <dt>{t('사진 저장 공간')}</dt>
        <dd><StorageMeter /></dd>
        <dt>{t('최근 활동')}</dt>
        <dd>
          <label><input type="checkbox" checked={settings.lastActiveVisible} onChange={(e) => changeLastActive(e.target.checked)} />  {t('최근 활동을 친구에게 보이기')}</label>
          <span className="muted small">  {t('끄면 나도 친구들의 최근 활동을 볼 수 없어요.')}</span>
        </dd>
        <dt>{t('팔로워·팔로잉 목록')}</dt>
        <dd>
          <label title={t('끄면 나와 관리자만 목록을 보고, 다른 사람에게는 \'비공개 계정입니다\'로 보여요')}>
            <input type="checkbox" checked={settings.followListPublic} disabled={savingFollowList} onChange={(e) => changeFollowList(e.target.checked)} />  {t('다른 사람에게 공개')}
          </label>
          <span className="muted small">  {t('끄면 다른 사람에게는 "비공개 계정입니다"로 보여요. 팔로워·팔로잉 수는 그대로 보여요.')}</span>
        </dd>
        {settings.aiAgreed && (
          <>
            <dt>{t('AI 기능 동의')}</dt>
            <dd><button type="button" className="btn btn-text" onClick={withdrawAi}>{t('동의 철회')}</button></dd>
          </>
        )}
        <dt>{t('약관')}</dt>
        <dd><a href="/terms" target="_blank" rel="noopener">{t('이용약관')}</a> · <a href="/privacy" target="_blank" rel="noopener">{t('개인정보 처리방침')}</a></dd>
      </dl>
      {message && <p className={message.ok ? 'ok' : 'error'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

/** 비밀번호 변경 (004 US5): 이메일 가입자만. 바꾸면 다른 기기는 로그아웃되고 알림 메일이 간다. */
function PasswordSection() {
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [done, setDone] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setErrors({})
    setDone(false)
    try {
      await api('/api/me/password', { method: 'PUT', body: { currentPassword: current, password, passwordConfirm: confirm } })
      setDone(true)
      setCurrent('')
      setPassword('')
      setConfirm('')
    } catch (err) {
      setErrors(fieldErrors(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="settings-section">
      <h2>{t('비밀번호')}</h2>
      <form className="form" onSubmit={submit}>
        <label className="field">
          <span>{t('현재 비밀번호')}</span>
          <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required
                 aria-invalid={!!errors.currentPassword} aria-describedby={errors.currentPassword ? 'current-pw-error' : undefined} />
          {errors.currentPassword && <small id="current-pw-error" className="error" role="alert">{errors.currentPassword}</small>}
        </label>
        <label className="field">
          <span>{t('새 비밀번호')}</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" maxLength={64} required
                 aria-invalid={!!errors.password} aria-describedby={errors.password ? 'new-pw-error' : undefined} />
          <PasswordRules password={password} />
          {errors.password && <small id="new-pw-error" className="error" role="alert">{errors.password}</small>}
        </label>
        <label className="field">
          <span>{t('새 비밀번호 확인')}</span>
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" maxLength={64} required
                 aria-invalid={!!errors.passwordConfirm || (!!confirm && confirm !== password)}
                 aria-describedby={errors.passwordConfirm || (confirm && confirm !== password) ? 'confirm-pw-error' : undefined} />
          {(errors.passwordConfirm || (confirm && confirm !== password)) && (
            <small id="confirm-pw-error" className="error" role={errors.passwordConfirm ? 'alert' : undefined}>{errors.passwordConfirm ?? t('비밀번호가 서로 달라요.')}</small>
          )}
        </label>
        {errors.form && <p className="error" role="alert">{errors.form}</p>}
        {done && <p className="ok" role="status">{t('비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요.')}</p>}
        <div><button className="btn btn-primary" disabled={submitting || !current || !passwordOk(password) || password !== confirm}>
          {submitting ? t('변경하는 중…') : t('비밀번호 변경')}
        </button></div>
      </form>
    </section>
  )
}

/** 친구 (008 US1·US2): 받은 요청 수락·거절, 친구 목록과 최근 활동, 보낸 요청 취소. 수락하면 요청한 사람에게 알린다(015 A-1). 거절·취소·끊기는 알리지 않는다. */
/** 알림 끄기 (015 US5). 바로 저장하고, 실패하면 되돌린다. 운영 알림은 목록에 없다(끌 수 없음). */
function NotificationsSection() {
  const [muted, setMuted] = useState<MutableType[] | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    notificationsApi.settings().then((s) => setMuted(s.muted)).catch(() => setMessage({ ok: false, text: t('알림 설정을 불러오지 못했어요.') }))
  }, [])

  useEffect(() => {
    if (muted && location.hash === '#notifications') document.getElementById('notifications')?.scrollIntoView()
  }, [muted])

  const toggle = async (type: MutableType, on: boolean) => {
    if (!muted) return
    const before = muted
    const next = on ? muted.filter((t) => t !== type) : [...muted, type]
    setMuted(next)
    try {
      setMuted((await notificationsApi.saveSettings(next)).muted)
      setMessage({ ok: true, text: t('저장했어요.') })
    } catch {
      setMuted(before)
      setMessage({ ok: false, text: t('바꾸지 못했어요. 다시 시도해 주세요.') })
    }
  }

  return (
    <section className="settings-section" id="notifications">
      <h2>{t('알림')}</h2>
      {muted && (
        <ul className="notification-settings">
          {MUTABLE_TYPES.map(({ type, label }) => (
            <li key={type}>
              <label><input type="checkbox" checked={!muted.includes(type)} onChange={(e) => toggle(type, e.target.checked)} /> {label}</label>
            </li>
          ))}
        </ul>
      )}
      <p className="muted small">{t('운영 알림(신고 결과·숨김)은 끌 수 없어요. 끈 알림은 그동안 쌓이지 않아요.')}</p>
      {message && <p className={message.ok ? 'ok' : 'error'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

/**
 * 텔레그램 (023): 1회용 주소로 연결하고, 새 알림 받기를 켜고 끈다. 연결하면 봇에게 보낸 메모가 임시글이 된다.
 * 서버에 봇이 없으면(available=false) 항목을 숨긴다. 주소를 연 뒤에는 연결될 때까지 상태를 다시 읽는다.
 */
function TelegramSection() {
  const [status, setStatus] = useState<TelegramStatus | null>(null)
  const [link, setLink] = useState<TelegramLink | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => { telegramApi.status().then(setStatus).catch(() => setStatus(null)) }, [])

  // 연결 주소가 살아 있는 동안만 상태를 다시 읽는다
  useEffect(() => {
    if (!link) return
    const timer = window.setInterval(async () => {
      setNow(Date.now())
      if (!linkTimeLeft(link.expiresAt)) { setLink(null); return }
      try {
        const s = await telegramApi.status()
        if (s.linked) { setStatus(s); setLink(null); setMessage({ ok: true, text: t('텔레그램과 연결했어요.') }) }
      } catch { /* 다음 차례에 다시 읽는다 */ }
    }, LINK_POLL_MS)
    return () => window.clearInterval(timer)
  }, [link])

  if (!status?.available) return null

  const run = async (fn: () => Promise<void>, fail: string) => {
    setBusy(true)
    setMessage(null)
    try { await fn() } catch { setMessage({ ok: false, text: fail }) } finally { setBusy(false) }
  }

  const connect = () => run(async () => {
    const l = await telegramApi.link()
    setNow(Date.now())
    setLink(l)
  }, t('연결 주소를 만들지 못했어요. 다시 시도해 주세요.'))

  const toggle = (on: boolean) => run(async () => {
    setStatus(await telegramApi.setNotifications(on))
    setMessage({ ok: true, text: t('저장했어요.') })
  }, t('바꾸지 못했어요. 다시 시도해 주세요.'))

  const disconnect = () => {
    if (!confirm(t('텔레그램 연결을 끊을까요? 새 알림을 텔레그램으로 받지 못하게 돼요.'))) return
    void run(async () => {
      await telegramApi.unlink()
      setStatus({ ...status, linked: false, linkedAt: null })
      setMessage({ ok: true, text: t('연결을 끊었어요.') })
    }, t('연결을 끊지 못했어요. 다시 시도해 주세요.'))
  }

  const left = link ? linkTimeLeft(link.expiresAt, now) : null

  return (
    <section className="settings-section" id="telegram">
      <h2>{t('텔레그램')}</h2>
      {status.linked ? (
        <>
          <p>
            {status.botUsername ? t('@{0}과 연결돼 있어요', { 0: status.botUsername }) : t('봇과 연결돼 있어요')}
            {status.linkedAt && <span className="muted small"> {tNodes('· {0}부터', { 0: fullDate(status.linkedAt) })}</span>}
          </p>
          <label><input type="checkbox" checked={status.notifications} disabled={busy} onChange={(e) => toggle(e.target.checked)} />  {t('새 알림을 텔레그램으로 받기')}</label>
          <p className="muted small">{t('봇에게 보낸 메모는 임시글로 저장돼요. AI 사용에 동의했으면 다듬어서 저장해요.')}</p>
          <button type="button" className="btn btn-text danger" disabled={busy} onClick={disconnect}>{t('연결 끊기')}</button>
        </>
      ) : (
        <>
          <p className="muted small">{t('연결하면 새 알림을 텔레그램으로 받고, 봇에게 보낸 메모를 임시글로 저장할 수 있어요.')}</p>
          {link && left ? (
            <p>
              <a className="btn btn-primary" href={link.url} target="_blank" rel="noopener noreferrer">{t('텔레그램 열기')}</a>{' '}
              <span className="muted small">{tNodes('{0} 열면 자동으로 연결돼요.', { 0: left })}</span>
            </p>
          ) : (
            <button type="button" className="btn" disabled={busy} onClick={connect}>{link ? t('주소 다시 만들기') : t('연결하기')}</button>
          )}
        </>
      )}
      {message && <p className={message.ok ? 'ok' : 'error'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

function DiscordSection() {
  const [status, setStatus] = useState<DiscordStatus | null>(null)
  const [url, setUrl] = useState('')
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => { discordApi.status().then(setStatus).catch(() => setStatus(null)) }, [])

  if (!status?.available) return null

  const run = async (fn: () => Promise<void>, fail: string) => {
    setBusy(true)
    setMessage(null)
    try { await fn() } catch (e) { setMessage({ ok: false, text: e instanceof ApiError ? e.message : fail }) } finally { setBusy(false) }
  }

  const urlOk = isDiscordWebhookUrl(url)
  const connect = (e: FormEvent) => {
    e.preventDefault()
    if (!urlOk) return
    void run(async () => {
      setStatus(await discordApi.connect(url))
      setUrl('')
      setEditing(false)
      setMessage({ ok: true, text: t('디스코드와 연결했어요. 채널에 첫 메시지를 보냈어요.') })
    }, t('연결하지 못했어요. 다시 시도해 주세요.'))
  }

  const test = () => run(async () => {
    setStatus(await discordApi.test())
    setMessage({ ok: true, text: t('시험 메시지를 보냈어요. 디스코드 채널을 확인해 보세요.') })
  }, t('보내지 못했어요. 다시 시도해 주세요.'))

  const toggle = (on: boolean) => run(async () => {
    setStatus(await discordApi.setNotifications(on))
    setMessage({ ok: true, text: t('저장했어요.') })
  }, t('바꾸지 못했어요. 다시 시도해 주세요.'))

  const disconnect = () => {
    if (!confirm(t('디스코드 연결을 끊을까요? 새 알림을 디스코드로 받지 못하게 돼요.'))) return
    void run(async () => {
      await discordApi.unlink()
      setStatus({ ...status, linked: false, webhookName: null, linkedAt: null })
      setMessage({ ok: true, text: t('연결을 끊었어요.') })
    }, t('연결을 끊지 못했어요. 다시 시도해 주세요.'))
  }

  const form = (
    <form className="form" onSubmit={connect}>
      <label className="field">
        <span>{t('웹훅 주소')}</span>
        <input type="url" inputMode="url" value={url} onChange={(e) => setUrl(e.target.value)} autoComplete="off" spellCheck={false}
               placeholder="https://discord.com/api/webhooks/…" aria-invalid={!!url && !urlOk}
               aria-describedby={url && !urlOk ? 'discord-url-error' : 'discord-url-help'} />
        <small id="discord-url-help" className="muted">{t('디스코드 채널 설정 › 연동 › 웹후크에서 새 웹후크를 만들고 [웹후크 URL 복사]를 눌러 붙여 넣어 주세요.')}</small>
        {url && !urlOk && <small id="discord-url-error" className="error">{t('디스코드 웹훅 주소 꼴이 아니에요.')}</small>}
      </label>
      <div className="row">
        <button className="btn btn-primary" disabled={busy || !urlOk} data-tip={t('주소를 확인하고 이 채널로 알림을 보내요')}>
          {busy ? t('확인하는 중…') : status.linked ? t('이 주소로 바꾸기') : t('연결하기')}
        </button>
        {editing && <button type="button" className="btn btn-text" disabled={busy} onClick={() => { setEditing(false); setUrl('') }}>{t('취소')}</button>}
      </div>
    </form>
  )

  return (
    <section className="settings-section" id="discord">
      <h2>{t('디스코드')}</h2>
      {status.linked ? (
        <>
          <p>
            {status.webhookName ? t('웹훅 「{0}」과 연결돼 있어요', { 0: status.webhookName }) : t('웹훅과 연결돼 있어요')}
            {status.linkedAt && <span className="muted small"> {tNodes('· {0}부터', { 0: fullDate(status.linkedAt) })}</span>}
          </p>
          <label><input type="checkbox" checked={status.notifications} disabled={busy} onChange={(e) => toggle(e.target.checked)} />  {t('새 알림을 디스코드로 받기')}</label>
          {editing ? form : (
            <div className="row">
              <button type="button" className="btn" disabled={busy} onClick={test} data-tip={t('연결된 채널로 시험 메시지를 하나 보내요')}>{t('시험 보내기')}</button>
              <button type="button" className="btn btn-text" disabled={busy} onClick={() => { setEditing(true); setMessage(null) }}
                      data-tip={t('다른 채널의 웹훅 주소로 바꿔요')}>{t('주소 바꾸기')}</button>
              <button type="button" className="btn btn-text danger" disabled={busy} onClick={disconnect}
                      data-tip={t('더는 디스코드로 알림을 보내지 않아요')}>{t('연결 끊기')}</button>
            </div>
          )}
        </>
      ) : (
        <>
          <p className="muted small">{t('내 디스코드 서버의 채널로 새 알림(댓글·좋아요·팔로우 등)을 받아요. 웹훅 주소는 비밀번호처럼 다뤄 주세요.')}</p>
          {form}
        </>
      )}
      {message && <p className={message.ok ? 'ok' : 'error'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

function FriendsSection() {
  const [data, setData] = useState<FriendOverview | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => { friendsApi.overview().then(setData).catch(() => setMessage({ ok: false, text: t('친구 목록을 불러오지 못했어요.') })) }, [])

  const act = async (handle: string, fn: () => Promise<unknown>, text: string) => {
    setBusy(handle)
    try {
      await fn()
      setData(await friendsApi.overview())
      setMessage({ ok: true, text })
    } catch (e) {
      setMessage({ ok: false, text: e instanceof ApiError ? e.message : t('처리하지 못했어요. 다시 시도해 주세요.') })
    } finally {
      setBusy(null)
    }
  }

  if (!data) return message ? <section className="settings-section"><h2>{t('친구')}</h2><p className="error" role="alert">{message.text}</p></section> : null
  const row = (p: FriendPerson, actions: ReactNode, extra?: string | null) => (
    <li key={p.handle} className="friend-row">
      <Link to={`/@${p.handle}`} className="friend-who">
        <Avatar src={p.profileImageUrl} name={p.nickname} seed={p.handle} size={32} />
        <span><b>{p.nickname}</b> <span className="muted small nowrap">@{p.handle}</span></span>
      </Link>
      {extra && <span className="muted small">{extra}</span>}
      <span className="friend-row-actions">{actions}</span>
    </li>
  )
  return (
    <section className="settings-section">
      <h2>{t('친구')}</h2>
      {data.received.length > 0 && (
        <>
          <h3>{tNodes('받은 친구 요청 {0}', { 0: data.received.length })}</h3>
          <ul className="friend-list">
            {data.received.map((p) => row(p, <>
              <button type="button" className="btn btn-primary" disabled={busy === p.handle} data-tip={t('친구가 되고 상대에게 수락 알림이 가요')}
                      onClick={() => act(p.handle, () => friendsApi.accept(p.handle), t('{0}님과 친구가 됐어요.', { 0: p.nickname }))}>{t('수락')}</button>
              <button type="button" className="btn btn-text" disabled={busy === p.handle} data-tip={t('요청을 지워요(상대에게 알리지 않아요)')}
                      onClick={() => act(p.handle, () => friendsApi.remove(p.handle), t('요청을 거절했어요.'))}>{t('거절')}</button>
            </>))}
          </ul>
        </>
      )}
      <h3>{tNodes('친구 {0}', { 0: data.friends.length })}</h3>
      {data.friends.length === 0
        ? <p className="muted small">{t('아직 친구가 없어요. 다른 사람의 블로그에서 [친구 요청]을 보내 보세요.')}</p>
        : (
          <ul className="friend-list">
            {data.friends.map((p) => row(p,
              <button type="button" className="btn btn-text" disabled={busy === p.handle} data-tip={t('친구 관계를 끊어요(상대에게 알리지 않아요)')} onClick={() => {
                if (confirm(t('{0}님과 친구를 끊을까요? 상대에게 알림은 가지 않아요.', { 0: p.nickname }))) {
                  void act(p.handle, () => friendsApi.remove(p.handle), t('친구를 끊었어요.'))
                }
              }}>{t('친구 끊기')}</button>,
              lastActiveLabel(p.lastActiveDaysAgo) && t('최근 활동 {0}', { 0: lastActiveLabel(p.lastActiveDaysAgo) })))}
          </ul>
        )}
      {data.sent.length > 0 && (
        <>
          <h3>{tNodes('보낸 요청 {0}', { 0: data.sent.length })}</h3>
          <ul className="friend-list">
            {data.sent.map((p) => row(p,
              <button type="button" className="btn btn-text" disabled={busy === p.handle} data-tip={t('보낸 요청을 거둬요(상대에게 알리지 않아요)')}
                      onClick={() => act(p.handle, () => friendsApi.remove(p.handle), t('요청을 취소했어요.'))}>{t('요청 취소')}</button>))}
          </ul>
        </>
      )}
      {message && <p className={message.ok ? 'ok' : 'error'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

/** 사진 저장 공간 (009 US5). 지운 사진의 공간은 정리 작업이 실제로 지울 때(7일 뒤) 돌아온다. */
function StorageMeter() {
  const [u, setU] = useState<StorageUsage | null>(null)
  useEffect(() => { storageUsage().then(setU).catch(() => undefined) }, [])
  if (!u) return <span className="muted small">{t('불러오는 중…')}</span>
  const ratio = Math.min(1, u.usedBytes / u.quotaBytes)
  return (
    <div>
      <div className={`storage-bar${ratio > 0.9 ? ' warn' : ''}`} role="meter" aria-valuemin={0} aria-valuemax={u.quotaBytes}
           aria-valuenow={u.usedBytes} aria-label={t('사진 저장 공간 사용량')}>
        <span style={{ width: `${(ratio * 100).toFixed(1)}%` }} />
      </div>
      <span className="small">{formatBytes(u.usedBytes)} / {formatBytes(u.quotaBytes)}</span>
      <span className="muted small">  {tNodes('· 오늘 {0}/{1}장 · 지운 사진의 공간은 7일 뒤 돌아와요', { 0: u.todayCount, 1: u.dailyLimit })}</span>
    </div>
  )
}
