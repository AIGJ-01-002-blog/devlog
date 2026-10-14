import { describe, expect, it } from 'vitest'
import { attendanceDots, points, reasonLabel, ruleValue, type PointAttendance } from './points'

describe('points', () => {
  it('포인트 숫자에 부호와 P를 붙인다', () => {
    expect(points(1500)).toBe('1,500P')
    expect(points(1500, true)).toBe('+1,500P')
    expect(points(-60, true)).toBe('-60P')
    expect(points(0, true)).toBe('0P')
  })

  it('출석 칸은 오늘을 끝으로 7일이고 출석한 날만 찬다', () => {
    const a: PointAttendance = { today: '2026-10-14', attendedToday: false, streak: 2, streakDays: 7, nextBonusIn: 5, recent: ['2026-10-12', '2026-10-13'] }
    const dots = attendanceDots(a)
    expect(dots).toHaveLength(7)
    expect(dots[0].date).toBe('2026-10-08')
    expect(dots[6]).toEqual({ date: '2026-10-14', done: false, today: true })
    expect(dots.filter((d) => d.done).map((d) => d.date)).toEqual(['2026-10-12', '2026-10-13'])
  })

  it('원장 사유와 규칙 값을 읽는다', () => {
    expect(reasonLabel('STREAK')).toBe('연속 출석 보너스')
    expect(reasonLabel('UNKNOWN')).toBe('UNKNOWN')
    expect(ruleValue([{ code: 'POST', label: '', value: 300, defaultValue: 300, max: 1 }], 'POST')).toBe(300)
    expect(ruleValue([], 'POST')).toBe(0)
  })
})
