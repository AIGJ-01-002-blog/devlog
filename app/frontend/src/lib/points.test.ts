import { describe, expect, it } from 'vitest'
import { amountOf, attendanceDots, attendanceReward, badgeName, credits, kstToday, points, reasonLabel, ruleValue, type PointAttendance } from './points'

describe('points', () => {
  it('포인트 숫자에 부호와 P를 붙인다', () => {
    expect(points(1500)).toBe('1,500P')
    expect(points(1500, true)).toBe('+1,500P')
    expect(points(-60, true)).toBe('-60P')
    expect(points(0, true)).toBe('0P')
  })

  it('출석 칸은 오늘을 끝으로 7일이고 출석한 날만 찬다', () => {
    const a: PointAttendance = { today: '2026-10-14', attendedToday: false, streak: 2, todayReward: 900, nextReward: 1600, maxReward: 13_322_500, maxStreak: 365, recent: ['2026-10-12', '2026-10-13'] }
    const dots = attendanceDots(a)
    expect(dots).toHaveLength(7)
    expect(dots[0].date).toBe('2026-10-08')
    expect(dots[6]).toEqual({ date: '2026-10-14', done: false, today: true })
    expect(dots.filter((d) => d.done).map((d) => d.date)).toEqual(['2026-10-12', '2026-10-13'])
  })

  it('원장 사유와 규칙 값을 읽는다', () => {
    expect(reasonLabel('ATTENDANCE')).toBe('출석')
    expect(reasonLabel('UNKNOWN')).toBe('UNKNOWN')
    expect(ruleValue([{ code: 'POST', label: '', value: 300, defaultValue: 300, max: 1 }], 'POST')).toBe(300)
    expect(ruleValue([], 'POST')).toBe(0)
  })

  it('크레딧은 상점 지갑 금액이고 출석 보상은 100 × 연속일²에 상한까지다', () => {
    expect(credits(1500)).toBe('1,500크레딧')
    expect(credits(100, true)).toBe('+100크레딧')
    expect(amountOf('SHOP', -60, true)).toBe('-60크레딧')
    expect(amountOf('INVEST_ONLY', 1000)).toBe('1,000P')
    expect(attendanceReward(100, 13_322_500, 1)).toBe(100)
    expect(attendanceReward(100, 13_322_500, 3)).toBe(900)
    expect(attendanceReward(100, 13_322_500, 365)).toBe(13_322_500)
    expect(attendanceReward(100, 13_322_500, 400)).toBe(13_322_500)
    expect(attendanceReward(100, 13_322_500, 0)).toBe(0)
  })

  it('휘장 이름과 한국 날짜', () => {
    expect(badgeName(20)).toBe('20일 연속 출석')
    expect(badgeName(365)).toBe('1년 개근')
    // 한국은 UTC+9라서 UTC 15시는 다음 날이다
    expect(kstToday(new Date('2026-10-14T15:30:00Z'))).toBe('2026-10-15')
    expect(kstToday(new Date('2026-10-14T14:59:00Z'))).toBe('2026-10-14')
  })
})
