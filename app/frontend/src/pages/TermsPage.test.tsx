// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TermsPage } from './TermsPage'

// spec 085: 처리방침 문안은 서버 한 곳(policy/*.html)에 있고, 화면은 그 HTML을 받아 그린다.
describe('TermsPage', () => {
  let root: Root
  let host: HTMLDivElement
  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.unstubAllGlobals()
  })

  it('서버가 준 처리방침 본문과 시행일을 그린다', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({
      kind: 'privacy', title: '개인정보 처리방침', effectiveDate: '2026-10-10', html: '<h2 id="ads">7. 광고와 쿠키</h2><p>본문</p>',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetch)
    await act(async () => { root.render(<TermsPage kind="privacy" />) })
    expect(String((fetch.mock.calls[0] as unknown[])[0])).toContain('/api/site/policies/privacy')
    expect(host.querySelector('#ads')?.textContent).toBe('7. 광고와 쿠키')
    expect(host.textContent).toContain('시행일 2026-10-10')
    expect(host.querySelector('a[href="/terms"]')?.textContent).toBe('이용약관 보기')
  })

  it('문안을 못 받으면 안내를 보인다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 500 })))
    await act(async () => { root.render(<TermsPage kind="terms" />) })
    expect(host.textContent).toContain('문안을 불러오지 못했어요')
  })
})
