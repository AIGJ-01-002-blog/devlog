import { useEffect, useRef, useState } from 'react'
import { t } from '../lib/i18n'
import {
  CHART_EXCHANGES, CHART_INTERVALS, changeRate, eventTime, loadTradeCharts, markIndex, sideLabel, sideUp,
  type ChartCoin, type ChartExchange, type ChartInterval, type CoinChart,
} from '../lib/tradeBot'
import { money, pnlClass } from '../lib/trades'

/** 서버가 캔들을 10초씩 들고 있으니 화면도 10초마다 다시 받는다 */
const REFRESH_MS = 10_000
const W = 320
const H = 170
const PAD = 6

/**
 * 자동매매 화면의 차트 탭 (spec 101). 코인 5종을 고른 거래소의 실시간(10초마다) 캔들로 그리고,
 * 봇이 알린 모의 체결을 봉 위(매도·청산)·아래(매수·진입)에 삼각형으로 찍는다. 삼각형에 마우스를 올리면 체결 내용이 보인다.
 */
export function TradeBotCharts() {
  const [exchange, setExchange] = useState<ChartExchange>('binance')
  const [interval, setTimeframe] = useState<ChartInterval>('15m')
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
        <div className="tradebot-charts">
          {shown.coins.map((c) => <CoinCard key={c.coin} coin={c} currency={shown.currency} />)}
        </div>
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
      {coin.marks.length > 0 && <p className="muted small">{t('봇 체결 {0}건', { 0: coin.marks.length })}</p>}
    </article>
  )
}

function CandleSvg({ coin, currency }: { coin: ChartCoin; currency: string }) {
  const cs = coin.candles
  if (cs.length === 0) return <p className="muted small">{t('아직 봉이 없어요.')}</p>
  const marks = coin.marks.map((m) => ({ m, i: markIndex(cs, m.t) })).filter((x): x is { m: typeof x.m; i: number } => x.i !== null)
  let lo = Math.min(...cs.map((c) => c.l), ...marks.map((x) => x.m.price))
  let hi = Math.max(...cs.map((c) => c.h), ...marks.map((x) => x.m.price))
  if (hi === lo) { hi += 1; lo -= 1 }
  const top = PAD + 10
  const bottom = H - PAD - 10
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
        const cy = buy ? Math.min(H - 2, y(cs[i].l) + 9) : Math.max(2, y(cs[i].h) - 9)
        const d = buy ? `M${cx} ${cy - 5} L${cx - 5} ${cy + 4} L${cx + 5} ${cy + 4}Z` : `M${cx} ${cy + 5} L${cx - 5} ${cy - 4} L${cx + 5} ${cy - 4}Z`
        return (
          <path key={k} d={d} className={`tradebot-mark ${buy ? 'up' : 'down'}`}>
            <title>{`${eventTime(new Date(m.t).toISOString())} ${sideLabel(m.side)} @ ${money(m.price, currency)}${m.text ? ` · ${m.text}` : ''}`}</title>
          </path>
        )
      })}
    </svg>
  )
}
