import { useEffect, useRef, useState } from 'react'
import { t } from '../lib/i18n'
import {
  CHART_EXCHANGES, CHART_INTERVALS, changeRate, eventTime, loadTradeCharts, markIndex, sideLabel, sideUp,
  type ChartCoin, type ChartExchange, type ChartInterval, type CoinChart,
} from '../lib/tradeBot'
import { money, pnlClass } from '../lib/trades'

/** 서버가 캔들을 10초씩 들고 있으니 화면도 10초마다 다시 받는다 */
const REFRESH_MS = 10_000
/** 고른 종목 하나를 크게 그린다. PC에서는 넓게, 휴대폰에서는 화면 폭에 맞춰 같은 비율로 줄어든다 */
const PC = { w: 900, h: 400 }
/** 휴대폰은 폭이 좁아 같은 비율이면 납작해지므로 세로를 늘린 비율로 그린다(봉·삼각형 크기는 그대로) */
const MOBILE = { w: 420, h: 300 }
const PAD = 10

/** 휴대폰 폭인가. 화면을 돌리거나 창 크기를 바꾸면 따라 바뀐다 */
function useNarrow(): boolean {
  const query = '(max-width: 767px)'
  const [narrow, setNarrow] = useState(() => typeof window !== 'undefined' && !!window.matchMedia?.(query).matches)
  useEffect(() => {
    const m = window.matchMedia?.(query)
    if (!m) return
    const on = () => setNarrow(m.matches)
    m.addEventListener('change', on)
    return () => m.removeEventListener('change', on)
  }, [])
  return narrow
}

/**
 * 자동매매 화면의 차트 탭 (spec 101). 코인 5종 가운데 고른 종목을 고른 거래소의 실시간(10초마다) 캔들로 크게 그리고,
 * 봇이 알린 모의 체결을 봉 위(매도·청산)·아래(매수·진입)에 삼각형으로 찍는다. 삼각형에 마우스를 올리면 체결 내용이 보이고,
 * 휴대폰처럼 마우스가 없을 때를 위해 차트 아래에 같은 체결을 목록으로 둔다.
 */
export function TradeBotCharts() {
  const [exchange, setExchange] = useState<ChartExchange>('binance')
  const [interval, setTimeframe] = useState<ChartInterval>('15m')
  const [picked, setPicked] = useState('BTC')
  const [data, setData] = useState<CoinChart | null>(null)
  const [error, setError] = useState(false)
  const key = useRef('')
  key.current = `${exchange}:${interval}`

  useEffect(() => {
    const mine = `${exchange}:${interval}`
    const load = () => loadTradeCharts(exchange, interval)
      .then((d) => { if (key.current === mine) { setData(d); setError(false) } })
      .catch(() => { if (key.current === mine) setError(true) })
    void load()
    const id = window.setInterval(() => { if (document.visibilityState === 'visible') void load() }, REFRESH_MS)
    return () => window.clearInterval(id)
  }, [exchange, interval])

  const shown = data && data.exchange.toLowerCase() === exchange && data.interval === interval ? data : null
  return (
    <section className="tradebot-section">
      <h2>{t('실시간 차트')}</h2>
      <div className="admin-toolbar tradebot-chart-toolbar">
        <div className="segmented" role="group" aria-label={t('거래소')}>
          {CHART_EXCHANGES.map((x) => (
            <button key={x} type="button" aria-pressed={x === exchange} onClick={() => setExchange(x)}
              data-tip={x === 'binance' ? t('바이낸스 현물 USDT 시세로 봐요') : t('업비트 원화 시세로 봐요')}>
              {x === 'binance' ? t('바이낸스') : t('업비트')}
            </button>
          ))}
        </div>
        <div className="segmented" role="group" aria-label={t('봉 간격')}>
          {CHART_INTERVALS.map((x) => (
            <button key={x} type="button" aria-pressed={x === interval} onClick={() => setTimeframe(x)}
              data-tip={t('봉 하나가 {0}예요', { 0: x })}>{x}</button>
          ))}
        </div>
      </div>
      <p className="muted small">{t('10초마다 새로 받아요. ▲는 봇의 모의 매수·진입, ▼는 매도·청산이에요. 삼각형에 마우스를 올리면 이유가 보여요.')}</p>
      {error && <p className="error small" role="alert">{t('차트를 불러오지 못했어요. 10초 뒤 다시 받아요.')}</p>}
      {!shown && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {shown && (
        <>
          <div className="tradebot-coins" role="tablist" aria-label={t('종목')}>
            {shown.coins.map((c) => {
              const last = c.candles.at(-1)
              const rate = changeRate(c.candles)
              return (
                <button key={c.coin} type="button" role="tab" aria-selected={c.coin === picked} className={`tradebot-coin${c.coin === picked ? ' active' : ''}`}
                  onClick={() => setPicked(c.coin)} data-tip={t('{0} 차트를 크게 봐요', { 0: t(c.name) })}>
                  <span className="tradebot-coin-name"><strong>{c.coin}</strong><span className="muted small">{t(c.name)}</span></span>
                  {last && <span className="tradebot-coin-price small">{money(last.c, shown.currency)}</span>}
                  {rate !== null && <span className={`pnl small ${pnlClass(rate)}`}>{rate > 0 ? '+' : ''}{rate.toFixed(2)}%</span>}
                  {c.marks.length > 0 && <span className="tradebot-coin-dot" aria-label={t('봇 체결 {0}건', { 0: c.marks.length })} />}
                </button>
              )
            })}
          </div>
          {shown.coins.filter((c) => c.coin === picked).map((c) => <CoinCard key={c.coin} coin={c} currency={shown.currency} />)}
        </>
      )}
    </section>
  )
}

function CoinCard({ coin, currency }: { coin: ChartCoin; currency: string }) {
  const last = coin.candles.at(-1)
  const rate = changeRate(coin.candles)
  return (
    <article className="tradebot-chart">
      <header className="trades-fill-head">
        <strong>{t(coin.name)}</strong>
        <span className="muted small">{coin.symbol}</span>
        {last && <span className="tradebot-chart-price">{money(last.c, currency)}</span>}
        {rate !== null && <span className={`pnl small ${pnlClass(rate)}`}>{rate > 0 ? '+' : ''}{rate.toFixed(2)}%</span>}
      </header>
      {coin.error ? <p className="muted small">{t('시세를 받지 못했어요. 잠시 뒤 다시 받아요.')}</p> : <CandleSvg coin={coin} currency={currency} />}
      {coin.candles.length > 0 && (
        <p className="muted small tradebot-range">
          {t('구간 고가 {0} · 저가 {1}', { 0: money(Math.max(...coin.candles.map((x) => x.h)), currency), 1: money(Math.min(...coin.candles.map((x) => x.l)), currency) })}
        </p>
      )}
      {coin.marks.length === 0 ? <p className="muted small">{t('이 구간에는 봇 체결이 없어요.')}</p> : (
        <ul className="tradebot-marks">
          {[...coin.marks].reverse().map((m, i) => (
            <li key={i}>
              <span className={sideUp(m.side) ? 'trades-buy' : 'trades-sell'}>{sideUp(m.side) ? '▲' : '▼'} <strong>{sideLabel(m.side)}</strong></span>
              <span className="small">{money(m.price, currency)}</span>
              <span className="muted small">{eventTime(new Date(m.t).toISOString())}</span>
              {m.text && <span className="small tradebot-text">{m.text}</span>}
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}

function CandleSvg({ coin, currency }: { coin: ChartCoin; currency: string }) {
  const { w: W, h: H } = useNarrow() ? MOBILE : PC
  const cs = coin.candles
  if (cs.length === 0) return <p className="muted small">{t('아직 봉이 없어요.')}</p>
  const marks = coin.marks.map((m) => ({ m, i: markIndex(cs, m.t) })).filter((x): x is { m: typeof x.m; i: number } => x.i !== null)
  let lo = Math.min(...cs.map((c) => c.l), ...marks.map((x) => x.m.price))
  let hi = Math.max(...cs.map((c) => c.h), ...marks.map((x) => x.m.price))
  if (hi === lo) { hi += 1; lo -= 1 }
  const top = PAD + 18
  const bottom = H - PAD - 18
  const y = (v: number) => top + ((hi - v) / (hi - lo)) * (bottom - top)
  const step = (W - PAD * 2) / cs.length
  const x = (i: number) => PAD + step * i + step / 2
  const body = Math.max(1, step * 0.6)
  const lastY = y(cs[cs.length - 1].c)
  return (
    <svg className="tradebot-candles" viewBox={`0 0 ${W} ${H}`} role="img"
      aria-label={t('{0} 차트, 마지막 {1}', { 0: t(coin.name), 1: money(cs[cs.length - 1].c, currency) })}>
      <line className="tradebot-last" x1={PAD} x2={W - PAD} y1={lastY} y2={lastY} />
      {cs.map((c, i) => {
        const up = c.c >= c.o
        return (
          <g key={c.t} className={up ? 'up' : 'down'}>
            <line x1={x(i)} x2={x(i)} y1={y(c.h)} y2={y(c.l)} />
            <rect x={x(i) - body / 2} width={body} y={y(Math.max(c.o, c.c))} height={Math.max(1, Math.abs(y(c.o) - y(c.c)))} />
          </g>
        )
      })}
      {marks.map(({ m, i }, k) => {
        const buy = sideUp(m.side)
        const cx = x(i)
        const cy = buy ? Math.min(H - 2, y(cs[i].l) + 14) : Math.max(2, y(cs[i].h) - 14)
        const d = buy ? `M${cx} ${cy - 8} L${cx - 8} ${cy + 6} L${cx + 8} ${cy + 6}Z` : `M${cx} ${cy + 8} L${cx - 8} ${cy - 6} L${cx + 8} ${cy - 6}Z`
        return (
          <path key={k} d={d} className={`tradebot-mark ${buy ? 'up' : 'down'}`}>
            <title>{`${eventTime(new Date(m.t).toISOString())} ${sideLabel(m.side)} @ ${money(m.price, currency)}${m.text ? ` · ${m.text}` : ''}`}</title>
          </path>
        )
      })}
    </svg>
  )
}
