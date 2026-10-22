import { describe, expect, it } from 'vitest'
import type { ShortsItem, ShortsStatus } from '../lib/shorts'
import { draftQueue } from './StudioQueue'

const item = (id: number, status: ShortsStatus, createdAt: string): ShortsItem => ({
  id, postId: null, postTitle: null, postUrl: null, title: `대본 ${id}`, scenes: [], status, attempts: 0, videoUrl: null,
  durationMs: null, videoBytes: null, error: null, estimatedSeconds: 30, queuedAt: null, renderedAt: null, createdAt,
  updatedAt: createdAt, version: 1, postTags: [], uploads: [], replay: null, batchId: null, topic: null, character: null,
  outro: true, onBlog: true, playlist: null,
})

// spec 107: 대본 큐는 "대본" 상태만, 먼저 쓴 순서대로(같으면 번호 순)
describe('draftQueue', () => {
  it('대본 상태만 남기고 먼저 쓴 순서로 줄 세운다', () => {
    const items = [
      item(5, 'DRAFT', '2026-10-21T03:00:00Z'), item(2, 'READY', '2026-10-01T00:00:00Z'),
      item(3, 'DRAFT', '2026-10-20T00:00:00Z'), item(4, 'QUEUED', '2026-10-19T00:00:00Z'),
      item(1, 'DRAFT', '2026-10-21T03:00:00Z'), item(6, 'FAILED', '2026-10-18T00:00:00Z'),
    ]
    expect(draftQueue(items).map((it) => it.id)).toEqual([3, 1, 5])
  })
})
