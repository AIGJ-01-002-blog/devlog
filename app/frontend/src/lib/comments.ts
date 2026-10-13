import { api } from './api'

// 댓글 API와 목록 다루기 (docs/21, spec 011). 목록 상태는 화면에서 바로 고친다(다시 받지 않는다).

export type CommentState = 'NORMAL' | 'DELETED' | 'HIDDEN' | 'WITHDRAWN_AUTHOR'

export interface CommentView {
  id: number
  state: CommentState
  /** 삭제된 자리·탈퇴·남이 보는 숨김이면 null */
  content: string | null
  createdAt: string
  edited: boolean
  author: { handle: string; nickname: string; profileImageUrl: string | null; isPostAuthor: boolean } | null
  replyTo: { handle: string | null; nickname: string | null; withdrawn: boolean } | null
  mine: boolean
  /** 최상위에만 있다 */
  replies: CommentView[] | null
  replyCount: number | null
  repliesNextCursor: string | null
}

export interface CommentPage {
  items: CommentView[]
  nextCursor: string | null
  prevCursor: string | null
  commentCount: number
  canWrite: boolean
}

export const MAX_COMMENT = 1000

export const commentsApi = {
  page: (postId: number, q: { cursor?: string; before?: string; around?: string } = {}) => {
    const params = new URLSearchParams(Object.entries(q).filter(([, v]) => v) as [string, string][])
    const qs = params.toString()
    return api<CommentPage>(`/api/posts/${postId}/comments${qs ? `?${qs}` : ''}`)
  },
  replies: (rootId: number, cursor: string) =>
    api<{ items: CommentView[]; nextCursor: string | null }>(`/api/comments/${rootId}/replies?cursor=${encodeURIComponent(cursor)}`),
  create: (postId: number, content: string, replyToCommentId?: number) =>
    api<CommentView>(`/api/posts/${postId}/comments`, { method: 'POST', body: { content, replyToCommentId } }),
  update: (id: number, content: string) => api<CommentView>(`/api/comments/${id}`, { method: 'PATCH', body: { content } }),
  remove: (id: number) => api<void>(`/api/comments/${id}`, { method: 'DELETE' }),
}

/** 서버와 같은 글자 수 (코드 포인트, 이모지 하나 = 1자). */
export function commentLength(s: string): number {
  return [...s.trim()].length
}

/** 이어 붙일 때 이미 있는 댓글은 건너뛴다. */
export function appendUnique(list: CommentView[], more: CommentView[]): CommentView[] {
  const seen = new Set(list.map((c) => c.id))
  return [...list, ...more.filter((c) => !seen.has(c.id))]
}

/** 새 댓글을 넣는다. 답글이면 그 최상위 아래 끝에. */
export function insertComment(list: CommentView[], c: CommentView, rootId: number | null): CommentView[] {
  if (rootId == null) return appendUnique(list, [{ ...c, replies: c.replies ?? [], replyCount: c.replyCount ?? 0 }])
  return list.map((r) => r.id !== rootId ? r : {
    ...r,
    replies: appendUnique(r.replies ?? [], [c]),
    replyCount: (r.replyCount ?? 0) + 1,
  })
}

export function replaceComment(list: CommentView[], c: CommentView): CommentView[] {
  return list.map((r) => r.id === c.id ? { ...r, ...c, replies: r.replies, replyCount: r.replyCount, repliesNextCursor: r.repliesNextCursor }
    : { ...r, replies: r.replies?.map((x) => (x.id === c.id ? c : x)) ?? null })
}

/**
 * 서버의 삭제 규칙을 화면에 그대로 (FR-034·FR-035): 답글이 있는 최상위는 "삭제된 댓글이에요" 자리로,
 * 그 밖에는 빼고, 자리 아래 마지막 답글이 빠지면 자리도 뺀다.
 */
export function removeComment(list: CommentView[], id: number): CommentView[] {
  const out: CommentView[] = []
  for (const r of list) {
    if (r.id === id) {
      if ((r.replyCount ?? 0) > 0) out.push({ ...r, state: 'DELETED', content: null, author: null, replyTo: null, mine: false, edited: false })
      continue
    }
    const replies = r.replies?.filter((x) => x.id !== id) ?? null
    if (replies && r.replies && replies.length !== r.replies.length) {
      const count = (r.replyCount ?? 1) - 1
      if (r.state === 'DELETED' && count === 0) continue
      out.push({ ...r, replies, replyCount: count })
    } else out.push(r)
  }
  return out
}

/** 지우면 보이는 댓글 수가 줄어드는지 (숨긴 댓글은 이미 빠져 있다). */
export function countsTowardTotal(c: CommentView): boolean {
  return c.state === 'NORMAL' || c.state === 'WITHDRAWN_AUTHOR'
}
