import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import { emptyScene, estimateSeconds, renderProblems, reportOf, videoFileName, workerOnline } from './shorts'

const scene = (narration: string) => ({ caption: '자막', narration, code: null })

describe('shorts', () => {
  it('예상 길이는 서버와 같게 센다', () => {
    expect(estimateSeconds([scene('가'.repeat(13)), scene('')])).toBeCloseTo(2 + 0.6 + 0.6)
  })

  it('영상 만들기 전 문제를 미리 알려 준다', () => {
    expect(renderProblems('', [emptyScene()])).toEqual([
      '제목을 적어 주세요.',
      '장면이 3개 이상 있어야 영상으로 만들 수 있어요.',
      '1번 장면에 자막이 없어요.',
      '1번 장면에 내레이션이 없어요.',
    ])
    expect(renderProblems('제목', [scene('하나'), scene('둘'), scene('셋')])).toEqual([])
    const long = Array.from({ length: 3 }, () => scene('가'.repeat(150)))
    expect(renderProblems('제목', long)).toEqual(['예상 길이가 71초예요. 60초 안으로 줄여 주세요.'])
  })

  it('워커는 최근 6분 안에 봤을 때만 켜진 것으로 본다', () => {
    const now = Date.parse('2026-10-14T10:00:00Z')
    expect(workerOnline(null, now)).toBe(false)
    expect(workerOnline('2026-10-14T09:58:00Z', now)).toBe(true)
    expect(workerOnline('2026-10-14T09:50:00Z', now)).toBe(false)
  })

  it('파일 이름에 못 쓰는 글자를 뺀다', () => {
    expect(videoFileName('도커: 볼륨/복구?')).toBe('도커 볼륨복구.mp4')
    expect(videoFileName('  ')).toBe('devlog-shorts.mp4')
  })

  it('양산형 검사로 막히면 검사 결과를 꺼낸다', () => {
    const report = { score: 60, passed: false, issues: [{ code: 'DUPLICATE', level: 'BLOCK', message: 'm', hint: null }] }
    expect(reportOf(new ApiError(422, 'SHORTS_NOT_ORIGINAL', 'x', [], { report }))).toEqual(report)
    expect(reportOf(new ApiError(400, 'INVALID', 'x'))).toBeNull()
  })
})
