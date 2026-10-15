import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { fieldErrors } from '../lib/fieldErrors'
import { t, tNodes } from '../lib/i18n'
import { Link, navigate, useLocation } from '../lib/router'
import {
  checkShorts, createShorts, deleteBatch, deleteShorts, emptyScene, estimateSeconds, groupByBatch, isBusy, LIMITS, listBatches, listShorts,
  renderBatch, renderProblems, renderShorts, reportOf, saveShorts, SCENE_VISUALS, statusLabel, videoFileName, workerOnline, writtenTopics,
  type BatchRenderResult, type OriginalityReport, type Scene, type SceneVisual, type ShortsBatch, type ShortsItem, type ShortsOverview,
} from '../lib/shorts'
import type { ManagePage } from '../lib/types'
import { ReplayInfo, ReplayPicker } from './StudioReplay'
import { StudioUploadGuide } from './StudioUploadGuide'

const POLL_MS = 5000
/** 장면 연출 이름 → 화면 이름 (번역 키) */
const VISUAL_LABELS: Record<SceneVisual, string> = {
  explain: '설명하기', shock: '데이터가 사라짐', typing: '코드 고치기', panic: '요청 폭주', restore: '백업으로 복구', wait: '느린 로딩',
  fast: '빨라짐', idea: '팁', protect: '막아 냄', think: '원인 질문', celebrate: '결과 축하',
}

/**
 * 쇼츠 스튜디오 (099). 왼쪽은 내 대본 목록, 오른쪽은 고른 대본 편집기.
 * [영상 만들기]를 누르면 양산형 검사를 거쳐 대기열에 들어가고, 내 컴퓨터의 렌더 워커가 영상을 만든다.
 * 대기·만드는 중인 대본이 있으면 5초마다 목록을 다시 받는다.
 */
export function StudioPage() {
  const { search } = useLocation()
  const selected = Number(search.get('id')) || null
  const { me } = useAuth()
  // 매매 재현 영상(#265)은 매매 일지가 켜져 있을 때만. /studio?replay=2026-10-14로 오면 그날 체결을 고르는 화면을 연다
  const trades = me?.features?.trades ?? false
  const selectedBatch = Number(search.get('batch')) || null
  const [data, setData] = useState<ShortsOverview | null>(null)
  const [batches, setBatches] = useState<ShortsBatch[]>([])
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [picking, setPicking] = useState(search.has('replay'))

  const load = useCallback(async () => {
    // 묶음 목록이 실패해도 대본 목록과 편집기는 보여 준다 (묶음은 지난 값을 유지)
    const [overview, batchList] = await Promise.allSettled([listShorts(), listBatches()])
    if (batchList.status === 'fulfilled') setBatches(batchList.value)
    if (overview.status === 'fulfilled') {
      setData(overview.value)
      setError(null)
    } else {
      const e = overview.reason
      setError(e instanceof ApiError ? e.message : t('잠시 후 다시 시도해 주세요.'))
    }
  }, [])

  useEffect(() => { document.title = t('쇼츠 스튜디오 - devlog') }, [])
  useEffect(() => { void load() }, [load])
  const busy = data?.items.some((it) => isBusy(it.status)) ?? false
  useEffect(() => {
    if (!busy) return
    const timer = setInterval(() => void load(), POLL_MS)
    return () => clearInterval(timer)
  }, [busy, load])

  const item = data?.items.find((it) => it.id === selected) ?? null
  const batch = batches.find((b) => b.id === selectedBatch) ?? null
  const groups = data ? groupByBatch(data.items, batches) : []
  const loose = data?.items.filter((it) => it.batchId === null || !batches.some((b) => b.id === it.batchId)) ?? []
  const replace = (next: ShortsItem) =>
    setData((d) => d && { ...d, items: d.items.map((it) => (it.id === next.id ? next : it)) })

  return (
    <main className="container studio-page">
      <h1>{t('쇼츠 스튜디오')}</h1>
      <p className="muted studio-lead">
        {tNodes('AI가 쓴 대본을 다듬고 {0}를 누르면 내 컴퓨터의 렌더 워커가 9:16 영상으로 만들어요. 유튜브·인스타그램·틱톡에는 내가 직접 올려요.', { 0: <b>{t('[영상 만들기]')}</b> })}
      </p>
      {data && <WorkerStatus data={data} />}
      {error && <p className="error" role="alert">{error}</p>}
      <div className="studio-layout">
        <aside className="studio-list" aria-label={t('내 쇼츠 대본')}>
          <button type="button" className="btn btn-primary btn-block" onClick={() => { setPicking(false); setCreating(true) }}
            data-tip={t('글 하나를 골라 새 대본 만들기')}>{t('새 대본')}</button>
          {trades && (
            <button type="button" className="btn btn-outline btn-block studio-replay-btn" onClick={() => { setCreating(false); setPicking(true) }}
              data-tip={t('매매 일지의 체결을 골라 차트가 흘러가는 재현 영상 만들기')}>{t('매매 재현 영상')}</button>
          )}
          {data && data.items.length === 0 && batches.length === 0 && (
            <p className="muted small">{tNodes('아직 대본이 없어요. AI에게 "이 글로 쇼츠 여러 편 만들어 줘"라고 하면 {0}로 작은 주제를 나누고 주제마다 대본을 써 줘요.', { 0: <code>plan_shorts</code> })}</p>
          )}
          {groups.map(({ batch: b, items }) => (
            <div key={b.id} className="studio-batch">
              <Link to={`/studio?batch=${b.id}`} className={`studio-batch-head${b.id === selectedBatch ? ' active' : ''}`} aria-current={b.id === selectedBatch ? 'true' : undefined}
                data-tip={t('근거 하나를 작은 주제로 나눈 묶음이에요. 눌러서 주제와 진행을 봐요')}>
                <span className="studio-item-title">{b.title}</span>
                <span className="muted small">{t('{0}/{1}편', { 0: writtenTopics(b), 1: b.topics.length })}</span>
              </Link>
              <ItemList items={items} selected={selected} />
            </div>
          ))}
          {loose.length > 0 && batches.length > 0 && <p className="muted small studio-loose">{t('따로 쓴 대본')}</p>}
          <ItemList items={loose} selected={selected} />
        </aside>
        <section className="studio-editor">
          {picking && trades
            ? <ReplayPicker initialDate={search.get('replay')} onCancel={() => setPicking(false)} onCreated={(it) => {
                setPicking(false)
                setData((d) => d && { ...d, items: [it, ...d.items] })
                navigate(`/studio?id=${it.id}`)
              }} />
            : creating
            ? <NewScript onCancel={() => setCreating(false)} onCreated={(it) => {
                setCreating(false)
                setData((d) => d && { ...d, items: [it, ...d.items] })
                navigate(`/studio?id=${it.id}`)
              }} />
            : batch
              ? <BatchPanel key={`${batch.id}:${batch.updatedAt}`} batch={batch} onReload={load}
                  onDeleted={() => { navigate('/studio', { replace: true }); void load() }} />
            : item
              ? <Editor key={`${item.id}:${item.version}:${item.status}`} item={item} onChange={replace} onReload={load}
                  onDeleted={() => { navigate('/studio', { replace: true }); void load() }} />
              : <p className="muted studio-empty">{data?.items.length ? t('왼쪽에서 대본을 골라 주세요.') : null}</p>}
        </section>
      </div>
    </main>
  )
}

function ItemList({ items, selected }: { items: ShortsItem[]; selected: number | null }) {
  if (items.length === 0) return null
  return (
    <ul>
      {items.map((it) => (
        <li key={it.id}>
          <Link to={`/studio?id=${it.id}`} className={`studio-item${it.id === selected ? ' active' : ''}`} aria-current={it.id === selected ? 'true' : undefined}>
            <span className="studio-item-title">
              {it.replay && <span className="studio-kind" title={t('매매 재현 영상')} aria-label={t('매매 재현 영상')}>📈 </span>}
              {it.topic !== null && <span className="muted">{it.topic}. </span>}{it.title}
            </span>
            <span className={`badge studio-status studio-status-${it.status.toLowerCase()}`}>{statusLabel(it.status)}</span>
          </Link>
        </li>
      ))}
    </ul>
  )
}

/**
 * 주제 묶음 (#276). 주제마다 첫 질문·범위와 대본 상태를 보여 주고, 대본을 다 쓴 편을 한 번에 영상 만들기 대기열에 넣는다.
 * 검사에 막힌 편은 건너뛰고 이유를 보여 준다. 묶음을 풀어도 대본과 영상은 남는다.
 */
function BatchPanel({ batch, onReload, onDeleted }: { batch: ShortsBatch; onReload: () => Promise<void>; onDeleted: () => void }) {
  const [result, setResult] = useState<BatchRenderResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [working, setWorking] = useState(false)
  const ready = batch.topics.filter((tp) => tp.status === 'DRAFT' || tp.status === 'FAILED').length
  const run = async (task: () => Promise<void>) => {
    setWorking(true)
    setError(null)
    try {
      await task()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('잠시 후 다시 시도해 주세요.'))
    } finally {
      setWorking(false)
    }
  }
  const renderAll = () => run(async () => {
    setResult(await renderBatch(batch.id))
    await onReload()
  })
  const ungroup = () => {
    if (!window.confirm(t('묶음을 풀까요? 대본과 영상은 그대로 남아요.'))) return
    void run(async () => {
      await deleteBatch(batch.id)
      onDeleted()
    })
  }
  return (
    <div className="studio-form studio-batch-panel">
      <h2>{batch.title}</h2>
      <p className="muted small">
        {batch.postId !== null && batch.postTitle
          ? t('근거: 글 「{0}」', { 0: batch.postTitle })
          : batch.hasSource ? t('근거: AI와 나눈 맥락') : t('근거 없음')}
        {' · '}{t('대본 {0}/{1}편', { 0: writtenTopics(batch), 1: batch.topics.length })}
      </p>
      <ol className="studio-topics">
        {batch.topics.map((tp) => (
          <li key={tp.number} className="studio-topic">
            <div className="studio-topic-head">
              <b>{tp.number}. {tp.title}</b>
              {tp.status
                ? <span className={`badge studio-status studio-status-${tp.status.toLowerCase()}`}>{statusLabel(tp.status)}</span>
                : <span className="badge" data-tip={t('AI에게 "{0}번 주제 대본 써 줘"라고 하면 채워져요', { 0: tp.number })}>{t('대본 없음')}</span>}
            </div>
            <p className="small"><span className="muted">{t('첫 질문')}</span> {tp.hook}</p>
            {tp.scope && <p className="small"><span className="muted">{t('다룰 것')}</span> {tp.scope}</p>}
            {tp.shortsId !== null && <Link to={`/studio?id=${tp.shortsId}`} className="small">{t('대본 열기')}</Link>}
          </li>
        ))}
      </ol>
      {result && (
        <div className={`studio-report ${result.skipped.length ? 'blocked' : 'ok'}`} role="status">
          <p><b>{t('{0}편을 영상 만들기 대기열에 넣었어요', { 0: result.queued.length })}</b></p>
          {result.skipped.length > 0 && (
            <ul>
              {result.skipped.map((sk) => (
                <li key={sk.shortsId} className="warn">
                  <Link to={`/studio?id=${sk.shortsId}`}>{sk.title}</Link>{' '}<span className="muted small">{sk.reason}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {error && <p className="error" role="alert">{error}</p>}
      <div className="studio-actions studio-bar">
        <button type="button" className="btn btn-primary" onClick={() => void renderAll()} disabled={working || ready === 0}
          data-tip={ready === 0 ? t('영상으로 만들 대본이 없어요. 대본을 쓰거나 고친 편이 있으면 다시 눌러요') : t('검사를 통과한 편만 대기열에 넣어요. 막힌 편은 이유를 알려 줘요')}>
          {t('묶음 영상 모두 만들기')}
        </button>
        <button type="button" className="btn btn-text studio-delete" onClick={ungroup} disabled={working}
          data-tip={t('묶음 표시만 지워요. 대본과 영상은 남아요')}>{t('묶음 풀기')}</button>
      </div>
    </div>
  )
}

function WorkerStatus({ data }: { data: ShortsOverview }) {
  const online = workerOnline(data.workerSeenAt)
  return (
    <p className={`studio-worker ${online ? 'on' : 'off'}`} role="status">
      <span className="studio-dot" aria-hidden="true" />
      {online ? t('렌더 워커 켜짐') : t('렌더 워커 꺼짐: 켜면 기다리던 영상부터 만들어요')}
      <span className="muted"> · {t('오늘 {0}/{1}편', { 0: data.rendersToday, 1: data.dailyRenders })}</span>
    </p>
  )
}

function NewScript({ onCancel, onCreated }: { onCancel: () => void; onCreated: (item: ShortsItem) => void }) {
  const [posts, setPosts] = useState<{ id: number; title: string }[]>([])
  const [postId, setPostId] = useState<string>('')
  const [title, setTitle] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    api<ManagePage>('/api/me/posts?tab=published')
      .then((page) => setPosts(page.items.map((p) => ({ id: p.id, title: p.title }))))
      .catch(() => setPosts([]))
  }, [])
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    try {
      onCreated(await createShorts({ postId: postId ? Number(postId) : null, title, scenes: [emptyScene(), emptyScene(), emptyScene()] }))
    } catch (err) {
      setErrors(fieldErrors(err))
    } finally {
      setSaving(false)
    }
  }
  return (
    <form className="studio-new" onSubmit={submit}>
      <h2>{t('새 대본')}</h2>
      <div className="field">
        <label htmlFor="shorts-post">{t('바탕이 될 글')}</label>
        <select id="shorts-post" value={postId} onChange={(e) => {
          setPostId(e.target.value)
          if (!title) setTitle(posts.find((p) => String(p.id) === e.target.value)?.title.slice(0, LIMITS.title) ?? '')
        }}>
          <option value="">{t('글 없이 쓰기')}</option>
          {posts.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
        </select>
        <span className="muted small">{t('글을 고르면 영상 끝에 글 주소를 넣고, 글을 그대로 옮겨 적었는지도 검사해요.')}</span>
        {errors.postId && <span className="error">{errors.postId}</span>}
      </div>
      <div className="field">
        <label htmlFor="shorts-title">{t('제목')}</label>
        <input id="shorts-title" value={title} maxLength={LIMITS.title} onChange={(e) => setTitle(e.target.value)} />
        {errors.title && <span className="error">{errors.title}</span>}
      </div>
      {errors.form && <p className="error" role="alert">{errors.form}</p>}
      <div className="studio-actions">
        <button type="submit" className="btn btn-primary" disabled={saving}>{t('만들기')}</button>
        <button type="button" className="btn btn-outline" onClick={onCancel}>{t('취소')}</button>
      </div>
    </form>
  )
}

function Editor({ item, onChange, onReload, onDeleted }: {
  item: ShortsItem
  onChange: (item: ShortsItem) => void
  onReload: () => Promise<void>
  onDeleted: () => void
}) {
  const [title, setTitle] = useState(item.title)
  const [character, setCharacter] = useState<string>(item.character ?? '')
  const [scenes, setScenes] = useState<Scene[]>(item.scenes.length ? item.scenes : [emptyScene()])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [report, setReport] = useState<OriginalityReport | null>(null)
  const [working, setWorking] = useState(false)
  const busy = isBusy(item.status)
  const dirty = title !== item.title || character !== (item.character ?? '') || JSON.stringify(scenes) !== JSON.stringify(item.scenes)
  const seconds = estimateSeconds(scenes)
  const problems = renderProblems(title, scenes)

  const edit = (i: number, patch: Partial<Scene>) => setScenes((list) => list.map((s, k) => (k === i ? { ...s, ...patch } : s)))
  const move = (i: number, by: -1 | 1) => setScenes((list) => {
    const next = [...list]
    ;[next[i], next[i + by]] = [next[i + by], next[i]]
    return next
  })

  const run = async (task: () => Promise<void>) => {
    setWorking(true)
    setErrors({})
    try {
      await task()
    } catch (err) {
      const blocked = reportOf(err)
      if (blocked) setReport(blocked)
      else if (err instanceof ApiError && err.code === 'VERSION_CONFLICT') {
        setErrors({ form: t('다른 곳(AI 등)에서 이 대본을 고쳤어요. 새로 불러온 뒤 다시 고쳐 주세요.') })
        await onReload()
      } else setErrors(fieldErrors(err))
    } finally {
      setWorking(false)
    }
  }
  const save = async (): Promise<ShortsItem> => {
    const saved = await saveShorts(item.id, { title, scenes: scenes.map((s) => ({ ...s, code: s.code?.trim() ? s.code : null, image: s.image?.trim() ? s.image.trim() : null })), version: item.version, character })
    onChange(saved)
    return saved
  }
  const check = () => run(async () => {
    if (dirty) await save()
    setReport(await checkShorts(item.id))
  })
  const render = () => run(async () => {
    if (dirty) await save()
    onChange(await renderShorts(item.id))
  })
  const remove = () => {
    if (!window.confirm(t('이 대본과 만든 영상을 지울까요? 되돌릴 수 없어요.'))) return
    void run(async () => {
      await deleteShorts(item.id)
      onDeleted()
    })
  }

  return (
    <div className="studio-form">
      <div className="studio-head">
        <span className={`badge studio-status studio-status-${item.status.toLowerCase()}`}>{statusLabel(item.status)}</span>
        {item.batchId !== null && <Link to={`/studio?batch=${item.batchId}`} className="small">{t('묶음의 {0}번 주제', { 0: item.topic ?? '' })}</Link>}
        {item.postUrl && <Link to={item.postUrl} className="small">{t('바탕 글: {0}', { 0: item.postTitle ?? '' })}</Link>}
      </div>

      {item.replay && <ReplayInfo item={item} replay={item.replay} />}
      {item.status === 'READY' && item.videoUrl && (
        <div className="studio-video">
          <video src={item.videoUrl} controls playsInline preload="metadata" aria-label={t('만든 쇼츠 영상')} />
          <div className="studio-actions">
            <a className="btn btn-primary" href={item.videoUrl} download={videoFileName(item.title)} data-tip={t('MP4 파일로 받아 SNS 앱에서 직접 올려요')}>{t('영상 받기')}</a>
            <span className="muted small">
              {t('{0}초 · {1}MB', { 0: ((item.durationMs ?? 0) / 1000).toFixed(1), 1: ((item.videoBytes ?? 0) / 1024 / 1024).toFixed(1) })}
            </span>
          </div>
          <p className="muted small">{t('대본을 고치면 영상은 지워지고 다시 만들어야 해요.')}</p>
        </div>
      )}
      {item.status === 'READY' && item.videoUrl && <StudioUploadGuide item={item} onChange={onChange} />}
      {busy && <p className="studio-note" role="status">{item.status === 'QUEUED' ? t('영상 만들기를 기다리고 있어요. 렌더 워커가 켜져 있으면 곧 시작해요.') : t('렌더 워커가 영상을 만들고 있어요.')}</p>}
      {item.status === 'FAILED' && <p className="error" role="alert">{t('영상을 만들지 못했어요: {0}', { 0: item.error ?? '' })}</p>}

      <fieldset disabled={busy || working}>
        <div className="field">
          <label htmlFor="studio-title">{t('제목')}</label>
          <input id="studio-title" value={title} maxLength={LIMITS.title} onChange={(e) => setTitle(e.target.value)} />
          {errors.title && <span className="error">{errors.title}</span>}
        </div>
        {!item.replay && (
          <div className="field">
            <label htmlFor="studio-character">{t('캐릭터')}</label>
            <select id="studio-character" value={character} data-tip={t('영상에서 상황을 연기할 캐릭터예요.')} onChange={(e) => setCharacter(e.target.value)}>
              <option value="">{t('기본 (로그냥)')}</option>
              <option value="lognyang">{t('로그냥 (후드 입은 고양이)')}</option>
              <option value="logduck">{t('로그덕 (스카프 맨 오리)')}</option>
              <option value="dubu">{t('두부 (흰 강아지)')}</option>
              <option value="cat">{t('이모지 고양이')}</option>
            </select>
            {errors.character && <span className="error">{errors.character}</span>}
          </div>
        )}

        <ol className="studio-scenes">
          {scenes.map((s, i) => (
            <li key={i} className="studio-scene">
              <div className="studio-scene-head">
                <b>{t('{0}번 장면', { 0: i + 1 })}</b>
                <span className="studio-scene-tools">
                  <button type="button" className="btn btn-text btn-small" onClick={() => move(i, -1)} disabled={i === 0} aria-label={t('{0}번 장면을 위로', { 0: i + 1 })}>↑</button>
                  <button type="button" className="btn btn-text btn-small" onClick={() => move(i, 1)} disabled={i === scenes.length - 1} aria-label={t('{0}번 장면을 아래로', { 0: i + 1 })}>↓</button>
                  <button type="button" className="btn btn-text btn-small" onClick={() => setScenes((list) => list.filter((_, k) => k !== i))} disabled={scenes.length === 1} aria-label={t('{0}번 장면 지우기', { 0: i + 1 })}>✕</button>
                </span>
              </div>
              <div className="field">
                <label htmlFor={`scene-${i}-caption`}>{t('화면 자막')} <span className="muted small">{s.caption.length}/{LIMITS.caption}</span></label>
                <input id={`scene-${i}-caption`} value={s.caption} maxLength={LIMITS.caption} onChange={(e) => edit(i, { caption: e.target.value })} />
                {errors[`scenes[${i}].caption`] && <span className="error">{errors[`scenes[${i}].caption`]}</span>}
              </div>
              <div className="field">
                <label htmlFor={`scene-${i}-narration`}>{t('읽을 문장')} <span className="muted small">{s.narration.length}/{LIMITS.narration}</span></label>
                <textarea id={`scene-${i}-narration`} rows={3} value={s.narration} maxLength={LIMITS.narration} onChange={(e) => edit(i, { narration: e.target.value })} />
                {errors[`scenes[${i}].narration`] && <span className="error">{errors[`scenes[${i}].narration`]}</span>}
              </div>
              {!item.replay && (
                <>
                <div className="field">
                  <label htmlFor={`scene-${i}-code`}>{t('코드 (선택)')}</label>
                  <textarea id={`scene-${i}-code`} rows={2} className="mono" value={s.code ?? ''} maxLength={LIMITS.code} onChange={(e) => edit(i, { code: e.target.value })} />
                </div>
                <div className="field">
                  <label htmlFor={`scene-${i}-image`}>{t('배경 그림 (선택)')}</label>
                  <input id={`scene-${i}-image`} type="text" inputMode="url" value={s.image ?? ''} maxLength={LIMITS.image}
                    placeholder="/media/images/…" data-tip={t('글에 올린 그림 주소를 넣으면 이 장면 배경으로 깔려요. 비우면 첫 장면에 글 대표 사진이 들어가요.')}
                    onChange={(e) => edit(i, { image: e.target.value })} />
                  {errors[`scenes[${i}].image`] && <span className="error">{errors[`scenes[${i}].image`]}</span>}
                </div>
                <div className="field">
                  <label htmlFor={`scene-${i}-visual`}>{t('장면 연출')}</label>
                  <select id={`scene-${i}-visual`} value={s.visual ?? ''} data-tip={t('장면 내용에 맞는 움직임을 골라요. 자동이면 자막과 내레이션을 보고 정해요.')}
                    onChange={(e) => edit(i, { visual: (e.target.value || null) as SceneVisual | null })}>
                    <option value="">{t('자동')}</option>
                    {SCENE_VISUALS.map((v) => <option key={v} value={v}>{t(VISUAL_LABELS[v])}</option>)}
                  </select>
                  {s.effects && s.effects.length > 0 && <span className="muted">{t('효과음 {0}개', { 0: s.effects.length })}</span>}
                  {s.diagram && <span className="muted" data-tip={t('연결한 AI가 넣은 설명 그림이에요. 영상에서 이 장면을 도식으로 그려요.')}>{t('설명 그림 있음')}</span>}
                  {errors[`scenes[${i}].visual`] && <span className="error">{errors[`scenes[${i}].visual`]}</span>}
                  {errors[`scenes[${i}].effects`] && <span className="error">{errors[`scenes[${i}].effects`]}</span>}
                </div>
                </>
              )}
            </li>
          ))}
        </ol>
        <button type="button" className="btn btn-outline btn-small" onClick={() => setScenes((list) => [...list, emptyScene()])}
          disabled={scenes.length >= LIMITS.scenesMax}>{t('장면 추가')}</button>
      </fieldset>

      <p className={`studio-length${seconds > LIMITS.seconds ? ' over' : ''}`}>
        {t('예상 길이 {0}초 / {1}초', { 0: Math.round(seconds), 1: LIMITS.seconds })}
      </p>
      {errors.scenes && <p className="error" role="alert">{errors.scenes}</p>}
      {errors.form && <p className="error" role="alert">{errors.form}</p>}
      {report && <Report report={report} />}

      <div className="studio-actions studio-bar">
        <button type="button" className="btn btn-outline" onClick={() => void run(async () => { await save() })} disabled={!dirty || busy || working}>{t('저장')}</button>
        <button type="button" className="btn btn-outline" onClick={() => void check()} disabled={busy || working}
          data-tip={t('같은 틀로 찍어 낸 영상처럼 보이는지, 글을 그대로 옮겼는지 검사해요')}>{t('검사')}</button>
        <button type="button" className="btn btn-primary" onClick={() => void render()} disabled={busy || working || problems.length > 0 || item.status === 'READY'}
          data-tip={problems[0] ?? (item.status === 'READY' ? t('이미 만든 영상이 있어요. 대본을 고치면 다시 만들 수 있어요.') : t('검사를 통과하면 렌더 워커 대기열에 넣어요'))}>
          {t('영상 만들기')}
        </button>
        <button type="button" className="btn btn-text studio-delete" onClick={remove} disabled={busy || working}>{t('삭제')}</button>
      </div>
    </div>
  )
}

function Report({ report }: { report: OriginalityReport }) {
  return (
    <div className={`studio-report ${report.passed ? 'ok' : 'blocked'}`} role="status">
      <p><b>{report.passed ? t('검사 통과') : t('이대로는 영상을 만들 수 없어요')}</b> <span className="muted">· {t('독창성 {0}점', { 0: report.score })}</span></p>
      {report.issues.length > 0 && (
        <ul>
          {report.issues.map((issue) => (
            <li key={issue.code} className={issue.level === 'BLOCK' ? 'block' : 'warn'}>
              <span className={`badge ${issue.level === 'BLOCK' ? 'badge-danger' : 'badge-warn'}`}>{issue.level === 'BLOCK' ? t('막힘') : t('경고')}</span>
              {' '}{issue.message}{issue.hint && <span className="muted small"> {issue.hint}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
