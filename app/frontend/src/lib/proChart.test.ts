import { describe, expect, it } from 'vitest'
import { candleRate, chartTime, compact, maAt, marksByCandle, movingAverage, positionPnl, withAlpha } from './proChart'
import { markIndex, type ChartCandle } from './tradeBot'

const candles = (closes: number[]): ChartCandle[] => closes.map((c, i) => ({ t: i * 60_000, o: c, h: c, l: c, c, v: 1 }))

describe('proChart', () => {
  it('이동평균은 봉이 n개가 된 뒤부터 그린다', () => {
    const cs = candles([1, 2, 3, 4, 5])
    expect(movingAverage(cs, 3)).toEqual([{ i: 2, value: 2 }, { i: 3, value: 3 }, { i: 4, value: 4 }])
    expect(maAt(cs, 4, 3)).toBe(4)
    expect(maAt(cs, 1, 3)).toBeNull()
    expect(movingAverage(cs, 99)).toEqual([])
  })

  it('한국 시간으로 보이게 9시간을 민 초로 바꾼다', async () => {
    const { timeLabel } = await import('./proChart')
    expect(timeLabel(chartTime(Date.UTC(2026, 9, 16, 4, 45, 7)), false)).toBe('10. 16. 13:45')
    expect(timeLabel(chartTime(Date.UTC(2026, 9, 16, 4, 45, 7)), true)).toBe('10. 16. 13:45:07')
    expect(chartTime(0)).toBe(9 * 3600)
    expect(chartTime(1_500)).toBe(9 * 3600 + 1)
  })

  it('숏은 내려야 이익이다', () => {
    expect(positionPnl({ avgPrice: 100, quantity: 2, side: '', label: '' }, 110)).toEqual({ pnl: 20, rate: 10 })
    expect(positionPnl({ avgPrice: 100, quantity: 2, side: 'SHORT', label: '' }, 110)).toEqual({ pnl: -20, rate: -10 })
  })

  it('같은 봉의 체결을 모으고 차트 밖 체결은 뺀다', () => {
    const cs = candles([1, 2, 3])
    const got = marksByCandle(cs, [
      { t: -1, side: 'BUY', price: 1, text: '' },
      { t: 60_000, side: 'BUY', price: 2, text: '', by: 'ME' },
      { t: 61_000, side: 'SELL', price: 2, text: '', by: 'BOT' },
    ], markIndex)
    expect([...got.keys()]).toEqual([1])
    expect(got.get(1)).toHaveLength(2)
  })

  it('숫자·색 모양', () => {
    expect(compact(1234)).toBe('1.23K')
    expect(compact(12_345_678)).toBe('12.35M')
    expect(compact(0.5)).toBe('0.500')
    expect(candleRate({ t: 0, o: 100, h: 0, l: 0, c: 105, v: 0 })).toBe(5)
    expect(withAlpha('#0a7d64', 0.5)).toBe('#0a7d6480')
    expect(withAlpha('rgb(1, 2, 3)', 0.5)).toBe('rgb(1, 2, 3)')
  })
})

describe('가격 자리', () => {
  it('원화와 USDT', async () => {
    const { pricePrecision, priceText } = await import('./proChart')
    expect(pricePrecision(100_000_000, 'KRW')).toBe(0)
    expect(pricePrecision(0.2, 'USDT')).toBe(5)
    expect(pricePrecision(67_000, 'USDT')).toBe(2)
    expect(priceText(1234.5, 2)).toBe('1,234.50')
  })
})

describe('봇 평단선', () => {
  it('같은 거래소·종목의 포지션만 계좌마다 하나씩', async () => {
    const { botPositions } = await import('./proChart')
    const acc = (account: string, exchange: string, market: 'SPOT' | 'FUTURES', symbol: string, avgPrice: number | null) => ({
      account, exchange, market, currency: 'USDT', cash: 0, unrealizedPnl: null, rules: [], reportedAt: '',
      positions: [{ symbol, side: market === 'FUTURES' ? 'SHORT' : '', quantity: 0.5, avgPrice, price: null, unrealizedPnl: null }],
    })
    const got = botPositions([
      acc('server-paper-binance-futures-1h', 'BINANCE', 'FUTURES', 'BTCUSDT', 100),
      acc('trade-state-binance-spot-4h.json', 'BINANCE', 'SPOT', 'BTCUSDT', 90),
      acc('server-paper-upbit-spot-1h', 'UPBIT', 'SPOT', 'KRW-BTC', 1000),
      acc('server-paper-binance-spot-1h', 'BINANCE', 'SPOT', 'ETHUSDT', 5),
      acc('server-paper-binance-spot-15m', 'BINANCE', 'SPOT', 'BTCUSDT', null),
    ], 'BINANCE', 'BTCUSDT')
    expect(got.map((p) => p.label)).toEqual(['서버 봇 선물 1h', '내 컴퓨터 봇 현물 4h'])
    expect(got[0].side).toBe('SHORT')
  })
})
