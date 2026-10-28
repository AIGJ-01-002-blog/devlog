import { useEffect, useState } from 'react'
import { CopyCode } from '../components/CopyCode'
import { ApiError } from '../lib/api'
import {
  AUTO_PLATFORM_NAMES, autoJobs, autoUploadStatus, canEnable, connectResultMessage, connectUrl, disconnect, jobLabel, retryJob,
  setAutoEnabled, testUpload, type AutoJob, type AutoPlatformState, type AutoUploadStatus,
} from '../lib/autoUpload'
import { relativeDate } from '../lib/format'
import { t } from '../lib/i18n'
import type { ShortsItem } from '../lib/shorts'

const errorText = (e: unknown) => (e instanceof ApiError ? e.message : t('잠시 후 다시 시도해 주세요.'))

/**
 * 스튜디오 [자동 올리기] 카드 (/studio?auto=1, spec 108). 플랫폼마다 계정 잇기 → 시험 올리기 → 켜기 순서로 한다.
 * 켜면 렌더가 끝난 영상을 하루 한 편씩 저녁 8시(한국 시간)에 공개되게 올린다.
 */
export function StudioAutoUpload({ items, result, resultPlatform }: { items: ShortsItem[]; result: string | null; resultPlatform: string | null }) {
  const [status, setStatus] = useState<AutoUploadStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const notice = connectResultMessage(result, resultPlatform)

  useEffect(() => {
    let alive = true
    autoUploadStatus().then((s) => { if (alive) setStatus(s) }).catch((e) => { if (alive) setError(errorText(e)) })
    return () => { alive = false }
  }, [])

  const ready = items.filter((it) => it.status === 'READY' && it.videoUrl)
  return (
    <section className="studio-auto" aria-labelledby="studio-auto-title">
      <h2 id="studio-auto-title">{t('자동 올리기')}</h2>
      <p className="muted small">
        {t('켜 두면 영상 만들기가 끝난 쇼츠를 하루 한 편씩 저녁 {0}(한국 시간)에 공개되게 올려요. 처음에는 꺼져 있고, 계정을 잇고 시험 올리기를 통과한 플랫폼만 켤 수 있어요.', { 0: status?.publishTime ?? '20:00' })}
      </p>
      {notice && <p className={notice.ok ? 'studio-auto-ok' : 'error'} role="status">{notice.text}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {status === null && !error && <p className="muted small">{t('불러오는 중…')}</p>}
      {status?.platforms.map((p) => (
        <PlatformCard key={p.platform} state={p} ready={ready} onChange={setStatus} />
      ))}
    </section>
  )
}

function PlatformCard({ state, ready, onChange }: { state: AutoPlatformState; ready: ShortsItem[]; onChange: (s: AutoUploadStatus) => void }) {
  const name = AUTO_PLATFORM_NAMES[state.platform]
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [pick, setPick] = useState<number | ''>(ready[0]?.id ?? '')
  const reload = async () => {
    const s = await autoUploadStatus()
    if (s) onChange(s)
  }
  const run = async (task: () => Promise<void>) => {
    setBusy(true)
    setMessage(null)
    try {
      await task()
    } catch (e) {
      setMessage({ ok: false, text: errorText(e) })
      await reload().catch(() => {})
    } finally {
      setBusy(false)
    }
  }
  const connect = () => run(async () => { window.location.assign(await connectUrl(state.platform)) })
  const unlink = () => run(async () => {
    if (!window.confirm(t('{0} 연결을 끊을까요? 아직 올리지 않은 작업은 멈추고, 이미 올린 영상은 그대로예요.', { 0: name }))) return
    await disconnect(state.platform)
    await reload()
  })
  const test = () => run(async () => {
    if (pick === '') return
    const r = await testUpload(state.platform, pick)
    setMessage({ ok: true, text: r.message })
    await reload()
  })
  const toggle = (enabled: boolean) => run(async () => { onChange(await setAutoEnabled(state.platform, enabled)) })

  const badge = !state.connected ? t('연결 안 함') : state.enabled ? t('켜짐') : t('꺼짐')
  return (
    <div className="studio-auto-platform">
      <div className="studio-auto-head">
        <h3>{name}</h3>
        <span className={`badge${state.enabled ? ' badge-on' : ''}`}>{badge}</span>
      </div>
      {!state.configured ? (
        <p className="muted small">{t('서버에 {0} 앱 값이 아직 없어요. 설정 안내(specs/108)대로 환경 변수를 넣으면 계정을 이을 수 있어요.', { 0: name })}</p>
      ) : !state.connected ? (
        <div className="studio-actions">
          <button type="button" className="btn btn-primary btn-small" disabled={busy} onClick={() => void connect()}
            data-tip={t('{0} 동의 화면으로 가서 올리기 권한을 허용해요', { 0: name })}>{t('계정 연결')}</button>
        </div>
      ) : (
        <>
          <p className="small">
            {t('연결한 계정: {0}', { 0: state.accountName || name })}
            {state.platform === 'INSTAGRAM' && state.tokenExpiresAt && (
              <span className="muted"> · {t('토큰은 서버가 만료 전에 늘려요')}</span>
            )}
          </p>
          <div className="studio-auto-test">
            <label htmlFor={`auto-test-${state.platform}`}>{t('시험으로 올릴 쇼츠')}</label>
            <div className="studio-upload-row">
              <select id={`auto-test-${state.platform}`} value={pick} disabled={busy || ready.length === 0}
                onChange={(e) => setPick(e.target.value ? Number(e.target.value) : '')}>
                {ready.length === 0 && <option value="">{t('다 만든 영상이 없어요')}</option>}
                {ready.map((it) => <option key={it.id} value={it.id}>{it.title}</option>)}
              </select>
              <button type="button" className="btn btn-outline btn-small" disabled={busy || pick === ''} onClick={() => void test()}
                data-tip={state.platform === 'YOUTUBE'
                  ? t('고른 영상을 공개 예약 없이 비공개로 올려 봐요')
                  : t('게시하지 않고 토큰과 영상 주소만 확인해요 (Instagram은 비공개 게시가 없어요)')}>{busy ? t('확인하는 중…') : t('시험 올리기')}</button>
            </div>
            {state.testedAt
              ? <span className="studio-auto-ok small">✓ {t('시험 통과 {0}', { 0: relativeDate(state.testedAt) })}{state.testUrl && <> · <a href={state.testUrl} target="_blank" rel="noopener noreferrer">{t('시험 영상 보기')}</a></>}</span>
              : <span className="muted small">{t('아직 시험 올리기를 통과하지 않았어요.')}</span>}
            {state.testError && <span className="error small">{state.testError}</span>}
          </div>
          <label className="check small" data-tip={canEnable(state) ? t('끄면 새 영상은 올리지 않아요. 이미 예약한 영상은 그대로예요') : t('시험 올리기를 통과하면 켤 수 있어요')}>
            <input type="checkbox" checked={state.enabled} disabled={busy || !canEnable(state)} onChange={(e) => void toggle(e.target.checked)} />
            {t('렌더가 끝나면 {0}에 자동으로 올리기', { 0: name })}
          </label>
          <div className="studio-actions">
            <button type="button" className="btn btn-text btn-small" disabled={busy} onClick={() => void unlink()}
              data-tip={t('저장한 토큰을 지워요. 올린 영상은 그대로예요')}>{t('연결 끊기')}</button>
          </div>
        </>
      )}
      {message && <p className={message.ok ? 'studio-auto-ok small' : 'error small'} role="status">{message.text}</p>}
      {state.configured && (
        <details className="studio-auto-redirect small">
          <summary>{t('앱에 등록할 콜백 주소')}</summary>
          <CopyCode code={state.redirectUri} label={t('콜백 주소')} />
        </details>
      )}
    </div>
  )
}

/** 올리기 안내 안의 쇼츠별 자동 올리기 상태. 쓸 수 없거나 작업이 없으면 그리지 않는다 */
export function AutoUploadJobs({ item }: { item: ShortsItem }) {
  const [jobs, setJobs] = useState<AutoJob[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    if (item.status !== 'READY') return
    autoJobs(item.id).then((j) => { if (alive) setJobs(j) }).catch(() => {})
    return () => { alive = false }
  }, [item.id, item.status, item.version])
  if (jobs.length === 0) return null
  const retry = async (job: AutoJob) => {
    setError(null)
    try {
      setJobs(await retryJob(item.id, job.platform))
    } catch (e) {
      setError(errorText(e))
    }
  }
  return (
    <div className="studio-auto-jobs" aria-label={t('자동 올리기 상태')}>
      <span className="label">{t('자동 올리기')}</span>
      <ul>
        {jobs.map((job) => (
          <li key={job.platform} className={job.status === 'FAILED' ? 'failed' : undefined}>
            <b>{AUTO_PLATFORM_NAMES[job.platform]}</b> {jobLabel(job)}
            {job.url && <> · <a href={job.url} target="_blank" rel="noopener noreferrer">{t('올린 영상 보기')}</a></>}
            {job.error && <span className="error small"> {job.error}</span>}
            {job.note && <span className="muted small"> {job.note}</span>}
            {job.status === 'FAILED' && (
              <button type="button" className="btn btn-text btn-small" onClick={() => void retry(job)}
                data-tip={t('공개 시각을 다시 골라 처음부터 올려요')}>{t('다시 올리기')}</button>
            )}
          </li>
        ))}
      </ul>
      {error && <span className="error small" role="alert">{error}</span>}
    </div>
  )
}
