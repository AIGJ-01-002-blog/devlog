import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { dayLabels, num } from '../lib/dashboard'
import { t } from '../lib/i18n'
import { Link, navigate, useLocation } from '../lib/router'
import {
  createTradeDraft, createTradeShorts, deleteTradeFill, fillTime, isDate, loadTradeDay, loadTrades, MEMO_MAX, pnlClass, qty,
  saveTradeMemo, TRADE_PERIODS, tradePeriodOf, winRate, won, type TradeDay, type TradeOverview, type TradePeriod,
} from '../lib/trades'

/**
 * 매매 일지 (#243). 집 컴퓨터의 매매 봇이 보낸 체결을 날별로 보고, 그날 메모를 남겨 요약 임시글과 쇼츠 대본을 만든다.
 * 주소: /trades?days=7|30|90&date=2026-10-14 (기본 30일, 날짜를 고르지 않으면 가장 최근 날)
 */
export function TradesPage() {
  const { search } = useLocation()
  const days = tradePeriodOf(search.get('days'))
  const picked = isDate(search.get('date')) ? search.get('date') : null
  const [data, setData] = useState<TradeOverview | null>(null)
  const [error, setError] = useState(false)
  const current = useRef(days)
  current.current = days

  useEffect(() => { document.title = t('매매 일지 - devlog') }, [])
  const reload = () => {
    setError(false)
    loadTrades(days)
      .then((d) => { if (current.current === days) setData(d) })
      .catch(() => { if (current.current === days) setError(true) })
  }
  useEffect(reload, [days]) // eslint-disable-line react-hooks/exhaustive-deps

  const date = picked ?? data?.days[0]?.date ?? null
  const href = (p: { days?: TradePeriod; date?: string | null }) => {
    const q = new URLSearchParams()
    const d = p.days ?? days
    if (d !== 30) q.set('days', String(d))
    const at = p.date === undefined ? picked : p.date
    if (at) q.set('date', at)
    const s = q.toString()
    return s ? `/trades?${s}` : '/trades'
  }

  const rate = data ? winRate(data.total) : null
  return (
    <main className="container dashboard-page trades-page">
      <h1 className="page-title">{t('매매 일지')}</h1>
      <p className="muted small trades-notice">{t('매매 봇이 보낸 체결을 모은 개인 기록이에요. 투자 권유가 아니에요.')}</p>
      <div className="admin-toolbar">
        <div className="segmented" role="group" aria-label={t('기간')}>
          {TRADE_PERIODS.map((p) => (
            <button key={p} type="button" aria-pressed={p === days} data-tip={t('오늘을 포함한 최근 {0}일을 봐요', { 0: p })}
              onClick={() => navigate(href({ days: p, date: null }))}>{t('{0}일', { 0: p })}</button>
          ))}
        </div>
        {data?.live && <span className="badge" data-tip={t('실계좌(LIVE)로 남긴 체결이 있어요. 봇의 실계좌 주문은 직접 켰을 때만 나가요')}>{t('실계좌 기록 있음')}</span>}
      </div>
      {error && <p className="error" role="alert">{t('매매 기록을 불러오지 못했어요.')}</p>}
      {!data && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {data && (
        <>
          <div className="kpi-grid dashboard-kpis" style={{ '--kpi-cols': 4 } as CSSProperties}>
            <div className="kpi" data-tip={t('기간 안에 매도로 확정된 손익이에요. 수수료·세금을 뺀 값이에요')}>
              <span className="kpi-label">{t('기간 실현 손익')}</span>
              <span className={`kpi-value pnl ${pnlClass(data.total.realizedPnl)}`}>{won(data.total.realizedPnl, true)}</span>
            </div>
            <div className="kpi" data-tip={t('봇이 처음 기록을 보낸 날부터 지금까지 확정된 손익이에요')}>
              <span className="kpi-label">{t('누적 실현 손익')}</span>
              <span className={`kpi-value pnl ${pnlClass(data.cumulativePnl)}`}>{won(data.cumulativePnl, true)}</span>
            </div>
            <div className="kpi" data-tip={t('기간 안의 체결 수예요')}>
              <span className="kpi-label">{t('체결')}</span>
              <span className="kpi-value">{num(data.total.trades)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('매수 {0} · 매도 {1}', { 0: num(data.total.buys), 1: num(data.total.sells) })}</span></span>
            </div>
            <div className="kpi" data-tip={t('손익이 난 매도 가운데 이익을 본 비율이에요')}>
              <span className="kpi-label">{t('이긴 매도')}</span>
              <span className="kpi-value">{rate === null ? '-' : `${rate}%`}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('이익 {0} · 손해 {1}', { 0: num(data.total.wins), 1: num(data.total.losses) })}</span></span>
            </div>
          </div>

          {data.days.length === 0 ? <EmptyTrades /> : (
            <div className="trades-layout">
              <section className="admin-card trades-days" aria-label={t('날짜별 기록')}>
                <h2>{t('날짜별 기록')}</h2>
                <DayBars data={data} selected={date} href={(d) => href({ date: d })} />
              </section>
              {date && <DayCard key={date} date={date} onChanged={reload} />}
            </div>
          )}
        </>
      )}
    </main>
  )
}

/** 날마다 실현 손익을 0을 가운데 둔 가로 막대로. 숫자와 +/- 기호를 함께 써서 색만으로 구분하지 않는다 */
function DayBars({ data, selected, href }: { data: TradeOverview; selected: string | null; href: (date: string) => string }) {
  const max = Math.max(1, ...data.days.map((d) => Math.abs(d.summary.realizedPnl)))
  return (
    <ol className="trades-day-list">
      {data.days.map((d) => {
        const pnl = d.summary.realizedPnl
        const width = `${Math.round((Math.abs(pnl) / max) * 100)}%`
        return (
          <li key={d.date}>
            <Link to={href(d.date)} className={`trades-day${d.date === selected ? ' active' : ''}`} aria-current={d.date === selected ? 'true' : undefined}
              data-tip={t('누적 {0}', { 0: won(d.cumulativePnl, true) })}>
              <span className="trades-day-date">{dayLabels(d.date).fullLabel}</span>
              <span className="trades-day-meta muted small">
                {t('체결 {0}건', { 0: num(d.summary.trades) })}
                {d.memo && <span title={t('메모 있음')}> · {t('메모')}</span>}
                {d.postId && <span title={t('요약 글 있음')}> · {t('글')}</span>}
                {d.shortsId && <span title={t('쇼츠 대본 있음')}> · {t('쇼츠')}</span>}
              </span>
              <span className={`trades-day-pnl pnl ${pnlClass(pnl)}`}>{d.summary.sells > 0 ? won(pnl, true) : t('확정 손익 없음')}</span>
              <span className="trades-bar" aria-hidden="true">
                <span className={`trades-bar-fill ${pnl < 0 ? 'neg' : 'pos'}`} style={{ width }} />
              </span>
            </Link>
          </li>
        )
      })}
    </ol>
  )
}

function DayCard({ date, onChanged }: { date: string; onChanged: () => void }) {
  const { me } = useAuth()
  const [day, setDay] = useState<TradeDay | null>(null)
  const [memo, setMemo] = useState('')
  const [busy, setBusy] = useState<'memo' | 'draft' | 'shorts' | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    loadTradeDay(date).then((d) => { setDay(d); setMemo(d.memo) }).catch(() => setMessage({ ok: false, text: t('이날 기록을 불러오지 못했어요.') }))
  }, [date])

  const run = async (kind: 'memo' | 'draft' | 'shorts', action: () => Promise<TradeDay>, done: string) => {
    setBusy(kind)
    setMessage(null)
    try {
      const d = await action()
      setDay(d)
      setMemo(d.memo)
      setMessage({ ok: true, text: done })
      onChanged()
    } catch (e) {
      setMessage({ ok: false, text: e instanceof ApiError ? e.message : t('저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    } finally {
      setBusy(null)
    }
  }

  const remove = async (id: number) => {
    if (!confirm(t('이 체결 기록을 지울까요? 봇이 잘못 보낸 기록만 지워 주세요.'))) return
    try {
      await deleteTradeFill(id)
      setDay(await loadTradeDay(date))
      onChanged()
    } catch {
      setMessage({ ok: false, text: t('지우지 못했어요.') })
    }
  }

  if (!day) return <section className="admin-card trades-detail">{message ? <p className="error" role="alert">{message.text}</p> : <p className="muted">{t('불러오는 중…')}</p>}</section>
  const s = day.summary
  const memoChanged = memo.trim() !== day.memo.trim()
  return (
    <section className="admin-card trades-detail" aria-label={dayLabels(date).fullLabel}>
      <h2>{dayLabels(date).fullLabel}</h2>
      <dl className="totals trades-totals">
        <div><dt>{t('실현 손익')}</dt><dd className={`pnl ${pnlClass(s.realizedPnl)}`}>{won(s.realizedPnl, true)}</dd></div>
        <div><dt>{t('누적')}</dt><dd className={`pnl ${pnlClass(day.cumulativePnl)}`}>{won(day.cumulativePnl, true)}</dd></div>
        <div><dt>{t('체결')}</dt><dd>{t('매수 {0} · 매도 {1}', { 0: num(s.buys), 1: num(s.sells) })}</dd></div>
        <div><dt data-tip={t('봇이 보낸 수수료와 세금을 더한 값이에요')}>{t('수수료·세금')}</dt><dd>{won(s.fees)}</dd></div>
      </dl>

      {day.fills.length === 0 ? <p className="muted small">{t('이날은 체결 기록이 없어요.')}</p> : (
        <ul className="trades-fills">
          {day.fills.map((f) => (
            <li key={f.id}>
              <div className="trades-fill-head">
                <span className="muted small">{fillTime(f.executedAt)}</span>
                <span className={`badge ${f.side === 'BUY' ? 'trades-buy' : 'trades-sell'}`}>{f.side === 'BUY' ? t('매수') : t('매도')}</span>
                <strong>{f.symbolName || f.symbol}</strong>
                {f.mode === 'LIVE' && <span className="badge" data-tip={t('실계좌 주문으로 남긴 체결이에요')}>{t('실계좌')}</span>}
                <button type="button" className="btn btn-text btn-small trades-fill-delete" onClick={() => void remove(f.id)}
                  aria-label={t('{0} 체결 기록 지우기', { 0: f.symbolName || f.symbol })} data-tip={t('봇이 잘못 보낸 기록을 지워요')}>{t('지우기')}</button>
              </div>
              <div className="small">
                {t('{0}주 · {1}', { 0: qty(f.quantity), 1: won(f.price) })}
                {f.realizedPnl !== null && <> · <span className={`pnl ${pnlClass(f.realizedPnl)}`}>{won(f.realizedPnl, true)}</span></>}
              </div>
              {(f.reason || f.strategy) && <div className="muted small">{f.reason || f.strategy}</div>}
            </li>
          ))}
        </ul>
      )}

      <div className="field">
        <label htmlFor={`trade-memo-${date}`} data-tip={t('요약 글과 쇼츠에 그대로 들어가요. 숫자만 읽는 영상은 날마다 같아져 양산형으로 보여서, 쇼츠는 메모가 있어야 만들어요')}>{t('오늘 메모')}</label>
        <textarea id={`trade-memo-${date}`} value={memo} maxLength={MEMO_MAX} rows={4} onChange={(e) => setMemo(e.target.value)}
          placeholder={t('왜 샀고 왜 팔았는지, 규칙을 지켰는지, 다음에 바꿀 점을 적어 보세요.')} />
      </div>
      <div className="trades-actions">
        <button type="button" className="btn btn-outline btn-small" disabled={!memoChanged || busy !== null}
          onClick={() => void run('memo', () => saveTradeMemo(date, memo), t('메모를 저장했어요.'))}>{busy === 'memo' ? t('저장하는 중…') : t('메모 저장')}</button>
        {day.postId
          ? <Link to={`/write/${day.postId}`} className="btn btn-outline btn-small" data-tip={t('아직 발행하지 않은 임시글이에요. 읽어 보고 발행할지 정해요')}>{t('요약 임시글 열기')}</Link>
          : <button type="button" className="btn btn-primary btn-small" disabled={day.fills.length === 0 || memoChanged || busy !== null}
              data-tip={memoChanged ? t('메모를 먼저 저장해 주세요') : t('체결 표·요약·메모·고지 문구로 임시글을 만들어요. 발행하기 전에는 나만 봐요')}
              onClick={() => void run('draft', () => createTradeDraft(date), t('요약 임시글을 만들었어요.'))}>{busy === 'draft' ? t('만드는 중…') : t('요약 임시글 만들기')}</button>}
        {me?.features?.shorts && (day.shortsId
          ? <Link to={`/studio?id=${day.shortsId}`} className="btn btn-outline btn-small">{t('쇼츠 대본 열기')}</Link>
          : <button type="button" className="btn btn-outline btn-small" disabled={day.fills.length === 0 || !day.memo.trim() || memoChanged || busy !== null}
              data-tip={!day.memo.trim() || memoChanged ? t('메모를 저장해야 쇼츠 대본을 만들 수 있어요') : t('하루 성적·눈에 띄는 체결·메모로 60초 대본을 만들어요. 영상은 스튜디오에서 확인하고 만들어요')}
              onClick={() => void run('shorts', () => createTradeShorts(date), t('쇼츠 대본을 만들었어요. 스튜디오에서 다듬어 주세요.'))}>{busy === 'shorts' ? t('만드는 중…') : t('쇼츠 대본 만들기')}</button>)}
      </div>
      {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

function EmptyTrades() {
  return (
    <section className="admin-card">
      <h2>{t('아직 체결 기록이 없어요')}</h2>
      <p className="muted small">
        {t('집 컴퓨터의 매매 봇이 MCP 도구 record_trade로 체결을 보내면 여기에 날짜별로 모여요. 봇에는 AI 연결에서 만든 쓰기 토큰을 환경 변수로 넣어요.')}
      </p>
      <Link to="/mcp" className="btn btn-outline btn-small">{t('AI 연결로')}</Link>
    </section>
  )
}
