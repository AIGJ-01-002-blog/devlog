// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AiOnboarding } from './AiOnboarding'
import { onboardingDone } from '../lib/aiOnboarding'

// spec 098: 세 단계를 넘기고, 마지막에 설정 › AI 연결로 보낸다. 어떻게 닫든 다시 뜨지 않는다
describe('AiOnboarding', () => {
  let root: Root
  let host: HTMLDivElement
  let tokens: unknown[]
  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    localStorage.clear()
    tokens = []
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(tokens), { status: 200, headers: { 'Content-Type': 'application/json' } })))
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.unstubAllGlobals()
  })

  const render = async (onClose = vi.fn()) => {
    await act(async () => { root.render(<AiOnboarding memberId={7} onClose={onClose} />) })
    return onClose
  }
  const button = (name: string) => [...document.querySelectorAll<HTMLElement>('.ai-onboarding button, .ai-onboarding a')].find((b) => b.textContent === name)

  it('단계를 넘겨 마지막에 AI 연결 설정으로 가는 버튼을 보인다', async () => {
    const onClose = await render()
    expect(document.querySelector('#ai-onboarding-title')?.textContent).toContain('개발 일지')
    act(() => button('다음')!.click())
    expect(document.querySelector('.ai-onboarding')?.textContent).toContain('/api/mcp')
    act(() => button('다음')!.click())
    const go = button('AI 연결하러 가기') as HTMLAnchorElement
    expect(go.getAttribute('href')).toBe('/settings#ai')
    expect(button('다음')).toBeUndefined()
    act(() => go.click())
    expect(location.hash).toBe('#ai')
    expect(onClose).toHaveBeenCalled()
    expect(onboardingDone(7)).toBe(true)
  })

  it('[나중에 하기]로 닫으면 다시 띄우지 않는다', async () => {
    const onClose = await render()
    act(() => button('나중에 하기')!.click())
    expect(onClose).toHaveBeenCalled()
    expect(onboardingDone(7)).toBe(true)
  })

  it('버튼마다 무엇을 하는지 툴팁이 있다', async () => {
    await render()
    for (const b of document.querySelectorAll('.ai-onboarding button')) expect(b.getAttribute('title'), b.outerHTML).toBeTruthy()
  })

  it('이미 AI를 연결한 회원에게는 띄우지 않는다', async () => {
    tokens = [{ id: 1, name: 'ChatGPT', prefix: 'dvl_a', scope: 'WRITE', createdAt: '', expiresAt: null, lastUsedAt: null, expired: false, oauth: true }]
    const onClose = await render()
    expect(document.querySelector('.ai-onboarding')).toBeNull()
    expect(onClose).toHaveBeenCalled()
    expect(onboardingDone(7)).toBe(true)
  })
})
