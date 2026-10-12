import { api } from './api'
import { localDrafts } from './localDrafts'
import { t } from './i18n'

// 글 삭제·휴지통 (007). 삭제는 휴지통으로 옮기고 30일 뒤 완전히 지워진다.

export type TrashOutcome = 'TRASHED' | 'ALREADY_TRASHED' | 'DELETED_EMPTY'

export interface TrashResult {
  result: TrashOutcome
  deletedAt: string | null
  purgeAt: string | null
}

export const TRASH_CONFIRM = t('휴지통으로 옮길까요? 30일 안에는 복구할 수 있어요.')
export const PURGE_CONFIRM = t('완전히 삭제할까요? 댓글·좋아요도 함께 지워지고 되돌릴 수 없어요.')

/** 휴지통으로 옮긴다. 이 기기에 남은 작성 데이터도 지워 다시 열었을 때 되살아나지 않게 한다. */
export async function trashPost(id: number, memberId?: number): Promise<TrashResult> {
  const r = await api<TrashResult>(`/api/posts/${id}`, { method: 'DELETE' })
  if (memberId != null) await localDrafts.remove(memberId, id)
  return r
}

export function restorePost(id: number): Promise<{ status: 'DRAFT' | 'PUBLISHED'; visibility: string | null }> {
  return api(`/api/posts/${id}/restore`, { method: 'POST' })
}

export function purgePost(id: number): Promise<unknown> {
  return api(`/api/posts/${id}/permanent`, { method: 'DELETE' })
}

/** 완전 삭제까지 남은 날 (올림, 최소 0). "D-3"처럼 보여 준다. */
export function daysLeft(purgeAt: string, now = Date.now()): number {
  return Math.max(0, Math.ceil((new Date(purgeAt).getTime() - now) / 86_400_000))
}

export function trashedMessage(r: TrashResult): string {
  return r.result === 'DELETED_EMPTY' ? t('빈 임시글이라 바로 지웠어요.') : t('휴지통으로 옮겼어요. 30일 안에 복구할 수 있어요.')
}

/** 아무것도 쓰지 않은 임시글인지 (091). 화면은 제목·본문만 보고, 태그·사진·시리즈는 서버가 한 번 더 확인한다. */
export function isBlankDraft(status: string, c: { title: string; contentMd: string }): boolean {
  return status === 'DRAFT' && !c.title.trim() && !c.contentMd.trim()
}

/**
 * [글쓰기]를 눌렀다 아무것도 쓰지 않고 나오면 그 임시글을 휴지통 없이 바로 지운다 (091).
 * 서버가 빈 글이 아니라고 보면(다른 탭에서 썼거나 태그·사진이 있으면) 그대로 두고 false다. 실패해도 나가기는 막지 않는다.
 */
export async function discardIfEmpty(id: number, memberId?: number, keepalive = false): Promise<boolean> {
  try {
    const r = await api<{ deleted: boolean }>(`/api/posts/${id}/if-empty`, { method: 'DELETE', keepalive })
    if (r.deleted && memberId != null) await localDrafts.remove(memberId, id)
    return r.deleted
  } catch {
    return false
  }
}
