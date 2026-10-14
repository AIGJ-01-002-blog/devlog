import { useEffect, useState } from 'react'
import { AdminNav } from '../components/AdminNav'
import { ApiError } from '../lib/api'
import { num } from '../lib/dashboard'
import { loadPointRules, savePointRules, type PointRuleView } from '../lib/points'

/**
 * 관리자 포인트 규칙 (#266). 적립 값과 하루 상한을 바꾼다. 바꾼 값은 다음 적립부터 쓰고, 이미 준 포인트는 그대로다.
 * 관리자만 저장한다(매니저는 볼 수만 있고 저장하면 서버가 403). 관리자 화면은 한국어만 쓴다.
 */
export function AdminPointsPage() {
  const [rules, setRules] = useState<PointRuleView[] | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  useEffect(() => { document.title = '포인트 규칙 - 관리자 - devlog' }, [])
  const fill = (r: PointRuleView[]) => {
    setRules(r)
    setDraft(Object.fromEntries(r.map((x) => [x.code, String(x.value)])))
  }
  useEffect(() => {
    loadPointRules().then(fill).catch(() => setMessage({ ok: false, text: '규칙을 불러오지 못했어요.' }))
  }, [])

  const changed = rules?.filter((r) => draft[r.code] !== String(r.value)) ?? []

  const save = async () => {
    const body: Record<string, number> = {}
    // 빈 칸이 0으로 저장되어 적립이 꺼지지 않게, 0 이상의 정수만 보낸다
    const bad: Record<string, string> = {}
    for (const r of changed) {
      const raw = (draft[r.code] ?? '').trim()
      const n = Number(raw)
      if (raw === '' || !Number.isInteger(n) || n < 0) bad[r.code] = '0 이상의 정수를 넣어 주세요.'
      else body[r.code] = n
    }
    if (Object.keys(bad).length > 0) {
      setFieldErrors(bad)
      setMessage({ ok: false, text: '고칠 값이 있어요.' })
      return
    }
    setBusy(true)
    setMessage(null)
    setFieldErrors({})
    try {
      fill(await savePointRules(body))
      setMessage({ ok: true, text: '저장했어요. 다음 적립부터 바뀐 값을 써요.' })
    } catch (e) {
      if (e instanceof ApiError) {
        setFieldErrors(Object.fromEntries(e.errors.map((x) => [x.field, x.message])))
        setMessage({ ok: false, text: e.errors.length ? '고칠 값이 있어요.' : e.message })
      } else setMessage({ ok: false, text: '저장하지 못했어요.' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="container narrow admin">
      <h1 className="page-title">포인트 규칙</h1>
      <AdminNav />
      <section className="admin-card points-admin">
        <h2>포인트 적립 규칙</h2>
        <p className="muted small">바꾼 값은 다음 적립부터 써요. 가입 첫 지급은 처음 포인트를 여는 회원부터 바뀐 값으로 받아요.</p>
        {!rules && !message && <p className="muted">불러오는 중…</p>}
        {rules && (
          <form onSubmit={(e) => { e.preventDefault(); void save() }}>
            <div className="points-admin-grid">
              {rules.map((r) => (
                <div className="field" key={r.code}>
                  <label htmlFor={`rule-${r.code}`} data-tip={`기본값 ${num(r.defaultValue)} · 최대 ${num(r.max)}`}>{r.label}</label>
                  <input id={`rule-${r.code}`} type="number" inputMode="numeric" min={0} max={r.max} step={1} value={draft[r.code] ?? ''}
                    aria-invalid={fieldErrors[r.code] ? true : undefined}
                    onChange={(e) => setDraft((d) => ({ ...d, [r.code]: e.target.value }))} />
                  {fieldErrors[r.code]
                    ? <span className="error small">{fieldErrors[r.code]}</span>
                    : <span className="muted small">{r.value === r.defaultValue ? '기본값' : `기본값 ${num(r.defaultValue)}에서 바꿈`}</span>}
                </div>
              ))}
            </div>
            <div className="points-admin-actions">
              <button type="submit" className="btn btn-primary" disabled={busy || changed.length === 0} data-tip="바꾼 칸만 저장해요">
                {busy ? '저장하는 중…' : changed.length ? `${changed.length}개 저장` : '바꾼 값 없음'}
              </button>
              <button type="button" className="btn btn-outline" disabled={busy} data-tip="모든 칸을 기본값으로 채워요. 저장해야 바뀌어요"
                onClick={() => setDraft(Object.fromEntries(rules.map((x) => [x.code, String(x.defaultValue)])))}>기본값으로 채우기</button>
            </div>
          </form>
        )}
        {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
      </section>
    </main>
  )
}
