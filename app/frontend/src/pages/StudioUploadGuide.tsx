import { useEffect, useState, type ReactNode } from 'react'
import { Link } from '../lib/router'
import { CopyCode } from '../components/CopyCode'
import { api } from '../lib/api'
import { fieldErrors } from '../lib/fieldErrors'
import { t } from '../lib/i18n'
import { CHANNEL_KIND, socialHref, type SocialLinks } from '../lib/socialLinks'
import {
  canShareFiles, deleteUpload, PLATFORMS, recordUpload, shareVideo, uploadGuide, videoFileName,
  type Platform, type ShortsItem,
} from '../lib/shorts'

/**
 * 직접 올리기 안내 (#238). 플랫폼을 고르면 그 플랫폼에 맞춘 제목·설명·고정 댓글과 올리는 순서를 보여 준다.
 * devlog는 SNS에 아무것도 보내지 않는다. 올린 뒤 영상 주소를 남기면 대시보드(#239)가 쓴다.
 */
export function StudioUploadGuide({ item, onChange }: { item: ShortsItem; onChange: (item: ShortsItem) => void }) {
  const [platform, setPlatform] = useState<Platform>('YOUTUBE')
  const guide = uploadGuide(item, platform, window.location.origin)
  const uploaded = item.uploads.find((u) => u.platform === platform) ?? null
  const links = useMyLinks()

  return (
    <section className="studio-guide" aria-labelledby="studio-guide-title">
      <h2 id="studio-guide-title">{t('올리기 안내')}</h2>
      <p className="muted small">{t('devlog는 자동으로 올리지 않아요. 아래 문구를 복사해 내 계정에서 직접 올려요.')}</p>
      <div className="studio-tabs" role="tablist" aria-label={t('올릴 플랫폼')}>
        {PLATFORMS.map((p) => {
          const done = item.uploads.some((u) => u.platform === p.id)
          return (
            <button key={p.id} type="button" role="tab" aria-selected={p.id === platform}
              className={`btn btn-small ${p.id === platform ? 'btn-primary' : 'btn-outline'}`} onClick={() => setPlatform(p.id)}
              data-tip={done ? t('올린 주소를 남겼어요') : t('{0}에 올릴 문구와 순서 보기', { 0: p.name })}>
              {p.name}{done ? ' ✓' : ''}
            </button>
          )
        })}
      </div>

      <div role="tabpanel" aria-label={guide.spec.name}>
        <ul className="studio-checks">
          {guide.checks.map((c) => (
            <li key={c.label} className={c.ok ? 'ok' : 'over'}>
              <span aria-hidden="true">{c.ok ? '✓' : '!'}</span> {c.label}
              {!c.ok && <span className="sr-only"> {t('한도를 넘었어요')}</span>}
            </li>
          ))}
        </ul>

        <ol className="studio-steps">
          {guide.steps.map((step) => <li key={step}>{step}</li>)}
        </ol>

        <ShareButtons item={item} appName={guide.spec.name} uploadUrl={guide.spec.uploadUrl}>
          {links && <MyChannel platform={platform} links={links} />}
        </ShareButtons>

        {guide.title !== null && (
          <div className="field">
            <span className="label">{t('제목')}</span>
            <CopyCode code={guide.title} label={t('제목')} />
          </div>
        )}
        <div className="field">
          <span className="label">{guide.title === null ? t('캡션') : t('설명')}</span>
          <CopyCode code={guide.caption} label={guide.title === null ? t('캡션') : t('설명')} />
        </div>
        <div className="field">
          <span className="label">{t('고정 댓글')}</span>
          <CopyCode code={guide.pinnedComment} label={t('고정 댓글')} />
        </div>

        <UploadRecord key={`${platform}:${uploaded?.url ?? ''}`} item={item} platform={platform} url={uploaded?.url ?? null} onChange={onChange} />
      </div>
    </section>
  )
}

function ShareButtons({ item, appName, uploadUrl, children }: { item: ShortsItem; appName: string; uploadUrl: string; children?: ReactNode }) {
  const [note, setNote] = useState<string | null>(null)
  const [sharing, setSharing] = useState(false)
  if (!item.videoUrl) return null
  const fileName = videoFileName(item.title)
  const share = async () => {
    setSharing(true)
    setNote(null)
    try {
      const result = await shareVideo(item.videoUrl!, fileName, item.title)
      if (result === 'unsupported') setNote(t('이 브라우저는 앱으로 보내기를 못 해요. [영상 받기]로 받은 뒤 앱에서 골라 주세요.'))
      else if (result === 'retry') setNote(t('영상을 준비했어요. [앱으로 보내기]를 한 번 더 눌러 주세요.'))
    } catch {
      setNote(t('영상을 불러오지 못했어요. [영상 받기]로 받아 주세요.'))
    } finally {
      setSharing(false)
    }
  }
  return (
    <div className="studio-actions">
      <a className="btn btn-outline btn-small" href={item.videoUrl} download={fileName}>{t('영상 받기')}</a>
      {canShareFiles() && (
        <button type="button" className="btn btn-outline btn-small" onClick={() => void share()} disabled={sharing}
          data-tip={t('휴대폰 공유 창으로 영상을 {0} 앱에 바로 넘겨요', { 0: appName })}>{t('앱으로 보내기')}</button>
      )}
      <a className="btn btn-text btn-small" href={uploadUrl} target="_blank" rel="noopener noreferrer">{t('{0} 열기', { 0: appName })}</a>
      {children}
      {note && <p className="muted small" role="status">{note}</p>}
    </div>
  )
}

/** 설정의 소셜 정보(쇼츠 채널 칸)를 한 번 읽는다. 못 읽으면 채널 안내를 숨긴다 */
function useMyLinks(): SocialLinks | null {
  const [links, setLinks] = useState<SocialLinks | null>(null)
  useEffect(() => {
    let alive = true
    api<{ socialLinks?: SocialLinks }>('/api/me/settings')
      .then((s) => { if (alive) setLinks(s.socialLinks ?? {}) })
      .catch(() => {})
    return () => { alive = false }
  }, [])
  return links
}

/** 내 채널 바로 가기 (spec 103). 채널을 넣지 않았으면 블로그에 채널을 잇는 설정으로 안내한다 */
function MyChannel({ platform, links }: { platform: Platform; links: SocialLinks }) {
  const kind = CHANNEL_KIND[platform]
  const value = links[kind]
  if (!value) {
    return (
      <span className="muted small">
        {t('내 채널을 아직 잇지 않았어요.')} <Link to="/settings" data-tip={t('설정의 소셜 정보에 채널을 넣으면 블로그 머리에 보여요')}>{t('설정에서 채널 넣기')}</Link>
      </span>
    )
  }
  return (
    <a className="btn btn-outline btn-small" href={socialHref(kind, value)} target="_blank" rel="noopener noreferrer"
      data-tip={t('블로그에 이은 내 채널을 새 탭에서 열어요')}>{t('내 채널 열기')}</a>
  )
}

function UploadRecord({ item, platform, url, onChange }: { item: ShortsItem; platform: Platform; url: string | null; onChange: (item: ShortsItem) => void }) {
  const [value, setValue] = useState(url ?? '')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const run = async (task: () => Promise<ShortsItem>) => {
    setSaving(true)
    setError(null)
    try {
      onChange(await task())
    } catch (err) {
      const errors = fieldErrors(err)
      setError(errors.url ?? errors.form ?? t('잠시 후 다시 시도해 주세요.'))
    } finally {
      setSaving(false)
    }
  }
  const id = `studio-upload-${platform.toLowerCase()}`
  return (
    <form className="field studio-upload" onSubmit={(e) => { e.preventDefault(); void run(() => recordUpload(item.id, platform, value)) }}>
      <label htmlFor={id}>{t('올린 영상 주소')}</label>
      <div className="studio-upload-row">
        <input id={id} type="url" inputMode="url" placeholder="https://" value={value} maxLength={300} onChange={(e) => setValue(e.target.value)} />
        <button type="submit" className="btn btn-primary btn-small" disabled={saving || !value.trim() || value.trim() === url}>{t('남기기')}</button>
        {url && (
          <button type="button" className="btn btn-text btn-small" disabled={saving} onClick={() => void run(() => deleteUpload(item.id, platform))}
            data-tip={t('남긴 주소만 지워요. 올린 영상은 그대로예요')}>{t('지우기')}</button>
        )}
      </div>
      <span className="muted small">{url ? <a href={url} target="_blank" rel="noopener noreferrer">{t('올린 영상 보기')}</a> : t('올린 뒤 주소를 남기면 대시보드에서 반응을 모아 볼 수 있어요.')}</span>
      {error && <span className="error" role="alert">{error}</span>}
    </form>
  )
}
