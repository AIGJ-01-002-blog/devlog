import { useEffect, useMemo, useRef, useState } from 'react'
import { t } from '../lib/i18n'
import { botPositions } from '../lib/proChart'
import {
  CHART_EXCHANGES, CHART_INTERVALS, KRW_INTERVALS, changeRate, eventTime, krwSourceName, loadTradeCharts, sideLabel, sideUp,
  type BotAccount, type ChartCoin, type ChartExchange, type ChartInterval, type CoinChart,
} from '../lib/tradeBot'
import { money, pnlClass } from '../lib/trades'
import { ProChart } from './ProChart'

/** 서버가 캔들을 10초씩 들고 있으니 화면도 10초마다 다시 받는다 */
const REFRESH_MS = 10_000

/**
 * 자동매매 화면의 차트 탭 (spec 101). 코인 5종 가운데 고른 종목을 고른 거래소의 실시간(10초마다) 캔들로 크게 그린다.
 * #354부터 거래소 차트처럼 이동평균 7/25/99·거래량·십자선·24시간 요약과 봇 포지션의 평단선을 함께 그린다(ProChart).
 * 휴대폰처럼 마우스가 없을 때를 위해 차트 아래에 봇 체결을 목록으로 둔다.
 *
 * @param accounts 봇이 알린 모의 계좌. 이 종목을 들고 있으면 계좌마다 평단선을 그린다
 */
export function TradeBotCharts({ accounts = [] }: { accounts?: BotAccount[] }) {
  const [exchange, setExchange] = useState<ChartExchange>('binance')
  const [interval, setTimeframe] = useState<ChartInterval>('15m')
  const [picked, setPicked] = useState('BTC')
  const [data, setData] = useState<CoinChart | null>(null)
  const [error, setError] = useState(false)
  const key = useRef('')
  key.current = `${exchange}:${interval}`
  const intervals = exchange === 'upbit' ? KRW_INTERVALS : CHART_INTERVALS

  const pickExchange = (x: ChartExchange) => {
    setExchange(x)
    // 원화 시세는 1초 봉이 없어 15분으로 바꾼다
    if (x === 'upbit' && !KRW_INTERVALS.includes(interval)) setTimeframe('15m')
  }

  useEffect(() => {
    const mine = `${exchange}:${interval}`
    const load = () => loadTradeCharts(exchange, interval)
      .then((d) => { if (key.current === mine) { setData(d); setError(false) } })
      .catch(() => { if (key.current === mine) setError(true) })
    void load()
    const id = window.setInterval(() => { if (document.visibilityState === 'visible') void load() }, REFRESH_MS)
    return () => window.clearInterval(id)
  }, [exchange, interval])

  // 간격을 바꾸는 동안에는 같은 거래소의 보던 차트를 그대로 두어 차트를 새로 만들지 않는다
  const shown = data && data.exchange.toLowerCase() === exchange ? data : null
  const loading = !shown || shown.interval !== interval
  return (
    <section className="tradebot-section">
      <h2>{t('실시간 차트')}</h2>
      <div className="admin-toolbar tradebot-chart-toolbar">
        <div className="segmented" role="group" aria-label={t('거래소')}>
          {CHART_EXCHANGES.map((x) => (
            <button key={x} type="button" aria-pressed={x === exchange} onClick={() => pickExchange(x)}
              data-tip={x === 'binance' ? t('바이낸스 현물 USDT 시세로 봐요') : t('{0} 원화 시세로 봐요. 업비트 봇 체결도 여기에 찍혀요', { 0: t(krwSourceName(data?.exchange === 'UPBIT' ? data.source : undefined)) })}>
              {x === 'binance' ? t('바이낸스') : t('원화')}
            </button>
          ))}
        </div>
      </div>
      <p className="muted small">{t('10초마다 새로 받아요. ▲는 봇의 모의 매수·진입, ▼는 매도·청산이에요. 십자선을 체결이 있는 봉에 대면 이유가 보여요.')}</p>
      {error && <p className="error small" role="alert">{t('차트를 불러오지 못했어요. 10초 뒤 다시 받아요.')}</p>}
      {loading && !error && <p className="muted small" role="status">{t('불러오는 중…')}</p>}
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
          {shown.coins.filter((c) => c.coin === picked).map((c) => (
            <CoinCard key={c.coin} coin={c} currency={shown.currency} exchange={shown.exchange} accounts={accounts}
              interval={interval} intervals={intervals} onInterval={setTimeframe} />
          ))}
        </>
      )}
    </section>
  )
}

function CoinCard({ coin, currency, exchange, accounts, interval, intervals, onInterval }: {
  coin: ChartCoin; currency: string; exchange: string; accounts: BotAccount[]
  interval: ChartInterval; intervals: readonly ChartInterval[]; onInterval: (i: ChartInterval) => void
}) {
  // 계좌는 1분마다 새로 받으니 값이 같으면 같은 배열을 넘겨 차트가 다시 그리지 않게 한다
  const found = botPositions(accounts, exchange, coin.symbol)
  const sig = JSON.stringify(found)
  const positions = useMemo(() => JSON.parse(sig) as typeof found, [sig])
  return (
    <article className="tradebot-chart">
      {coin.error ? <p className="muted small">{t('시세를 받지 못했어요. 잠시 뒤 다시 받아요.')}</p>
        : coin.candles.length === 0 ? <p className="muted small">{t('아직 봉이 없어요.')}</p>
        : <ProChart coin={coin} currency={currency} interval={interval} intervals={intervals} onInterval={onInterval} positions={positions} />}
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
