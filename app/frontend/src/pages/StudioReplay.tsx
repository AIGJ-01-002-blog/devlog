import { useEffect, useMemo, useState } from 'react'
import { CopyCode } from '../components/CopyCode'
import { fieldErrors } from '../lib/fieldErrors'
import { t } from '../lib/i18n'
import { Link } from '../lib/router'
import {
  createReplay, intervalLabel, REPLAY_FILLS_MAX, REPLAY_INTERVALS, replayPrompt,
  type ReplayInterval, type ShortsItem, type TradeReplay,
} from '../lib/shorts'
import { coinUnit, exchangeName, isDate, loadTradeDay, money, qty, type TradeDay, type TradeFill } from '../lib/trades'

/** 공개 캔들을 받아 차트를 그릴 수 있는 거래소 (서버 TradeReplay.EXCHANGES) */
const REPLAY_EXCHANGES = new Set(['BINANCE', 'UPBIT'])

const sameGroup = (a: TradeFill, b: TradeFill) => a.exchange === b.exchange && a.symbol === b.symbol && a.mode === b.mode

/** 한국 시간 10/13 10:15 */
export function replayTime(iso: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
  }).formatToParts(new Date(iso))
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${get('month')}/${get('day')} ${get('hour')}:${get('minute')}`
}

function todayKst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date())
}

/**
 * 매매 재현 영상 만들기 (#265). 매매 일지의 날짜를 골라 체결을 1~6개 고르면 스튜디오에 새 대본이 생긴다.
 * 고른 체결은 날짜를 바꿔도 남는다(며칠에 걸친 매매도 한 영상으로). 같은 거래소·종목·모드끼리만 고를 수 있다.
 */
export function ReplayPicker({ initialDate, onCancel, onCreated }: {
  initialDate: string | null
  onCancel: () => void
  onCreated: (item: ShortsItem) => void
}) {
  const [date, setDate] = useState(isDate(initialDate) ? initialDate : todayKst())
  const [day, setDay] = useState<TradeDay | null>(null)
  const [loading, setLoading] = useState(true)
  const [picked, setPicked] = useState<TradeFill[]>([])
  const [bar, setBar] = useState<ReplayInterval | ''>('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let alive = true
    setLoading(true)
    loadTradeDay(date)
      .then((d) => { if (alive) setDay(d) })
      .catch(() => { if (alive) setDay(null) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [date])

  const head = picked[0] ?? null
  const ids = useMemo(() => new Set(picked.map((f) => f.id)), [picked])
  const toggle = (f: TradeFill) => setPicked((list) => (list.some((p) => p.id === f.id)
    ? list.filter((p) => p.id !== f.id)
    : [...list, f].sort((a, b) => a.executedAt.localeCompare(b.executedAt))))
  const blockedReason = (f: TradeFill): string | null => {
    if (ids.has(f.id)) return null
    if (!REPLAY_EXCHANGES.has(f.exchange)) return t('바이낸스·업비트 체결만 차트로 재현할 수 있어요')
    if (head && !sameGroup(head, f)) return t('먼저 고른 체결과 같은 거래소·종목·모드만 고를 수 있어요')
    if (picked.length >= REPLAY_FILLS_MAX) return t('한 영상에 체결 {0}개까지예요', { 0: REPLAY_FILLS_MAX })
    return null
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setErrors({})
    try {
      onCreated(await createReplay(picked.map((f) => f.id), bar || null))
    } catch (err) {
      setErrors(fieldErrors(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="studio-new replay-picker" onSubmit={submit}>
      <h2>{t('매매 재현 영상')}</h2>
      <p className="muted small">{t('매매 일지의 체결을 고르면, 그 구간의 공개 캔들이 흘러가며 체결 시각에 매수·매도가 찍히는 영상을 만들어요. 대본은 내 AI에게 다시 써 달라고 할 수 있어요.')}</p>
      <div className="field">
        <label htmlFor="replay-date">{t('날짜')}</label>
        <input id="replay-date" type="date" value={date} max={todayKst()} onChange={(e) => isDate(e.target.value) && setDate(e.target.value)} />
      </div>

      {loading && <p className="muted small" role="status">{t('불러오는 중…')}</p>}
      {!loading && day && day.fills.length === 0 && <p className="muted small">{t('이날은 체결 기록이 없어요.')}</p>}
      {!loading && day && day.fills.length > 0 && !day.memo.trim() && (
        <p className="studio-note small" role="note">
          {t('이날 메모가 없어요. 메모를 남긴 날의 매매만 영상으로 만들 수 있어요.')}{' '}
          <Link to={`/trades?date=${date}`}>{t('매매 일지에서 메모 남기기')}</Link>
        </p>
      )}
      {!loading && day && day.fills.length > 0 && (
        <fieldset className="replay-fills">
          <legend className="sr-only">{t('재현할 체결 고르기')}</legend>
          {day.fills.map((f) => {
            const reason = blockedReason(f)
            const unit = coinUnit(f)
            return (
              <label key={f.id} className={`replay-fill${ids.has(f.id) ? ' picked' : ''}${reason ? ' blocked' : ''}`} title={reason ?? undefined}>
                <input type="checkbox" checked={ids.has(f.id)} disabled={reason !== null} onChange={() => toggle(f)} />
                <span className="replay-fill-time">{replayTime(f.executedAt)}</span>
                <span className={`badge replay-side ${f.side === 'BUY' ? 'up' : 'down'}`}>{f.side === 'BUY' ? t('매수') : t('매도')}</span>
                <span className="replay-fill-main">
                  {f.symbolName || f.symbol} <span className="muted">{exchangeName(f.exchange)}</span>
                  <span className="replay-fill-sub muted small">
                    {qty(f.quantity)}{unit ? ` ${unit}` : t('주')} · {money(f.price, f.currency)}
                    {f.realizedPnl !== null && <> · <span className={`pnl ${f.realizedPnl >= 0 ? 'good' : 'bad'}`}>{money(f.realizedPnl, f.currency, true)}</span></>}
                    {f.reason && <> · {f.reason}</>}
                  </span>
                </span>
              </label>
            )
          })}
        </fieldset>
      )}
      {errors.tradeIds && <span className="error">{errors.tradeIds}</span>}

      <div className="field">
        <label htmlFor="replay-interval">{t('봉')}</label>
        <select id="replay-interval" value={bar} onChange={(e) => setBar(e.target.value as ReplayInterval | '')}>
          <option value="">{t('자동 (체결 사이가 잘 보이는 봉)')}</option>
          {REPLAY_INTERVALS.map((i) => <option key={i} value={i}>{intervalLabel(i)}</option>)}
        </select>
        {errors.interval && <span className="error">{errors.interval}</span>}
      </div>

      <p className="muted small" role="status">
        {picked.length === 0
          ? t('체결을 골라 주세요 (최대 {0}개).', { 0: REPLAY_FILLS_MAX })
          : t('고른 체결 {0}개: {1}', { 0: picked.length, 1: picked.map((f) => replayTime(f.executedAt)).join(', ') })}
      </p>
      {errors.form && <p className="error" role="alert">{errors.form}</p>}
      <div className="studio-actions">
        <button type="submit" className="btn btn-primary" disabled={saving || picked.length === 0}
          data-tip={t('고른 체결로 대본을 만들어요. 영상은 대본을 확인한 뒤 [영상 만들기]로 만들어요')}>{t('재현 영상 만들기')}</button>
        <button type="button" className="btn btn-outline" onClick={onCancel}>{t('취소')}</button>
      </div>
    </form>
  )
}

/** 편집기 위에 보이는 재현 정보: 차트 구간·고른 체결, 장면 순서 규칙, 내 AI에게 보낼 부탁 */
export function ReplayInfo({ item, replay }: { item: ShortsItem; replay: TradeReplay }) {
  return (
    <section className="replay-info" aria-label={t('매매 재현 정보')}>
      <p className="replay-info-head">
        <b>{t('매매 재현')}</b> · {exchangeName(replay.exchange)} {replay.symbolName || replay.symbol} · {intervalLabel(replay.interval)}
        {' · '}{replay.mode === 'LIVE' ? t('실계좌') : t('모의투자')}
        <span className="muted small"> · {t('차트 {0} ~ {1}', { 0: replayTime(replay.from), 1: replayTime(replay.to) })}</span>
      </p>
      <ol className="replay-info-fills">
        {replay.fills.map((f, i) => (
          <li key={f.tradeId}>
            <span className="muted small">{t('{0}번 장면', { 0: i + 2 })}</span>{' '}
            {replayTime(f.executedAt)}{' '}
            <span className={`badge replay-side ${f.side === 'BUY' ? 'up' : 'down'}`}>{f.side === 'BUY' ? t('매수') : t('매도')}</span>{' '}
            {money(f.price, replay.currency)}
            {f.realizedPnl !== null && <> · <span className={`pnl ${f.realizedPnl >= 0 ? 'good' : 'bad'}`}>{money(f.realizedPnl, replay.currency, true)}</span></>}
          </li>
        ))}
      </ol>
      <p className="muted small">{t('1번 장면에서 체결 전 흐름을 보여 주고, 2번 장면부터 체결 하나에 장면 하나씩 그 시각에 표시가 찍혀요. 남은 장면은 결과·배운 점이고, 투자 권유 고지는 맨 끝에 짧게 붙어요.')}</p>
      <CopyCode label={t('내 AI에게 대본 부탁하기')} code={replayPrompt(item.id)} />
    </section>
  )
}
