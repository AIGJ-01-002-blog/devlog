import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './api'
import { emptyScene, estimateSeconds, hashtags, renderProblems, reportOf, shareVideo, uploadGuide, videoFileName, workerOnline, type ShortsItem } from './shorts'

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

  const ready = (over: Partial<ShortsItem> = {}): ShortsItem => ({
    id: 1, postId: 7, postTitle: '세션', postUrl: '/@minseo/posts/7', title: '배포하면 왜 로그아웃될까',
    scenes: [scene('왜 배포만 하면 다 로그아웃될까요?'), scene('직렬화가 범인이었어요.'), scene('JSON으로 바꿨어요.')],
    status: 'READY', attempts: 1, videoUrl: '/media/shorts/2026/10/a.mp4', durationMs: 31500, videoBytes: 1000, error: null,
    estimatedSeconds: 30, queuedAt: null, renderedAt: null, createdAt: '', updatedAt: '', version: 1,
    postTags: ['spring', 'spring-boot', 'c++', '세션', 'redis', 'docker', 'k8s'], uploads: [], ...over,
  })

  it('태그를 해시태그로 바꿀 때 못 쓰는 글자를 빼고 한도까지만 쓴다', () => {
    expect(hashtags(['spring', 'spring-boot', 'c++', '세션', 'spring'], 10)).toEqual(['#spring', '#springboot', '#c', '#세션'])
    expect(hashtags(['a', 'b', 'c', 'd'], 3)).toEqual(['#a', '#b', '#c'])
    expect(hashtags(['++'], 3)).toEqual([])
  })

  it('YouTube는 제목 칸과 누를 수 있는 글 링크를 설명에 넣는다', () => {
    const g = uploadGuide(ready(), 'YOUTUBE', 'https://devlog.life')
    expect(g.title).toBe('배포하면 왜 로그아웃될까')
    expect(g.caption).toBe('왜 배포만 하면 다 로그아웃될까요?\n\n전체 글: https://devlog.life/@minseo/posts/7\n\n#spring #springboot #c')
    expect(g.pinnedComment).toContain('https://devlog.life/@minseo/posts/7')
    expect(g.checks.every((c) => c.ok)).toBe(true)
    expect(g.steps.length).toBeGreaterThan(3)
  })

  it('Instagram·TikTok은 캡션 링크를 못 누르니 고정 댓글로 안내하고 해시태그는 5개까지', () => {
    for (const p of ['INSTAGRAM', 'TIKTOK'] as const) {
      const g = uploadGuide(ready(), p, 'https://devlog.life')
      expect(g.title).toBeNull()
      expect(g.caption.startsWith('배포하면 왜 로그아웃될까\n\n')).toBe(true)
      expect(g.caption).not.toContain('https://')
      expect(g.caption).toContain('고정 댓글')
      expect(g.tags).toHaveLength(5)
      expect(g.pinnedComment).toContain('https://devlog.life/@minseo/posts/7')
    }
  })

  it('글 없이 만든 쇼츠는 사이트 주소로 돌아오게 하고, 넘친 한도는 표시한다', () => {
    const g = uploadGuide(ready({ postUrl: null, postId: null, postTags: [], title: '가'.repeat(60) }), 'YOUTUBE', 'https://devlog.life')
    expect(g.pinnedComment).toContain('https://devlog.life/')
    expect(g.tags).toEqual([])
    const long = uploadGuide(ready({ durationMs: 200_000 }), 'YOUTUBE', 'https://devlog.life')
    expect(long.checks[0].ok).toBe(false)
  })
})

describe('shareVideo', () => {
  it('파일 공유를 못 하는 브라우저면 영상을 받기 전에 끝낸다', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    vi.stubGlobal('navigator', { share: vi.fn(), canShare: () => false })
    expect(await shareVideo('/v.mp4', 'v.mp4', 't')).toBe('unsupported')
    expect(fetchSpy).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('탭 허용 시간이 지나 막히면 받아 둔 파일로 다시 누르게 한다', async () => {
    const fetchSpy = vi.fn(async () => ({ ok: true, blob: async () => new Blob(['x']) }))
    const share = vi.fn().mockRejectedValueOnce(new DOMException('late', 'NotAllowedError')).mockResolvedValueOnce(undefined)
    vi.stubGlobal('fetch', fetchSpy)
    vi.stubGlobal('navigator', { share, canShare: () => true })
    expect(await shareVideo('/w.mp4', 'w.mp4', 't')).toBe('retry')
    expect(await shareVideo('/w.mp4', 'w.mp4', 't')).toBe('shared')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    vi.unstubAllGlobals()
  })
})
