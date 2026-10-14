/** 최근 검색어 (spec 097 FR-009). 이 브라우저에만 8개까지 남긴다. 검색 기록은 서버에 저장하지 않는다(014 FR-022). */
const KEY = 'devlog:recent-searches'
export const SEARCH_KEEP = 8

export function recentSearches(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(raw) ? raw.filter((q): q is string => typeof q === 'string' && q.trim() !== '') : []
  } catch {
    return []
  }
}

export function rememberSearch(q: string): void {
  const query = q.trim()
  if (!query) return
  try {
    const list = [query, ...recentSearches().filter((s) => s !== query)]
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, SEARCH_KEEP)))
  } catch {
    // 막혀 있으면 기억하지 않는다
  }
}

export function forgetSearch(q: string): void {
  try {
    const list = recentSearches().filter((s) => s !== q)
    if (list.length) localStorage.setItem(KEY, JSON.stringify(list))
    else localStorage.removeItem(KEY)
  } catch {
    // 지우지 못해도 화면은 그대로 쓴다
  }
}
