import { describe, expect, it } from 'vitest'
import { dayLabels, delta, periodOf } from './dashboard'

describe('dashboard', () => {
  it('기간은 7·30·90일만, 그 밖에는 30일', () => {
    expect(periodOf('7')).toBe(7)
    expect(periodOf('90')).toBe(90)
    expect(periodOf('13')).toBe(30)
    expect(periodOf(null)).toBe(30)
  })

  it('이전 기간과 견준 변화를 말로 보여 준다', () => {
    expect(delta(5, 5)).toEqual({ text: '변화 없음', trend: 'flat' })
    expect(delta(3, 0)).toEqual({ text: '새로 생김', trend: 'up' })
    expect(delta(15, 10)).toEqual({ text: '+50%', trend: 'up' })
    expect(delta(5, 10)).toEqual({ text: '-50%', trend: 'down' })
    expect(delta(1000, 999)).toEqual({ text: '변화 없음', trend: 'flat' })
  })

  it('날짜를 축 이름과 툴팁 이름으로', () => {
    expect(dayLabels('2026-10-08')).toEqual({ label: '10.8', fullLabel: '10월 8일' })
  })
})
