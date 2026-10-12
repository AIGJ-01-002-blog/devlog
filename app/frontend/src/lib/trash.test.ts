import { describe, expect, it } from 'vitest'
import { daysLeft, isBlankDraft, trashedMessage } from './trash'

describe('daysLeft', () => {
  const now = Date.parse('2026-10-07T00:00:00Z')
  it('남은 날을 올림한다', () => {
    expect(daysLeft('2026-11-06T00:00:00Z', now)).toBe(30)
    expect(daysLeft('2026-10-07T00:00:01Z', now)).toBe(1)
  })
  it('지났으면 0이다', () => {
    expect(daysLeft('2026-10-06T00:00:00Z', now)).toBe(0)
  })
})

describe('trashedMessage', () => {
  it('빈 임시글은 바로 지웠다고 알린다', () => {
    expect(trashedMessage({ result: 'DELETED_EMPTY', deletedAt: null, purgeAt: null })).toContain('바로 지웠어요')
    expect(trashedMessage({ result: 'TRASHED', deletedAt: 'x', purgeAt: 'y' })).toContain('휴지통')
  })
})

describe('isBlankDraft', () => {
  it('제목·본문이 공백뿐인 임시글만 빈 글이다', () => {
    expect(isBlankDraft('DRAFT', { title: '', contentMd: '' })).toBe(true)
    expect(isBlankDraft('DRAFT', { title: '  ', contentMd: '\n\t' })).toBe(true)
    expect(isBlankDraft('DRAFT', { title: 'a', contentMd: '' })).toBe(false)
    expect(isBlankDraft('DRAFT', { title: '', contentMd: '한 줄' })).toBe(false)
    expect(isBlankDraft('PUBLISHED', { title: '', contentMd: '' })).toBe(false)
  })
})
