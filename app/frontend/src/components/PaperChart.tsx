import { useEffect, useMemo, useRef, useState } from 'react'
import { t } from '../lib/i18n'
import { loadPaperChart } from '../lib/paper'
import { Link } from '../lib/router'
import type { ChartPosition } from '../lib/proChart'
import { KRW_INTERVALS, loadPaperBot, setPaperBot, type ChartInterval, type CoinChart } from '../lib/tradeBot'
import { ProChart } from './ProChart'

/** 서버가 캔들을 10초씩 들고 있으니 화면도 10초마다 다시 받는다 */
const REFRESH_MS = 10_000

/**
 * 포인트 모의투자 차트 (#324). 고른 코인의 원화 캔들 위에 내가 포인트로 낸 체결과, 서버 모의 봇을 켰다면 봇 체결을 함께 찍는다.
 * 봇은 포인트를 쓰지 않고 봇 전용 가상 돈으로 돈다. 켜고 끄는 스위치는 자동매매 화면과 같은 상태다.
 *
 * #354부터 거래소처럼 이동평균·거래량·십자선·내 평단선을 함께 그린다(ProChart).
 *
 * @param refresh 주문이 체결될 때마다 바뀌는 값. 바뀌면 바로 다시 받아 방금 체결을 찍는다
 * @param position 이 코인을 갖고 있으면 내 평단과 수량. 평단선과 손익을 그린다
 */
export function PaperChart({ symbol, refresh, position }: { symbol: string; refresh: number; position?: ChartPosition | null }) {
  const [interval, setTimeframe] = useState<ChartInterval>('15m')
  const [data, setData] = useState<CoinChart | null>(null)
  const [error, setError] = useState(false)
  const [bot, setBot] = useState<boolean | null>(null)
  const [botBusy, setBotBusy] = useState(false)
  const [botError, setBotError] = useState(false)
  // 봉 간격을 빨리 바꿔도 마지막 요청의 응답만 쓴다
  const seq = useRef(0)

  useEffect(() => {
    const load = () => {
      const id = ++seq.current
      return loadPaperChart(interval)
        .then((d) => { if (id === seq.current) { setData(d); setError(false) } })
        .catch(() => { if (id === seq.current) setError(true) })
    }
    void load()
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void load() }, REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [interval, refresh])

  useEffect(() => {
    let alive = true
    loadPaperBot().then((b) => { if (alive) setBot(b.enabled) }).catch(() => { if (alive) setBot(null) })
    return () => { alive = false }
  }, [])

  const toggleBot = async () => {
    if (bot === null) return
    setBotBusy(true)
    setBotError(false)
    try {
      setBot((await setPaperBot(!bot)).enabled)
    } catch {
      // 바꾸지 못하면 지금 상태를 그대로 두고 알린다
      setBotError(true)
    } finally {
      setBotBusy(false)
    }
  }

  const positions = useMemo(() => (position ? [position] : []), [position])
  const botSwitch = (
    <>
      {bot !== null && (
        <label className="paper-chart-bot small" data-tip={t('서버 모의 봇이 5분마다 내 기본 크레딧으로 사고팔아요. 상점에서 쓰는 크레딧은 쓰지 않아요')}>
          <input type="checkbox" checked={bot} disabled={botBusy} onChange={() => void toggleBot()} />
          {t('모의 봇 켜기')}
        </label>
      )}
      <Link to="/trade" className="small" data-tip={t('봇 규칙과 성적은 자동매매 화면에서 봐요')}>{t('봇 자세히')}</Link>
    </>
  )

  // 간격을 바꾸는 동안에는 보던 차트를 그대로 두어 차트를 새로 만들지 않는다
  const shown = data
  const coin = shown?.coins.find((c) => c.coin === symbol) ?? null
  return (
    <section className="paper-chart tradebot-chart" aria-label={t('차트')}>
      <h2 className="paper-chart-title">{t('{0} 차트', { 0: symbol })}</h2>
      {botError && <p className="error small" role="alert">{t('모의 봇을 바꾸지 못했어요. 다시 시도해 주세요.')}</p>}
      {error && <p className="error small" role="alert">{t('차트를 불러오지 못했어요. 10초 뒤 다시 받아요.')}</p>}
      {(!shown || shown.interval !== interval) && !error && <p className="muted small" role="status">{t('불러오는 중…')}</p>}
      {shown && coin && (coin.error
        ? <p className="muted small">{t('시세를 받지 못했어요. 잠시 뒤 다시 받아요.')}</p>
        : <ProChart coin={coin} currency={shown.currency} interval={interval} onInterval={setTimeframe} intervals={KRW_INTERVALS}
            positions={positions} extra={botSwitch} />)}
    </section>
  )
}
