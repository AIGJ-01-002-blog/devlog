import { describe, expect, it } from 'vitest'
import {
  accountInterval, changeRate, isStale, markIndex, paperBotForm, paperBotFormError, paperBotSettings, sideUp, type ChartCandle,
} from './tradeBot'

const c = (t: number, o: number, cl: number): ChartCandle => ({ t, o, h: Math.max(o, cl) + 1, l: Math.min(o, cl) - 1, c: cl })

describe('자동매매 화면 (spec 101)', () => {
  it('체결 시각이 들어 있는 봉에 찍고, 차트보다 앞선 체결은 찍지 않는다', () => {
    const cs = [c(1000, 10, 11), c(2000, 11, 12), c(3000, 12, 13)]
    expect(markIndex(cs, 500)).toBeNull()
    expect(markIndex(cs, 1000)).toBe(0)
    expect(markIndex(cs, 2500)).toBe(1)
    expect(markIndex(cs, 9000)).toBe(2)
    expect(markIndex([], 1000)).toBeNull()
  })

  it('구간 등락률은 첫 시가에서 마지막 종가까지', () => {
    expect(changeRate([c(1, 100, 101), c(2, 101, 110)])).toBeCloseTo(10)
    expect(changeRate([])).toBeNull()
  })

  it('매수·롱 진입은 위쪽(초록), 매도·숏 진입·청산은 아래쪽(빨강)', () => {
    expect(['BUY', 'LONG'].map(sideUp)).toEqual([true, true])
    expect(['SELL', 'SHORT', 'CLOSE'].map(sideUp)).toEqual([false, false, false])
  })

  it('계좌 파일 이름에서 봉을 읽고, 15분 넘게 소식이 없으면 멈춘 것으로 본다', () => {
    expect(accountInterval('trade-state-binance-futures-15m.json')).toBe('15m')
    expect(accountInterval('mine.json')).toBeNull()
    const now = Date.parse('2026-10-15T05:00:00Z')
    expect(isStale('2026-10-15T04:50:00Z', now)).toBe(false)
    expect(isStale('2026-10-15T04:40:00Z', now)).toBe(true)
    expect(isStale(null, now)).toBe(true)
  })

  it('모의 봇 기준(#340): %와 비율을 오차 없이 오가고, 서버와 같은 범위로 막는다', () => {
    const form = paperBotForm({ coins: ['BTC', 'ETH'], stopLoss: 0.035, takeProfit: 0.07, interval: '4h', budget: 50_000 })
    expect(form).toEqual({ coins: ['BTC', 'ETH'], stopLossPct: '3.5', takeProfitPct: '7', interval: '4h', budget: '50000' })
    expect(paperBotFormError(form)).toBeNull()
    expect(paperBotSettings(form)).toEqual({ coins: ['BTC', 'ETH'], stopLoss: 0.035, takeProfit: 0.07, interval: '4h', budget: 50_000 })
    expect(paperBotSettings({ ...form, takeProfitPct: '' }).takeProfit).toBe(0)
    expect(paperBotFormError({ ...form, coins: [] })).not.toBeNull()
    expect(paperBotFormError({ ...form, stopLossPct: '0.1' })).not.toBeNull()
    expect(paperBotFormError({ ...form, takeProfitPct: '0' })).toBeNull()
    expect(paperBotFormError({ ...form, takeProfitPct: '0.2' })).not.toBeNull()
    expect(paperBotFormError({ ...form, budget: '4000' })).not.toBeNull()
    expect(paperBotFormError({ ...form, budget: '5000.5' })).not.toBeNull()
  })
})
