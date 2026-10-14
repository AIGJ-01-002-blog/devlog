import type { Me } from './types'

/**
 * 첫 로그인 AI 연결 안내 (spec 098).
 * 가입하고 처음 로그인한 회원에게 devlog의 주 기능(AI 앱에 MCP로 연결해 "개발 일지 써 줘")을 단계형 팝업으로 보여 준다.
 * - "처음"은 서버가 주는 직전 로그인 시각이 없을 때다(docs/07 §6). 다음 로그인부터는 뜨지 않는다.
 * - [나중에 하기]·[연결하러 가기]·Esc 어느 쪽으로 닫아도 이 브라우저에서 다시 띄우지 않는다.
 * - 이미 토큰이나 로그인 연결이 있는 회원에게는 띄우지 않는다(팝업을 열기 전에 목록을 한 번 본다).
 */

// 로그아웃 때 지우는 키(`:{id}:`가 든 키)와 겹치지 않게 회원 번호를 끝에 붙인다. 로그아웃해도 "닫았음"은 남는다
const DONE_PREFIX = 'devlog.ai-onboarding.done.'

// 쓰는 중이거나 다른 일을 마쳐야 하는 화면, 이미 AI 연결 안내가 있는 화면에서는 띄우지 않는다
const QUIET_PREFIXES = ['/write', '/oauth/', '/admin', '/account/', '/settings', '/signup', '/login', '/mcp']
const QUIET_PATHS = new Set(['/agreements', '/terms', '/privacy', '/verify-email', '/withdrawn', '/forgot-password', '/reset-password'])

export function isQuietPath(path: string): boolean {
  return QUIET_PATHS.has(path) || QUIET_PREFIXES.some((p) => path.startsWith(p))
}

/** 서버 상태만으로 안내 대상인지: 로그인한 활동 회원, 재동의 필요 없음, 첫 로그인. */
export function isFirstLogin(me: Me | null): me is Me & { member: NonNullable<Me['member']> } {
  return !!me?.authenticated && !!me.member && me.member.status === 'ACTIVE' && !me.agreementRequired
    && !me.pendingSignup && me.previousLogin != null && me.previousLogin.at == null
}

export function onboardingDone(memberId: number): boolean {
  try {
    return localStorage.getItem(DONE_PREFIX + memberId) != null
  } catch {
    // 저장소를 못 쓰는 창(사생활 보호 등)에서는 띄우지 않는다. 닫아도 기억하지 못해 화면마다 다시 뜨기 때문이다
    return true
  }
}

export function markOnboardingDone(memberId: number): void {
  try {
    localStorage.setItem(DONE_PREFIX + memberId, new Date().toISOString())
  } catch { /* 저장소 없음: 이번 화면에서만 닫힌다 */ }
}

/** 안내 단계 수. 화면(AiOnboarding)과 테스트가 같이 본다 */
export const ONBOARDING_STEPS = 3
