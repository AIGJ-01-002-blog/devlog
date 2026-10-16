import { useEffect, useState, type FormEvent } from 'react'
import { t } from '../lib/i18n'
import {
  PAPER_BOT_MAX_BUDGET, PAPER_BOT_MIN_BUDGET, paperBotForm, paperBotFormError, paperBotSettings, savePaperBotSettings,
  type PaperBotForm, type PaperBotView,
} from '../lib/tradeBot'

/**
 * 서버 모의 봇의 내 기준 (#340): 코인·봉 간격·손절·익절·한 번 금액. 계정마다 따로 저장되고 다음 바퀴부터 쓴다.
 * 접어 두면 버튼 하나라 /trade 위쪽이 길어지지 않는다.
 */
export function PaperBotSettingsForm({ view, onSaved }: { view: PaperBotView; onSaved: (v: PaperBotView) => void }) {
  const [form, setForm] = useState<PaperBotForm>(() => paperBotForm(view.settings))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  // 저장된 기준이 바뀌었을 때만 맞춘다. 1분마다 새로 받아도 값이 같으면 고치던 칸을 덮지 않는다
  const savedKey = JSON.stringify(view.settings)
  useEffect(() => { setForm(paperBotForm(JSON.parse(savedKey))) }, [savedKey])

  const edit = (patch: Partial<PaperBotForm>) => { setForm((f) => ({ ...f, ...patch })); setSaved(false); setError(null) }
  const toggleCoin = (coin: string) =>
    edit({ coins: form.coins.includes(coin) ? form.coins.filter((c) => c !== coin) : view.coins.filter((c) => c === coin || form.coins.includes(c)) })

  const save = (e: FormEvent) => {
    e.preventDefault()
    const problem = paperBotFormError(form)
    if (problem) { setError(problem); return }
    setSaving(true)
    savePaperBotSettings(paperBotSettings(form))
      .then((v) => { onSaved(v); setSaved(true) })
      .catch(() => setError(t('기준을 저장하지 못했어요. 다시 시도해 주세요.')))
      .finally(() => setSaving(false))
  }

  return (
    <details className="tradebot-settings">
      <summary data-tip={t('내 모의 봇만의 코인·봉 간격·손절·익절·한 번 금액이에요. 다른 회원과 따로 돌아요')}>{t('모의 봇 기준')}</summary>
      <form onSubmit={save} className="tradebot-settings-form">
        <fieldset>
          <legend>{t('코인')}</legend>
          <div className="tradebot-settings-coins">
            {view.coins.map((c) => (
              <label key={c} className="small">
                <input type="checkbox" checked={form.coins.includes(c)} onChange={() => toggleCoin(c)} /> {c}
              </label>
            ))}
          </div>
        </fieldset>
        <label>
          <span>{t('봉 간격')}</span>
          <select value={form.interval} onChange={(e) => edit({ interval: e.target.value })}>
            {view.intervals.map((i) => <option key={i} value={i}>{i}</option>)}
          </select>
          <small className="muted">{t('고른 봉이 새로 닫힐 때마다 사고팔아요')}</small>
        </label>
        <label>
          <span>{t('손절 (%)')}</span>
          <input type="number" inputMode="decimal" min={0.5} max={50} step={0.5} value={form.stopLossPct} onChange={(e) => edit({ stopLossPct: e.target.value })} />
        </label>
        <label>
          <span>{t('익절 (%)')}</span>
          <input type="number" inputMode="decimal" min={0} max={100} step={0.5} value={form.takeProfitPct} onChange={(e) => edit({ takeProfitPct: e.target.value })} />
          <small className="muted">{t('0이면 익절선 없이 이동평균선 아래로 내려갈 때 팔아요')}</small>
        </label>
        <label>
          <span>{t('한 번 금액 (크레딧)')}</span>
          <input type="number" inputMode="numeric" min={PAPER_BOT_MIN_BUDGET} max={PAPER_BOT_MAX_BUDGET} step={1000} value={form.budget}
            onChange={(e) => edit({ budget: e.target.value })} />
        </label>
        <div className="tradebot-settings-actions">
          <button type="submit" className="btn btn-primary btn-small" disabled={saving}>{saving ? t('저장하는 중…') : t('저장')}</button>
          {saved && <span className="pnl good small" role="status">{t('저장했어요. 다음 바퀴부터 이 기준으로 돌아요')}</span>}
        </div>
        {error && <p className="error small" role="alert">{error}</p>}
      </form>
    </details>
  )
}
