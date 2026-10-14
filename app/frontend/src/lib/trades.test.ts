import { describe, expect, it } from 'vitest'
import { fillTime, isDate, pnlClass, qty, tradePeriodOf, winRate, won } from './trades'

const summary = { trades: 0, buys: 0, sells: 0, wins: 0, losses: 0, realizedPnl: 0, fees: 0 }

describe('매매 일지 (#243)', () => {
  it('금액은 원 단위로 반올림하고 이익에만 +를 붙인다', () => {
    expect(won(14600, true)).toBe('+14,600원')
    expect(won(-24300.4, true)).toBe('-24,300원')
    expect(won(0, true)).toBe('0원')
    expect(won(71000)).toBe('71,000원')
  })

  it('손익 색은 반올림한 값으로 정한다', () => {
    expect(pnlClass(1)).toBe('good')
    expect(pnlClass(-1)).toBe('bad')
    expect(pnlClass(0.4)).toBe('flat')
  })

  it('이긴 매도 비율은 손익이 난 매도만 센다', () => {
    expect(winRate(summary)).toBeNull()
    expect(winRate({ ...summary, wins: 2, losses: 1 })).toBe(67)
  })

  it('주소의 기간·날짜를 읽는다', () => {
    expect(tradePeriodOf('7')).toBe(7)
    expect(tradePeriodOf('14')).toBe(30)
    expect(tradePeriodOf(null)).toBe(30)
    expect(isDate('2026-10-14')).toBe(true)
    expect(isDate('2026-10-14x')).toBe(false)
    expect(isDate(null)).toBe(false)
  })

  it('체결 시각은 한국 시간, 수량은 소수점 아래 0을 지운다', () => {
    expect(fillTime('2026-10-14T00:05:00Z')).toBe('09:05')
    expect(qty(10)).toBe('10')
    expect(qty(0.5)).toBe('0.5')
  })
})
