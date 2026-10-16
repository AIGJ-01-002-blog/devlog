import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { dayLabels, num } from '../lib/dashboard'
import { t } from '../lib/i18n'
import { Link, navigate, useLocation } from '../lib/router'
import {
  actionLabel, closes, coinUnit, createTradeDraft, createTradeShorts, deleteTradeFill, exchangeName, exitLabel, exitWord, fillTime, isDate, loadTradeDay, loadTrades,
  mainCurrency, rText, MEMO_MAX, money, moneys, pnlClass, positionLabel, qty, replayHref, saveTradeMemo, TRADE_MODES, TRADE_PERIODS, tradeModeOf, tradePeriodOf, winRate,
  type Money, type SetupStat, type TradeDay, type TradeFill, type TradeMode, type TradeOverview, type TradePeriod, type TradeSummary,
} from '../lib/trades'

/**
 * 매매 일지 (#243). 집 컴퓨터의 매매 봇이 보낸 체결을 날별로 보고, 그날 메모를 남겨 요약 임시글과 쇼츠 대본을 만든다.
 * 주소: /trades?days=7|30|90&mode=live|paper&date=2026-10-14 (기본 30일, 날짜를 고르지 않으면 가장 최근 날)
 * 실거래와 모의는 토글로 나눠 본다. 고르지 않으면 실거래, 실거래 기록이 없고 모의만 있으면 모의를 보여 준다.
 */
export function TradesPage() {
  const { search } = useLocation()
  const days = tradePeriodOf(search.get('days'))
  const picked = isDate(search.get('date')) ? search.get('date') : null
  const chosen = tradeModeOf(search.get('mode'))
  const [fallback, setFallback] = useState<TradeMode>('live')
  const mode = chosen ?? fallback
  const [data, setData] = useState<TradeOverview | null>(null)
  const [error, setError] = useState(false)
  // 요청마다 번호를 붙여 마지막 요청의 응답만 쓴다. reload는 언제 불려도(지난 DayCard의 저장 완료 등) 지금 고른 기간·모드를 다시 읽는다
  const seq = useRef(0)
  const latest = useRef({ days, mode, chosen })
  useEffect(() => { latest.current = { days, mode, chosen } })
  // 주소에서 모드를 고르면 자동 전환을 잊는다. 모드 없는 주소로 돌아오면 기록을 보고 기본값을 다시 정한다
  useEffect(() => { if (chosen) setFallback('live') }, [chosen])

  useEffect(() => { document.title = t('매매 일지 - devlog') }, [])
  const reload = () => {
    const at = latest.current
    const id = ++seq.current
    setError(false)
    setData(null)
    loadTrades(at.days, at.mode)
      .then((d) => {
        if (seq.current !== id) return
        // 고른 적 없고 실거래 기록이 없으면 모의로 넘어간다
        if (!at.chosen && at.mode === 'live' && !d.live && d.paper) setFallback('paper')
        else setData(d)
      })
      .catch(() => { if (seq.current === id) setError(true) })
  }
  useEffect(reload, [days, mode, chosen]) // eslint-disable-line react-hooks/exhaustive-deps

  const date = picked ?? data?.days[0]?.date ?? null
  const href = (p: { days?: TradePeriod; date?: string | null; mode?: TradeMode }) => {
    const q = new URLSearchParams()
    const d = p.days ?? days
    if (d !== 30) q.set('days', String(d))
    const m = p.mode ?? chosen
    if (m) q.set('mode', m)
    const at = p.date === undefined ? picked : p.date
    if (at) q.set('date', at)
    const s = q.toString()
    return s ? `/trades?${s}` : '/trades'
  }

  const rate = data ? winRate(data.total) : null
  const main = data ? mainCurrency(data.total) : 'KRW'
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
        <div className="segmented" role="group" aria-label={t('실거래·모의')}>
          {TRADE_MODES.map((m) => (
            <button key={m} type="button" aria-pressed={m === mode}
              data-tip={m === 'live' ? t('실제 돈으로 거래한 체결만 봐요') : t('모의투자로 거래한 체결만 봐요')}
              onClick={() => navigate(href({ mode: m, date: null }))}>{m === 'live' ? t('실거래') : t('모의')}</button>
          ))}
        </div>
      </div>
      {error && <p className="error" role="alert">{t('매매 기록을 불러오지 못했어요.')}</p>}
      {!data && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {data && (
        <>
          <div className="kpi-grid dashboard-kpis" style={{ '--kpi-cols': 4 } as CSSProperties}>
            <div className="kpi" data-tip={t('기간 안에 매도·선물 청산으로 확정된 손익이에요. 수수료·세금을 뺀 값이에요')}>
              <span className="kpi-label">{t('기간 실현 손익')}</span>
              <span className="kpi-value"><Pnl s={data.total} main={main} stack /></span>
            </div>
            <div className="kpi" data-tip={t('봇이 처음 기록을 보낸 날부터 지금까지 확정된 손익이에요')}>
              <span className="kpi-label">{t('누적 실현 손익')}</span>
              <span className="kpi-value"><Amounts m={data.cumulativePnl} main={main} pnl stack /></span>
            </div>
            <div className="kpi" data-tip={t('기간 안의 체결 수예요')}>
              <span className="kpi-label">{t('체결')}</span>
              <span className="kpi-value">{num(data.total.trades)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('매수 {0} · 매도 {1}', { 0: num(data.total.buys), 1: num(data.total.sells) })}</span></span>
            </div>
            <div className="kpi" data-tip={t('손익이 난 매도·선물 청산 가운데 이익을 본 비율이에요')}>
              <span className="kpi-label">{t('이긴 청산')}</span>
              <span className="kpi-value">{rate === null ? '-' : `${rate}%`}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('이익 {0} · 손해 {1}', { 0: num(data.total.wins), 1: num(data.total.losses) })}</span></span>
            </div>
          </div>

          {data.setups && data.setups.length > 0 && <SetupStats stats={data.setups} />}

          {data.days.length === 0 ? <EmptyTrades mode={mode} /> : (
            <div className="trades-layout">
              <section className="admin-card trades-days" aria-label={t('날짜별 기록')}>
                <h2>{t('날짜별 기록')}</h2>
                <DayBars data={data} selected={date} href={(d) => href({ date: d })} />
              </section>
              {date && <DayCard key={`${mode}:${date}`} date={date} mode={mode} onChanged={reload} />}
            </div>
          )}
        </>
      )}
    </main>
  )
}

/**
 * 날마다 실현 손익을 가로 막대로. 숫자와 +/- 기호를 함께 써서 색만으로 구분하지 않는다.
 * 원화와 USDT는 크기를 견줄 수 없어서 통화마다 막대를 따로 그리고, 길이는 기간 안의 같은 통화 가운데 가장 큰 손익에 맞춘다.
 */
function DayBars({ data, selected, href }: { data: TradeOverview; selected: string | null; href: (date: string) => string }) {
  const max: Record<string, number> = {}
  for (const d of data.days) {
    for (const [c, v] of Object.entries(d.summary.realizedPnl)) max[c] = Math.max(max[c] ?? 0, Math.abs(v))
  }
  const main = mainCurrency(data.total)
  return (
    <ol className="trades-day-list">
      {data.days.map((d) => {
        const pnl = d.summary.realizedPnl
        return (
          <li key={d.date}>
            <Link to={href(d.date)} className={`trades-day${d.date === selected ? ' active' : ''}`} aria-current={d.date === selected ? 'true' : undefined}
              data-tip={t('누적 {0}', { 0: moneys(d.cumulativePnl, main, true) })}>
              <span className="trades-day-date">{dayLabels(d.date).fullLabel}</span>
              <span className="trades-day-meta muted small">
                {t('체결 {0}건', { 0: num(d.summary.trades) })}
                {d.memo && <span title={t('메모 있음')}> · {t('메모')}</span>}
                {d.postId && <span title={t('요약 글 있음')}> · {t('글')}</span>}
                {d.shortsId && <span title={t('쇼츠 대본 있음')}> · {t('쇼츠')}</span>}
              </span>
              <span className="trades-day-pnl">{d.summary.closes > 0 ? <Pnl s={d.summary} main={main} /> : t('확정 손익 없음')}</span>
              {Object.keys(pnl).length === 0
                ? <span className="trades-bar" aria-hidden="true" />
                : Object.entries(pnl).map(([c, v]) => (
                  <span key={c} className="trades-bar" aria-hidden="true">
                    <span className={`trades-bar-fill ${v < 0 ? 'neg' : 'pos'}`} style={{ width: `${max[c] ? Math.round((Math.abs(v) / max[c]) * 100) : 0}%` }} />
                  </span>
                ))}
            </Link>
          </li>
        )
      })}
    </ol>
  )
}

/** 기법 버전·셋업별 승률과 평균 R (#285). 봇이 R배수를 남긴 정리 체결만 센다 */
function SetupStats({ stats }: { stats: SetupStat[] }) {
  return (
    <section className="admin-card trades-setups" aria-label={t('기법별 성적')}>
      <h2 data-tip={t('봇이 R배수를 남긴 정리 체결만 기법 버전·셋업으로 묶어 세요. R은 처음 정한 위험 대비 손익이에요')}>{t('기법별 성적')}</h2>
      <div className="admin-table-wrap">
        <table className="trades-setup-table">
          <thead>
            <tr><th scope="col">{t('기법 버전')}</th><th scope="col">{t('셋업')}</th><th scope="col" className="num">{t('정리')}</th>
              <th scope="col" className="num">{t('승률')}</th><th scope="col" className="num">{t('평균 R')}</th></tr>
          </thead>
          <tbody>
            {stats.map((s) => (
              <tr key={`${s.strategyVersion}:${s.setup}`}>
                <td>{s.strategyVersion || '-'}</td>
                <td>{s.setup || '-'}</td>
                <td className="num">{num(s.samples)}</td>
                <td className="num">{Math.round((s.wins / s.samples) * 100)}%</td>
                <td className={`num pnl ${s.avgR > 0 ? 'good' : s.avgR < 0 ? 'bad' : 'flat'}`}>{rText(s.avgR)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/** 체결 한 줄의 판단 흔적: 바구니·셋업, 진입 때 정한 손절·익절, 정리 이유와 R배수. 흔적이 없는 옛 체결은 아무것도 그리지 않는다 */
function TraceLine({ f }: { f: TradeFill }) {
  const tr = f.trace
  if (!tr) return null
  const filters = [tr.regime, tr.setup, tr.trigger].filter(Boolean).join(' → ')
  const exit = exitLabel(f)
  const levels = [
    tr.stopPrice !== null ? t('손절 {0}', { 0: money(tr.stopPrice, f.currency) }) : null,
    tr.tp1Price !== null ? t('익절 {0}', { 0: money(tr.tp1Price, f.currency) }) : null,
    tr.tp2Price !== null ? t('2차 익절 {0}', { 0: money(tr.tp2Price, f.currency) }) : null,
  ].filter((x): x is string => x !== null)
  if (!tr.basket && !tr.setup && !exit && levels.length === 0) return null
  return (
    <div className="small trades-trace">
      {tr.basket && <span className="badge" data-tip={tr.basket === 'CORE' ? t('오래 모아 가는 코어 적립 몫이에요') : t('짧게 사고파는 트레이딩 몫이에요')}>
        {tr.basket === 'CORE' ? t('코어') : t('트레이딩')}</span>}
      {tr.setup && <span className="muted" data-tip={filters ? t('세 겹 거름망: {0}', { 0: filters }) : undefined}>{tr.setup}</span>}
      {levels.length > 0 && <span className="muted" data-tip={t('진입할 때 정한 값이에요')}>{levels.join(' · ')}</span>}
      {exit && <span className={`pnl ${tr.rMultiple === null ? 'flat' : tr.rMultiple < 0 ? 'bad' : tr.rMultiple > 0 ? 'good' : 'flat'}`}
        data-tip={tr.exitReason ? t('{0}로 정리했어요. R은 처음 정한 위험 대비 손익이에요', { 0: t(exitWord(tr.exitReason)) }) : t('R은 처음 정한 위험 대비 손익이에요')}>{exit}</span>}
    </div>
  )
}

function DayCard({ date, mode, onChanged }: { date: string; mode: TradeMode; onChanged: () => void }) {
  const { me } = useAuth()
  const [day, setDay] = useState<TradeDay | null>(null)
  const [memo, setMemo] = useState('')
  const [busy, setBusy] = useState<'memo' | 'draft' | 'shorts' | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    loadTradeDay(date, mode).then((d) => { setDay(d); setMemo(d.memo) }).catch(() => setMessage({ ok: false, text: t('이날 기록을 불러오지 못했어요.') }))
  }, [date, mode])

  const run = async (kind: 'memo' | 'draft' | 'shorts', action: () => Promise<TradeDay>, done: string) => {
    setBusy(kind)
    setMessage(null)
    try {
      await action()
      const d = await loadTradeDay(date, mode) // 저장 응답은 두 모드를 합친 값이라 고른 모드로 다시 읽는다
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
      setDay(await loadTradeDay(date, mode))
      onChanged()
    } catch {
      setMessage({ ok: false, text: t('지우지 못했어요.') })
    }
  }

  if (!day) return <section className="admin-card trades-detail">{message ? <p className="error" role="alert">{message.text}</p> : <p className="muted">{t('불러오는 중…')}</p>}</section>
  const s = day.summary
  const main = mainCurrency(s)
  const memoChanged = memo.trim() !== day.memo.trim()
  return (
    <section className="admin-card trades-detail" aria-label={dayLabels(date).fullLabel}>
      <h2>{dayLabels(date).fullLabel}</h2>
      <dl className="totals trades-totals">
        <div><dt>{t('실현 손익')}</dt><dd><Pnl s={s} main={main} stack /></dd></div>
        <div><dt>{t('누적')}</dt><dd><Amounts m={day.cumulativePnl} main={main} pnl stack /></dd></div>
        <div><dt>{t('체결')}</dt><dd>{t('매수 {0} · 매도 {1}', { 0: num(s.buys), 1: num(s.sells) })}</dd></div>
        <div><dt data-tip={t('봇이 보낸 수수료와 세금을 더한 값이에요')}>{t('수수료·세금')}</dt><dd><Amounts m={s.fees} main={main} stack /></dd></div>
      </dl>

      {day.fills.length === 0 ? <p className="muted small">{t('이날은 체결 기록이 없어요.')}</p> : (
        <ul className="trades-fills">
          {day.fills.map((f) => (
            <li key={f.id}>
              <div className="trades-fill-head">
                <span className="muted small">{fillTime(f.executedAt)}</span>
                <span className={`badge ${f.side === 'BUY' ? 'trades-buy' : 'trades-sell'}`}
                  data-tip={f.market === 'FUTURES' ? (closes(f) ? t('포지션을 닫은 체결이에요. 실현 손익이 여기서 확정돼요') : t('포지션을 연 체결이에요')) : undefined}>{actionLabel(f)}</span>
                <strong>{f.symbolName || f.symbol}</strong>
                {f.exchange && <span className="muted trades-exchange">{exchangeName(f.exchange)}</span>}
                {f.market === 'FUTURES' && <span className="badge trades-position" data-tip={t('선물 포지션 방향과 레버리지예요. 손익은 레버리지만큼 커져요')}>{positionLabel(f)}</span>}
                {f.interval && <span className="muted trades-exchange" data-tip={t('봇이 이 봉을 보고 판단했어요')}>{f.interval}</span>}
                {f.mode === 'LIVE' && <span className="badge" data-tip={t('실계좌 주문으로 남긴 체결이에요')}>{t('실계좌')}</span>}
                <button type="button" className="btn btn-text btn-small trades-fill-delete" onClick={() => void remove(f.id)}
                  aria-label={t('{0} 체결 기록 지우기', { 0: f.symbolName || f.symbol })} data-tip={t('봇이 잘못 보낸 기록을 지워요')}>{t('지우기')}</button>
              </div>
              <div className="small">
                {fillAmount(f)}
                {f.realizedPnl !== null && <> · <span className={`pnl ${pnlClass(f.realizedPnl, f.currency)}`}>{money(f.realizedPnl, f.currency, true)}</span></>}
              </div>
              <TraceLine f={f} />
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
        {me?.features?.shorts && day.fills.length > 0 && (
          <Link to={replayHref(date)} className="btn btn-outline btn-small"
            data-tip={t('이날 체결을 골라 실시간으로 매매하는 것처럼 캔들 위에 다시 그리는 영상을 스튜디오에서 만들어요')}>{t('재현 영상 만들기')}</Link>
        )}
      </div>
      {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
    </section>
  )
}

/**
 * 통화별 금액. 통화마다 따로 색을 칠하고(원화 손실·USDT 이익이 섞여도 각자 빨강·초록), 금액 하나는 줄바꿈하지 않는다.
 * pnl이면 손익이라 +를 붙이고 색을 칠한다. stack이면 통화마다 한 줄씩 쌓는다(좁은 카드에서 줄 머리에 · 가 오지 않게)
 */
function Amounts({ m, main, pnl = false, stack = false }: { m: Money; main: string; pnl?: boolean; stack?: boolean }) {
  const entries = Object.entries(m)
  const list = entries.length > 0 ? entries : [[main, 0] as [string, number]]
  return (
    <span className={`trades-amounts${stack ? ' stack' : ''}`}>
      {list.map(([c, v]) => (
        <span key={c} className={pnl ? `pnl ${pnlClass(v, c)}` : undefined}>{money(v, c, pnl)}</span>
      ))}
    </span>
  )
}

/** 실현 손익. 모의투자와 실계좌가 섞이면 더하지 않고 나눠 보여 준다 (#260) */
function Pnl({ s, main, stack = false }: { s: TradeSummary; main: string; stack?: boolean }) {
  const modes = Object.entries(s.modes ?? {}).filter(([, m]) => m.closes > 0)
  if (modes.length === 0) return <Amounts m={s.realizedPnl} main={main} pnl stack={stack} />
  return (
    <span className={`trades-modes${stack ? ' stack' : ''}`}>
      {modes.map(([mode, m]) => (
        <span key={mode} className="trades-mode" data-tip={t('모의투자와 실계좌 손익은 더하지 않고 따로 보여 줘요')}>
          <span className="badge">{mode === 'LIVE' ? t('실계좌') : t('모의투자')}</span> <Amounts m={m.realizedPnl} main={main} pnl />
        </span>
      ))}
    </span>
  )
}

/** 수량과 가격: 주식 "10주 · 71,000원", 코인 "0.0012 BTC · 83,752.30 USDT" */
function fillAmount(f: TradeFill): string {
  const unit = coinUnit(f)
  const price = money(f.price, f.currency)
  return unit ? `${qty(f.quantity)} ${unit} · ${price}` : t('{0}주 · {1}', { 0: qty(f.quantity), 1: price })
}

function EmptyTrades({ mode }: { mode: TradeMode }) {
  return (
    <section className="admin-card">
      <h2>{mode === 'live' ? t('아직 실거래 기록이 없어요') : t('아직 모의 기록이 없어요')}</h2>
      <p className="muted small">
        {t('집 컴퓨터의 매매 봇이 MCP 도구 record_trade로 체결을 보내면 여기에 날짜별로 모여요. 봇에는 AI 연결에서 만든 쓰기 토큰을 환경 변수로 넣어요.')}
      </p>
      <Link to="/mcp" className="btn btn-outline btn-small">{t('AI 연결로')}</Link>
    </section>
  )
}
