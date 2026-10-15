import { describe, expect, it } from 'vitest'
import { intervalLabel, replayPrompt } from '../lib/shorts'
import { replayTime } from './StudioReplay'

describe('매매 재현 영상 (#265)', () => {
  it('체결 시각을 한국 시간 월/일 시:분으로 보인다', () => {
    expect(replayTime('2026-10-13T01:15:00Z')).toBe('10/13 10:15')
    expect(replayTime('2026-10-13T15:05:00Z')).toBe('10/14 00:05')
  })

  it('봉 이름과 AI에게 보낼 부탁', () => {
    expect(intervalLabel('15m')).toBe('15분봉')
    expect(intervalLabel('1d')).toBe('일봉')
    expect(replayPrompt(12)).toContain('쇼츠 12번')
    expect(replayPrompt(12)).toContain('get_shorts')
  })
})
