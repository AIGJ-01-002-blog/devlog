import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  CandlestickSeries, ColorType, CrosshairMode, HistogramSeries, LineSeries, LineStyle, createChart, createSeriesMarkers,
  type IChartApi, type IPriceLine, type ISeriesApi, type ISeriesMarkersPluginApi, type SeriesMarker, type Time, type UTCTimestamp,
} from 'lightweight-charts'
import { t } from '../lib/i18n'
import {
  MA_PERIODS, candleRate, chartTime, timeLabel, compact, maAt, marksByCandle, movingAverage, positionPnl, pricePrecision, priceText, withAlpha,
  type ChartPosition,
} from '../lib/proChart'
import { CHART_INTERVALS, INTERVAL_LABELS, eventTime, markIndex, sideLabel, sideUp, type ChartCoin, type ChartInterval } from '../lib/tradeBot'
import { money, pnlClass } from '../lib/trades'

/** 테마 색. styles.css의 토큰을 읽어 차트에 넘긴다(라이브러리는 캔버스에 그려 CSS 변수를 직접 못 쓴다) */
interface Palette { surface: string; text: string; muted: string; border: string; up: string; down: string; ma: string[] }

function palette(): Palette {
  const css = getComputedStyle(document.documentElement)
  const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback
  return {
    surface: v('--color-surface', 'white'),
    text: v('--color-text', 'black'),
    muted: v('--color-text-muted', 'gray'),
    border: v('--color-border', 'silver'),
    up: v('--color-up', 'green'),
    down: v('--color-down', 'red'),
    ma: [v('--chart-ma-7', 'goldenrod'), v('--chart-ma-25', 'deeppink'), v('--chart-ma-99', 'blueviolet')],
  }
}

/** 기본값을 매번 새 배열로 만들면 그릴 때마다 봉을 다시 넣으므로 하나를 함께 쓴다 */
const NO_POSITIONS: ChartPosition[] = []

interface Handles {
  chart: IChartApi
  candles: ISeriesApi<'Candlestick'>
  volume: ISeriesApi<'Histogram'>
  mas: ISeriesApi<'Line'>[]
  markers: ISeriesMarkersPluginApi<Time>
  lines: IPriceLine[]
}

export interface ProChartProps {
  coin: ChartCoin
  currency: string
  interval: ChartInterval
  onInterval: (i: ChartInterval) => void
  /** 고를 수 있는 간격. 빠진 간격은 버튼을 끄고 이유를 툴팁으로 보인다 */
  intervals?: readonly ChartInterval[]
  /** 이 코인의 내 포지션·봇 포지션. 평단선과 손익을 그린다 */
  positions?: ChartPosition[]
  /** 간격 버튼 옆에 둘 것(모의 봇 스위치 등) */
  extra?: ReactNode
}

/**
 * 거래소처럼 자세한 차트 (#354). 캔들·이동평균 7/25/99·거래량·십자선(가격·시간 라벨)·평단선·내 체결과 봇 체결을 그린다.
 * TradingView Lightweight Charts(캔버스)로 그린다: 십자선·확대·끌기·축 라벨을 직접 만들 필요가 없고 45KB 남짓으로 가볍다.
 * 그리기 도구는 넣지 않는다. 라이선스(Apache 2.0)가 요구하는 TradingView 표시는 차트 구석 로고로 남긴다.
 */
export function ProChart({ coin, currency, interval, onInterval, intervals = CHART_INTERVALS, positions = NO_POSITIONS, extra }: ProChartProps) {
  const box = useRef<HTMLDivElement>(null)
  const handles = useRef<Handles | null>(null)
  const times = useRef(new Map<number, number>())
  const [hover, setHover] = useState<number | null>(null)
  const secondsRef = useRef(interval === '1s')
  secondsRef.current = interval === '1s'
  const cs = coin.candles
  const last = cs.length - 1
  const precision = pricePrecision(cs[last]?.c ?? 1, currency)

  // 차트는 한 번 만들고, 테마가 바뀌면 색만 다시 칠한다
  useEffect(() => {
    const el = box.current
    if (!el) return
    const p = palette()
    const chart = createChart(el, {
      autoSize: true,
      layout: { background: { type: ColorType.Solid, color: p.surface }, textColor: p.muted, fontFamily: 'inherit', attributionLogo: true },
      grid: { vertLines: { color: withAlpha(p.border, 0.6) }, horzLines: { color: withAlpha(p.border, 0.6) } },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: p.border, scaleMargins: { top: 0.08, bottom: 0.25 } },
      timeScale: { borderColor: p.border, timeVisible: true, rightOffset: 4 },
      // 휴대폰에서 세로로 밀면 화면이 내려가게 둔다(차트는 가로로만 끈다)
      handleScroll: { vertTouchDrag: false },
      localization: { locale: 'ko-KR', timeFormatter: (time: Time) => timeLabel(time as number, secondsRef.current) },
    })
    const candles = chart.addSeries(CandlestickSeries, {
      upColor: p.up, downColor: p.down, borderUpColor: p.up, borderDownColor: p.down, wickUpColor: p.up, wickDownColor: p.down,
    })
    const volume = chart.addSeries(HistogramSeries, { priceScaleId: 'vol', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false })
    chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    const mas = MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, {
      color: p.ma[k], lineWidth: 1, lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false,
    }))
    const markers = createSeriesMarkers(candles, [])
    handles.current = { chart, candles, volume, mas, markers, lines: [] }
    chart.subscribeCrosshairMove((param) => {
      const i = param.time === undefined ? undefined : times.current.get(param.time as number)
      setHover(i ?? null)
    })

    const repaint = () => {
      const q = palette()
      chart.applyOptions({
        layout: { background: { type: ColorType.Solid, color: q.surface }, textColor: q.muted },
        grid: { vertLines: { color: withAlpha(q.border, 0.6) }, horzLines: { color: withAlpha(q.border, 0.6) } },
        rightPriceScale: { borderColor: q.border },
        timeScale: { borderColor: q.border },
      })
      candles.applyOptions({ upColor: q.up, downColor: q.down, borderUpColor: q.up, borderDownColor: q.down, wickUpColor: q.up, wickDownColor: q.down })
      mas.forEach((s, k) => s.applyOptions({ color: q.ma[k] }))
    }
    const watch = new MutationObserver(repaint)
    watch.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    const media = window.matchMedia?.('(prefers-color-scheme: dark)')
    media?.addEventListener('change', repaint)
    return () => {
      watch.disconnect()
      media?.removeEventListener('change', repaint)
      chart.remove()
      handles.current = null
    }
  }, [])

  // 1초 봉일 때만 초까지 보인다
  useEffect(() => {
    handles.current?.chart.applyOptions({ timeScale: { secondsVisible: interval === '1s' } })
  }, [interval])

  // 봉·체결·평단선. 10초마다 새로 받아도 보던 구간(확대·끌기)은 그대로 둔다
  useEffect(() => {
    const h = handles.current
    if (!h) return
    const p = palette()
    times.current = new Map(cs.map((c, i) => [chartTime(c.t), i]))
    const time = (i: number) => chartTime(cs[i].t) as UTCTimestamp
    h.candles.applyOptions({ priceFormat: { type: 'price', precision, minMove: 10 ** -precision } })
    h.candles.setData(cs.map((c, i) => ({ time: time(i), open: c.o, high: c.h, low: c.l, close: c.c })))
    h.volume.setData(cs.map((c, i) => ({ time: time(i), value: c.v ?? 0, color: withAlpha(c.c >= c.o ? p.up : p.down, 0.45) })))
    MA_PERIODS.forEach((n, k) => h.mas[k].setData(movingAverage(cs, n).map((x) => ({ time: time(x.i), value: x.value }))))

    const list: SeriesMarker<Time>[] = []
    for (const [i, ms] of [...marksByCandle(cs, coin.marks, markIndex)].sort((a, b) => a[0] - b[0])) {
      for (const m of ms) {
        const up = sideUp(m.side)
        list.push({
          time: time(i), position: up ? 'belowBar' : 'aboveBar', shape: up ? 'arrowUp' : 'arrowDown',
          color: up ? p.up : p.down, text: m.by === 'ME' ? t('나') : t('봇'), size: m.by === 'ME' ? 1.4 : 1,
        })
      }
    }
    h.markers.setMarkers(list)

    for (const line of h.lines) h.candles.removePriceLine(line)
    h.lines = positions.map((pos) => h.candles.createPriceLine({
      price: pos.avgPrice, color: p.text, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: true,
      title: t('{0} 평단', { 0: pos.label }),
    }))
  }, [cs, coin.marks, positions, precision])

  // 종목·간격을 바꾸면 처음 모습(전체 보기)으로 돌아간다
  const view = `${coin.symbol}:${interval}:${cs.length > 0}`
  useEffect(() => {
    handles.current?.chart.timeScale().fitContent()
  }, [view])

  const marks = useMemo(() => marksByCandle(cs, coin.marks, markIndex), [cs, coin.marks])
  const at = hover ?? last
  const bar = cs[at]
  const day = coin.day
  const lastPrice = cs[last]?.c

  return (
    <div className="prochart">
      <div className="prochart-head">
        <div className="prochart-title">
          <strong>{coin.symbol}</strong>
          <span className="muted small">{t(coin.name)}</span>
          {lastPrice !== undefined && (
            <span className={`prochart-price pnl ${pnlClass(candleRate(cs[last]))}`} data-tip={t('마지막 체결가예요')}>{priceText(lastPrice, precision)}</span>
          )}
        </div>
        {day && (
          <dl className="prochart-day small">
            <div data-tip={t('최근 24시간 가장 높은 값')}><dt>{t('24h 고가')}</dt><dd>{priceText(day.high, precision)}</dd></div>
            <div data-tip={t('최근 24시간 가장 낮은 값')}><dt>{t('24h 저가')}</dt><dd>{priceText(day.low, precision)}</dd></div>
            <div data-tip={t('최근 24시간 거래된 코인 수')}><dt>{t('24h 거래량({0})', { 0: coin.coin })}</dt><dd>{compact(day.volume)}</dd></div>
            <div data-tip={t('최근 24시간 거래 금액(거래량 × 값)')}><dt>{t('24h 거래대금({0})', { 0: currency })}</dt><dd>{compact(day.quoteVolume)}</dd></div>
          </dl>
        )}
      </div>
      <div className="prochart-tools">
        <div className="segmented prochart-intervals" role="group" aria-label={t('봉 간격')}>
          {CHART_INTERVALS.map((x) => {
            const ok = intervals.includes(x)
            return (
              <button key={x} type="button" aria-pressed={x === interval} disabled={!ok} onClick={() => onInterval(x)}
                data-tip={ok ? t('봉 하나가 {0}예요', { 0: INTERVAL_LABELS[x] }) : t('원화 시세는 {0} 봉을 주지 않아요', { 0: INTERVAL_LABELS[x] })}>
                {INTERVAL_LABELS[x]}
              </button>
            )
          })}
        </div>
        {extra}
      </div>
      {bar && (
        <p className="prochart-ohlc small" aria-live="off">
          <span className="muted">{eventTime(new Date(bar.t).toISOString())}</span>
          <span>{t('시')} <b>{priceText(bar.o, precision)}</b></span>
          <span>{t('고')} <b>{priceText(bar.h, precision)}</b></span>
          <span>{t('저')} <b>{priceText(bar.l, precision)}</b></span>
          <span>{t('종')} <b>{priceText(bar.c, precision)}</b></span>
          <span className={`pnl ${pnlClass(candleRate(bar))}`}>{candleRate(bar) > 0 ? '+' : ''}{candleRate(bar).toFixed(2)}%</span>
          <span>{t('거래량')} <b>{compact(bar.v ?? 0)}</b></span>
        </p>
      )}
      {bar && (
        <p className="prochart-ma small">
          {MA_PERIODS.map((n, k) => {
            const v = maAt(cs, at, n)
            return (
              <span key={n} className={`prochart-ma-${k}`} data-tip={t('최근 {0}개 봉 종가의 평균이에요', { 0: n })}>
                MA({n}) {v === null ? '-' : priceText(v, precision)}
              </span>
            )
          })}
        </p>
      )}
      {lastPrice !== undefined && positions.map((pos, k) => {
        const { pnl: raw, rate } = positionPnl(pos, lastPrice)
        // 원화는 원 단위, USDT는 센트까지만 보인다
        const pnl = currency === 'KRW' ? Math.round(raw) : Math.round(raw * 100) / 100
        return (
          <p key={`${k}:${pos.label}`} className="prochart-position small" data-tip={t('점선이 평단(손익분기)이에요. 손익은 마지막 값 기준이에요')}>
            <span>{pos.label}{pos.side ? ` · ${sideLabel(pos.side)}` : ''}</span>
            <span>{t('평단')} <b>{priceText(pos.avgPrice, precision)}</b></span>
            <span className={`pnl ${pnlClass(pnl, currency)}`}>{t('손익')} <b>{money(pnl, currency, true)}</b> ({rate > 0 ? '+' : ''}{rate.toFixed(2)}%)</span>
          </p>
        )
      })}
      <div ref={box} className="prochart-canvas" role="img"
        aria-label={t('{0} 차트, 마지막 {1}', { 0: t(coin.name), 1: lastPrice === undefined ? '-' : money(lastPrice, currency) })} />
      {hover !== null && (marks.get(hover)?.length ?? 0) > 0 && (
        <ul className="prochart-fills small" aria-label={t('이 봉의 체결')}>
          {marks.get(hover)!.map((m, k) => (
            <li key={k}>
              <span className={sideUp(m.side) ? 'trades-buy' : 'trades-sell'}>{m.by === 'ME' ? t('나') : t('봇')} {sideLabel(m.side)}</span>
              <span>{money(m.price, currency)}</span>
              {m.text && <span className="muted">{m.text}</span>}
            </li>
          ))}
        </ul>
      )}
      <p className="muted small prochart-legend">
        <span>{t('▲▼ 나: 내 체결 · 봇: 봇 체결')}</span>
        <span>{t('끌어서 지난 봉 보기, 휠·두 손가락으로 확대')}</span>
      </p>
    </div>
  )
}
