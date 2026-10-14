// 유입 경로·많이 본 화면 이름 (spec 070). 서버 VisitSources의 분류 값과 화면 주소 모양을 사람이 읽는 이름으로 바꾼다.

export type SourceGroup = 'search' | 'sns' | 'direct' | 'other'

const SOURCES: Record<string, { label: string; group: SourceGroup }> = {
  google: { label: '구글', group: 'search' },
  naver: { label: '네이버', group: 'search' },
  daum: { label: '다음', group: 'search' },
  bing: { label: '빙', group: 'search' },
  kakao: { label: '카카오톡', group: 'sns' },
  instagram: { label: '인스타그램', group: 'sns' },
  facebook: { label: '페이스북', group: 'sns' },
  x: { label: 'X(트위터)', group: 'sns' },
  youtube: { label: '유튜브', group: 'sns' },
  threads: { label: '스레드', group: 'sns' },
  linkedin: { label: '링크드인', group: 'sns' },
  line: { label: '라인', group: 'sns' },
  github: { label: '깃허브', group: 'other' },
  direct: { label: '직접 입력·즐겨찾기', group: 'direct' },
}

export const GROUP_LABEL: Record<SourceGroup, string> = { search: '검색', sns: 'SNS·메신저', direct: '직접', other: '다른 사이트' }

/** 기타 사이트는 그 사이트 이름, 나머지 사이트를 모은 줄은 '그 밖의 사이트' */
export function sourceLabel(source: string, host: string): string {
  if (source === 'other') return host || '그 밖의 사이트'
  return SOURCES[source]?.label ?? source
}

export function sourceGroup(source: string): SourceGroup {
  return SOURCES[source]?.group ?? 'other'
}

const PAGES: Record<string, string> = {
  '/': '홈',
  '/search': '검색',
  '/tags': '태그 목록',
  '/releases': '릴리스 노트',
  '/support': '문의',
  '/mcp': 'MCP 안내',
  '/rss': 'RSS 안내',
  '/login': '로그인',
  '/signup': '가입',
  '/feed': '피드',
  '/terms': '이용약관',
  '/privacy': '개인정보 처리방침',
  '/notifications': '알림',
  '/settings': '내 설정',
  '/lists/liked': '좋아한 글',
  '/lists/read': '최근 읽은 글',
  '/manage/posts': '내 글 관리',
}

/** 주소의 %인코딩을 풀어 읽기 쉽게 */
export function decode(s: string): string {
  try {
    return decodeURIComponent(s)
  } catch {
    return s
  }
}

/**
 * 화면 주소를 이름으로. 글 화면은 제목(공개 글만)을 쓰고, 제목이 없으면 '공개하지 않은 글'.
 */
export function pageLabel(path: string, title: string | null): string {
  if (PAGES[path]) return PAGES[path]
  let m = /^\/tags\/(.+)$/.exec(path)
  if (m) return `태그 #${decode(m[1])}`
  m = /^\/@([^/]+)\/posts\/\d+$/.exec(path)
  if (m) return title ?? `@${m[1]}의 공개하지 않은 글`
  m = /^\/@([^/]+)(?:\/(.+))?$/.exec(path)
  if (m) {
    const rest = m[2]
    if (!rest) return `@${m[1]} 블로그`
    if (rest === 'series') return `@${m[1]} 시리즈 목록`
    if (rest.startsWith('series/')) return `@${m[1]} 시리즈 ${decode(rest.slice(7))}`
    if (rest === 'about') return `@${m[1]} 소개`
    if (rest === 'followers') return `@${m[1]} 팔로워`
    if (rest === 'following') return `@${m[1]} 팔로잉`
    if (rest === 'rss') return `@${m[1]} RSS 안내`
  }
  return decode(path)
}
