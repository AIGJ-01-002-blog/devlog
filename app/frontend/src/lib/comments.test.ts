import { describe, expect, it } from 'vitest'
import { appendUnique, commentLength, insertComment, removeComment, submitsOnEnter, type CommentView, type EnterKey } from './comments'

const c = (id: number, extra: Partial<CommentView> = {}): CommentView => ({
  id, state: 'NORMAL', content: 'x', createdAt: '2026-10-07T00:00:00Z', edited: false,
  author: { handle: 'h', nickname: 'n', profileImageUrl: null, isPostAuthor: false }, replyTo: null, mine: true,
  replies: null, replyCount: null, repliesNextCursor: null, ...extra,
})

describe('댓글 목록', () => {
  it('글자 수는 이모지를 1자로 센다', () => {
    expect(commentLength(' 😀😀 ')).toBe(2)
  })

  it('이어 붙일 때 겹치는 댓글은 건너뛴다', () => {
    expect(appendUnique([c(1), c(2)], [c(2), c(3)]).map((x) => x.id)).toEqual([1, 2, 3])
  })

  it('답글은 최상위 아래에 넣고 답글 수를 늘린다', () => {
    const list = insertComment([c(1, { replies: [], replyCount: 0 })], c(5), 1)
    expect(list[0].replies!.map((x) => x.id)).toEqual([5])
    expect(list[0].replyCount).toBe(1)
  })

  it('답글이 있는 최상위는 자리로 남고 마지막 답글이 빠지면 자리도 빠진다', () => {
    let list = [c(1, { replies: [c(2), c(3)], replyCount: 2 }), c(4, { replies: [], replyCount: 0 })]
    list = removeComment(list, 4)
    expect(list.map((x) => x.id)).toEqual([1])
    list = removeComment(list, 1)
    expect(list[0].state).toBe('DELETED')
    expect(list[0].content).toBeNull()
    list = removeComment(list, 2)
    expect(list[0].replyCount).toBe(1)
    list = removeComment(list, 3)
    expect(list).toEqual([])
  })
})

const key = (k: Partial<EnterKey> = {}): EnterKey =>
  ({ key: 'Enter', shiftKey: false, ctrlKey: false, metaKey: false, isComposing: false, keyCode: 13, ...k })

describe('submitsOnEnter', () => {
  it('Enter는 등록, Shift+Enter는 줄바꿈', () => {
    expect(submitsOnEnter(key(), false, true)).toBe(true)
    expect(submitsOnEnter(key({ shiftKey: true }), false, true)).toBe(false)
    expect(submitsOnEnter(key({ key: 'a', keyCode: 65 }), false, true)).toBe(false)
  })

  it('한글 조합을 확정하는 Enter로는 등록하지 않는다', () => {
    expect(submitsOnEnter(key({ isComposing: true }), false, true)).toBe(false)
    expect(submitsOnEnter(key({ keyCode: 229 }), false, true)).toBe(false) // Safari
    expect(submitsOnEnter(key(), true, true)).toBe(false) // compositionend 전
  })

  it('터치 기기에서는 Enter가 줄바꿈이고 Ctrl·Cmd+Enter만 등록한다', () => {
    expect(submitsOnEnter(key(), false, false)).toBe(false)
    expect(submitsOnEnter(key({ ctrlKey: true }), false, false)).toBe(true)
    expect(submitsOnEnter(key({ metaKey: true }), false, true)).toBe(true)
  })
})
