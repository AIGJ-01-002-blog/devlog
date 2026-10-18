// @vitest-environment jsdom
import { act, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SocialLinkList } from './SocialLinkList'
import { SocialLinksForm } from './SocialLinksForm'
import type { SocialLinks } from '../lib/socialLinks'

async function mount(node: ReactNode) {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  document.cookie = 'XSRF-TOKEN=test'
  const el = document.createElement('div'); document.body.appendChild(el)
  await act(async () => { createRoot(el).render(node) })
  return el
}

function serve(status: number, body: unknown) {
  const fetch = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

function type(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

const input = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('label')].find((l) => l.querySelector('span')?.textContent === label)!.querySelector('input')!

describe('SocialLinkList', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('값이 있는 것만 정해진 순서로, 주인 계정 링크로 보인다', async () => {
    const el = await mount(<SocialLinkList links={{ homepage: 'https://devlog.life', github: 'octo', email: 'me@example.com' }} />)
    const links = [...el.querySelectorAll('a')]
    expect(links.map((a) => a.textContent)).toEqual(['이메일', 'GitHub', '홈페이지'])
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['mailto:me@example.com', 'https://github.com/octo', 'https://devlog.life'])
    expect(links[1].rel).toBe('me nofollow noopener noreferrer')
  })

  it('쇼츠 채널은 채널 주소로 가고 툴팁에 채널 이름을 보인다', async () => {
    const el = await mount(<SocialLinkList links={{ youtube: '@로그냥', instagram: 'dev.kim', tiktok: 'dev_kim' }} />)
    const links = [...el.querySelectorAll('a')]
    expect(links.map((a) => a.textContent)).toEqual(['YouTube', 'Instagram', 'TikTok'])
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      'https://www.youtube.com/@%EB%A1%9C%EA%B7%B8%EB%83%A5', 'https://www.instagram.com/dev.kim/', 'https://www.tiktok.com/@dev_kim'])
    expect(links.map((a) => a.title)).toEqual(['YouTube 채널 @로그냥', 'Instagram 채널 @dev.kim', 'TikTok 채널 @dev_kim'])
    const id = await mount(<SocialLinkList links={{ youtube: 'channel/UCabcdefghijklmnopqrstuv' }} />)
    expect(id.querySelector('a')!.getAttribute('href')).toBe('https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv')
  })

  it('아무것도 없으면 목록을 그리지 않는다', async () => {
    const el = await mount(<SocialLinkList links={{}} />)
    expect(el.querySelector('.social-links')).toBeNull()
  })
})

describe('SocialLinksForm', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('모든 칸을 한 번에 보내고 서버가 정리한 값으로 칸을 바꾼다', async () => {
    const fetch = serve(200, { github: 'octo' })
    const onSaved = vi.fn<(l: SocialLinks) => void>()
    const el = await mount(<SocialLinksForm initial={{ x: 'old' }} onSaved={onSaved} />)
    const save = el.querySelector('button')!
    expect(save.disabled).toBe(true) // 바꾼 것이 없으면 저장할 수 없다

    await act(async () => { type(input(el, 'GitHub'), 'https://github.com/octo'); type(input(el, 'X'), '') })
    await act(async () => { save.click() })

    const [url, init] = fetch.mock.calls[0]
    expect(String(url)).toBe('/api/me/social-links')
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toEqual({ email: '', github: 'https://github.com/octo', x: '', facebook: '', homepage: '', youtube: '', instagram: '', tiktok: '' })
    expect(input(el, 'GitHub').value).toBe('octo')
    expect(onSaved).toHaveBeenCalledWith({ github: 'octo' })
    expect(el.querySelector('[role="status"]')?.textContent).toBe('저장했어요.')
  })

  it('틀린 칸마다 서버 문구를 그 칸 아래에 보인다', async () => {
    serve(400, { code: 'VALIDATION_FAILED', message: '입력값을 확인해 주세요.',
      errors: [{ field: 'homepage', code: 'SOCIAL_HOMEPAGE_INVALID', message: 'http:// 또는 https://로 시작하는 주소를 넣어 주세요.' }] })
    const el = await mount(<SocialLinksForm initial={{}} onSaved={() => {}} />)
    await act(async () => { type(input(el, '홈페이지'), 'javascript:alert(1)') })
    await act(async () => { el.querySelector('button')!.click() })
    const field = input(el, '홈페이지')
    expect(field.getAttribute('aria-invalid')).toBe('true')
    expect(document.getElementById(field.getAttribute('aria-describedby')!)?.textContent).toContain('https://')
  })
})
