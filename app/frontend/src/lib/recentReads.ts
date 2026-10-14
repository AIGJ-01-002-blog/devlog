/**
 * 최근 읽은 글 (spec 097 FR-008, velog "읽기 목록"의 최근 읽은 포스트).
 * 이 브라우저에만 남긴다. 서버에는 보내지 않아서 로그인하지 않아도 쓰고, 기기를 바꾸면 비어 있다.
 */
export interface RecentRead {
  id: number
  url: string
  title: string
  author: string
  thumbnailUrl: string | null
  readAt: string
}

const KEY = 'devlog:recent-reads'
export const RECENT_KEEP = 50

export function recentReads(): RecentRead[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(raw) ? raw.filter((r) => r && typeof r.id === 'number' && typeof r.url === 'string') : []
  } catch {
    return []
  }
}

/** 맨 앞에 넣고 같은 글은 한 번만 남긴다. 오래된 것부터 50개 넘게는 버린다 */
export function rememberRead(read: Omit<RecentRead, 'readAt'>, now = new Date()): void {
  try {
    const list = [{ ...read, readAt: now.toISOString() }, ...recentReads().filter((r) => r.id !== read.id)]
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, RECENT_KEEP)))
  } catch {
    // 저장 공간이 없거나 막혀 있으면 기억하지 않는다
  }
}

export function forgetRead(id: number): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(recentReads().filter((r) => r.id !== id)))
  } catch {
    // 지우지 못해도 화면은 그대로 쓴다
  }
}

export function clearReads(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // 막혀 있으면 할 일이 없다
  }
}
