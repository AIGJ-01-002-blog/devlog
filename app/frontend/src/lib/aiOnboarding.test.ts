import { beforeEach, describe, expect, it } from 'vitest'
import { isFirstLogin, isQuietPath, markOnboardingDone, onboardingDone } from './aiOnboarding'
import type { Me } from './types'

// spec 098: 가입 뒤 첫 로그인에만, 쓰기·동의·설정 화면을 피해 한 번 띄운다
const me = (over: Partial<Me> = {}, status = 'ACTIVE'): Me => ({
  authenticated: true,
  member: { id: 7, handle: 'minseo', nickname: '민서', role: 'USER', defaultVisibility: 'PUBLIC', status, profileImageUrl: null },
  agreementRequired: false,
  previousLogin: { at: null, provider: 'GITHUB' },
  pendingSignup: false,
  emailVerified: true,
  ...over,
} as Me)

describe('aiOnboarding', () => {
  beforeEach(() => localStorage.clear())

  it('첫 로그인(직전 로그인 없음)인 활동 회원만 대상이다', () => {
    expect(isFirstLogin(me())).toBe(true)
    expect(isFirstLogin(me({ previousLogin: { at: '2026-10-13T00:00:00Z', provider: 'GITHUB' } }))).toBe(false)
    expect(isFirstLogin(me({ agreementRequired: true }))).toBe(false)
    expect(isFirstLogin(me({}, 'WITHDRAWN'))).toBe(false)
    expect(isFirstLogin(me({ authenticated: false, member: null, previousLogin: null }))).toBe(false)
    expect(isFirstLogin(null)).toBe(false)
  })

  it('쓰기·설정·동의·연결 안내 화면에서는 띄우지 않는다', () => {
    for (const p of ['/write', '/write/3', '/settings', '/agreements', '/oauth/authorize', '/mcp', '/verify-email', '/signup/social']) {
      expect(isQuietPath(p), p).toBe(true)
    }
    for (const p of ['/', '/@minseo', '/feed', '/tags']) expect(isQuietPath(p), p).toBe(false)
  })

  it('한 번 닫으면 회원별로 기억하고, 로그아웃 정리 대상 키와 겹치지 않는다', () => {
    expect(onboardingDone(7)).toBe(false)
    markOnboardingDone(7)
    expect(onboardingDone(7)).toBe(true)
    expect(onboardingDone(8)).toBe(false)
    // clearLocalData는 `:{id}:`가 든 키를 지운다
    expect(Object.keys(localStorage).some((k) => k.includes(':7:'))).toBe(false)
  })
})
