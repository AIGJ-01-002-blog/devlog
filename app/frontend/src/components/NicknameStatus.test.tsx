// @vitest-environment jsdom
import { act, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NICKNAME_CHECK_DELAY_MS, useNicknameCheck } from '../lib/nickname'
import { NicknameStatus } from './NicknameStatus'

// 닉네임을 치는 대로 조건을 보여 주고, 멈추면 사용 가능 여부를 자동으로 확인한다 (docs/09).
let current = ''
function Harness({ initial = '', unchanged = false }: { initial?: string; unchanged?: boolean }) {
  const [value, setValue] = useState(initial)
  const check = useNicknameCheck(value, unchanged)
  current = check.state
  return (
    <>
      <input value={value} onChange={(e) => setValue(e.target.value)} />
      <NicknameStatus value={value} check={check} hint="안내" />
    </>
  )
}

describe('NicknameStatus', () => {
  let root: Root
  let host: HTMLDivElement
  let calls: string[]
  let respond: (nick: string) => Response

  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.useFakeTimers()
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    calls = []
    respond = (nick) => json(nick === '사용중' ? { available: false, code: 'NICKNAME_DUPLICATE', message: '이미 사용 중인 닉네임이에요' } : { available: true })
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const nick = new URL(url, 'http://x').searchParams.get('nickname')!
      calls.push(nick)
      return respond(nick)
    }))
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  const render = async (props: { initial?: string; unchanged?: boolean } = {}) => { await act(async () => root.render(<Harness {...props} />)) }
  const type = async (v: string) => {
    const input = host.querySelector('input')!
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    await act(async () => { setter.call(input, v); input.dispatchEvent(new Event('input', { bubbles: true })) })
  }
  const wait = async (ms = NICKNAME_CHECK_DELAY_MS) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }

  it('조건을 못 채우면 서버에 묻지 않고 조건별 ✓를 보여 준다', async () => {
    await render()
    expect(host.textContent).toContain('안내')
    await type('민')
    await wait()
    expect(calls).toEqual([])
    expect(current).toBe('rules')
    const items = [...host.querySelectorAll('li')]
    expect(items.map((li) => li.className)).toEqual(['bad', 'ok', 'ok'])
  })

  it('입력을 멈춘 뒤 한 번만 묻고 결과를 보여 준다', async () => {
    await render()
    await type('민서')
    await type('민서님')
    expect(current).toBe('checking')
    expect(host.textContent).toContain('확인하는 중')
    await wait()
    expect(calls).toEqual(['민서님'])
    expect(current).toBe('available')
    expect(host.textContent).toContain('쓸 수 있는 닉네임이에요.')
  })

  it('늦게 도착한 옛 응답은 지금 입력한 값의 결과를 덮지 않는다', async () => {
    const pending: Record<string, (r: Response) => void> = {}
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      const nick = new URL(url, 'http://x').searchParams.get('nickname')!
      calls.push(nick)
      return new Promise<Response>((resolve) => { pending[nick] = resolve })
    }))
    await render()
    await type('사용중')
    await wait()
    await type('민서님')
    await wait()
    expect(calls).toEqual(['사용중', '민서님'])
    await act(async () => { pending['민서님'](json({ available: true })) })
    expect(current).toBe('available')
    await act(async () => { pending['사용중'](json({ available: false, message: '이미 사용 중인 닉네임이에요' })) })
    expect(current).toBe('available')
    expect(host.textContent).not.toContain('이미 사용 중인')
  })

  it('이미 쓰는 닉네임이면 서버 문구를 보여 준다', async () => {
    await render()
    await type('사용중')
    await wait()
    expect(current).toBe('taken')
    expect(host.textContent).toContain('이미 사용 중인 닉네임이에요')
  })

  it('확인에 실패하면 [다시 확인]으로 바로 다시 묻는다', async () => {
    respond = () => json({ code: 'RATE_LIMITED', message: 'too many' }, 429)
    await render()
    await type('민서')
    await wait()
    expect(current).toBe('error')
    const button = host.querySelector<HTMLButtonElement>('button')!
    expect(button.textContent).toBe('다시 확인')
    expect(button.dataset.tip).toBeTruthy()
    respond = () => json({ available: true })
    await act(async () => button.click())
    await wait(0)
    expect(calls).toEqual(['민서', '민서'])
    expect(current).toBe('available')
  })

  it('지금 쓰는 닉네임 그대로면 묻지 않는다', async () => {
    await render({ initial: '민서', unchanged: true })
    await wait()
    expect(calls).toEqual([])
    expect(current).toBe('idle')
    expect(host.querySelector('ul')).toBeNull()
  })
})
