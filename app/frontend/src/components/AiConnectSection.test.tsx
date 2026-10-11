// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AiConnectSection } from './AiConnectSection'

// spec 052: 토큰 원문은 만든 직후 한 번만 보이고, 목록에는 앞부분만 남는다.
const listed = { id: 1, name: '노트북', prefix: 'dvl_abcd', scope: 'READ', createdAt: '2026-10-08T00:00:00Z',
  expiresAt: '2027-01-06T00:00:00Z', lastUsedAt: null, expired: false, oauth: false }
const chatgpt = { ...listed, id: 3, name: 'ChatGPT', prefix: 'dvl_zzzz', scope: 'WRITE', oauth: true }
const secret = 'dvl_' + 'x'.repeat(43)

describe('AiConnectSection', () => {
  let allowed: boolean
  let diary: boolean
  let diaryHour: number
  let root: Root
  let host: HTMLDivElement
  beforeEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    document.cookie = 'XSRF-TOKEN=test'
    host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    allowed = false
    diary = false
    diaryHour = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/api/me/ai-publish')) {
        if (init?.method === 'PUT') allowed = JSON.parse(String(init.body)).allowed
        return new Response(JSON.stringify({ allowed }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (url.endsWith('/api/me/ai-diary')) {
        if (init?.method === 'PUT') {
          const b = JSON.parse(String(init.body))
          diary = b.enabled
          if (b.hour !== undefined) diaryHour = b.hour
        }
        return new Response(JSON.stringify({ enabled: diary, hour: diaryHour }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      if (init?.method === 'POST') {
        return new Response(JSON.stringify({ token: { ...listed, id: 2, name: 'Claude Code', prefix: secret.slice(0, 8), scope: 'WRITE' }, secret }),
          { status: 201, headers: { 'Content-Type': 'application/json' } })
      }
      return new Response(JSON.stringify(url.endsWith('/api/me/tokens') ? [listed, chatgpt] : {}), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.unstubAllGlobals()
  })

  it('목록에는 앞부분만, 만든 직후에는 원문과 연결 명령을 보여 준다', async () => {
    await act(async () => { root.render(<AiConnectSection />) })
    expect(host.textContent).toContain('노트북')
    expect(host.textContent).toContain('dvl_abcd…')
    expect(host.textContent).not.toContain(secret)
    // 로그인(OAuth)으로 연결한 앱은 토큰 앞부분 대신 표시가 붙고 [연결 끊기]다
    expect(host.textContent).toContain('로그인 연결')
    expect(host.textContent).not.toContain('dvl_zzzz')
    expect([...host.querySelectorAll('button')].some((b) => b.textContent === '연결 끊기')).toBe(true)

    await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) })
    expect(host.textContent).toContain(secret)
    expect(host.textContent).toContain(`claude mcp add --transport http devlog`)
    expect(host.textContent).toContain('다시 볼 수 없으니')

    await act(async () => { [...host.querySelectorAll('button')].find((b) => b.textContent === '다 복사했어요')!.click() })
    expect(host.textContent).not.toContain(secret)
    expect(host.querySelectorAll('.token-row')).toHaveLength(3)
  })

  // spec 087: 처음 연결하는 회원에게 로그인 연결과 토큰 연결의 차이, 앱별 순서를 접는 칸으로 보여 준다
  it('앱별 연결 순서와 자주 묻는 질문을 접어서 보여 준다', async () => {
    await act(async () => { root.render(<AiConnectSection />) })
    const guide = host.querySelector('.ai-guide')!
    expect(guide.textContent).toContain('토큰 연결')
    const steps = [...guide.querySelectorAll('details')]
    expect(steps.map((d) => d.querySelector('summary')!.textContent)).toEqual([
      'ChatGPT에 연결하기', 'Claude 앱(claude.ai·데스크톱)에 연결하기', 'Claude Code·Cursor·Codex에 연결하기', '자주 묻는 질문',
    ])
    // 처음에는 모두 접혀 있고, 펼칠 칸마다 무엇을 여는지 툴팁이 있다
    expect(steps.every((d) => !d.open && d.querySelector('summary')!.title)).toBe(true)
    // 로그인 연결 두 곳에는 넣을 서버 주소를 복사할 수 있다
    expect(steps[0].textContent).toContain(`${window.location.origin}/api/mcp`)
    expect(steps[1].textContent).toContain(`${window.location.origin}/api/mcp`)
    expect(steps[0].textContent).toContain('사용자 지정 MCP 서버 추가')
    expect(steps[3].textContent).toContain('연결했는데 이 목록에 안 보여요.')
  })

  // spec 053: AI 발행·삭제 허용은 기본 꺼짐이고, 켤 때만 한 번 더 묻는다
  it('AI 발행·삭제 허용은 켤 때 확인을 받고, 끌 때는 바로 끈다', async () => {
    const ask = vi.fn(() => false)
    vi.stubGlobal('confirm', ask)
    await act(async () => { root.render(<AiConnectSection />) })
    expect(host.textContent).toContain('켜면 연결한 AI가 글을 바로 발행하거나 삭제할 수 있어요. 삭제는 웹에서 지울 때와 같아요.')
    // AI 앱은 처음 연결할 때 받은 도구 목록을 기억하므로, 켠 뒤에는 다시 연결해야 한다
    expect(host.textContent).toContain('켠 뒤에는 AI 앱에서 devlog 연결을 다시 시작해야 발행·삭제 도구가 보여요.')
    const box = () => host.querySelector<HTMLInputElement>('.ai-publish input[type="checkbox"]')!
    const puts = () => (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.filter(([, init]) => init?.method === 'PUT')
    expect(box().checked).toBe(false)

    // 확인 창에서 취소하면 켜지지 않는다
    await act(async () => { box().click() })
    expect(ask).toHaveBeenCalledTimes(1)
    expect(puts()).toHaveLength(0)
    expect(box().checked).toBe(false)

    ask.mockReturnValue(true)
    await act(async () => { box().click() })
    expect(puts()).toHaveLength(1)
    expect(JSON.parse(String(puts()[0][1].body))).toEqual({ allowed: true })
    expect(box().checked).toBe(true)

    // 끌 때는 묻지 않는다
    await act(async () => { box().click() })
    expect(ask).toHaveBeenCalledTimes(2)
    expect(JSON.parse(String(puts()[1][1].body))).toEqual({ allowed: false })
    expect(box().checked).toBe(false)
  })

  // spec 061·071: AI 일기 쓰기는 기본 꺼짐이고, 끌 때는 남은 메모가 지워지니 한 번 더 묻는다
  it('AI 일기 쓰기는 켤 때 바로 켜고, 끌 때 확인을 받는다', async () => {
    const ask = vi.fn(() => false)
    vi.stubGlobal('confirm', ask)
    await act(async () => { root.render(<AiConnectSection />) })
    const box = () => host.querySelector<HTMLInputElement>('.ai-diary input[type="checkbox"]')!
    const puts = () => (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls
      .filter(([url, init]) => init?.method === 'PUT' && String(url).endsWith('/api/me/ai-diary'))
    expect(host.textContent).toContain('AI 일기 쓰기')
    expect(box().closest('label')!.title).toContain('자정에')
    expect(box().checked).toBe(false)

    await act(async () => { box().click() })
    expect(ask).not.toHaveBeenCalled()
    expect(JSON.parse(String(puts()[0][1].body))).toEqual({ enabled: true })
    expect(box().checked).toBe(true)

    // 끌 때 취소하면 그대로
    await act(async () => { box().click() })
    expect(ask).toHaveBeenCalledTimes(1)
    expect(puts()).toHaveLength(1)
    expect(box().checked).toBe(true)

    ask.mockReturnValue(true)
    await act(async () => { box().click() })
    expect(JSON.parse(String(puts()[1][1].body))).toEqual({ enabled: false })
    expect(box().checked).toBe(false)
  })

  // spec 071: 일기 시각은 켠 뒤에 고르고, 고르면 그 시각으로 저장한다
  it('일기 쓰는 시각을 고르면 저장하고 안내 문구도 그 시각으로 바뀐다', async () => {
    await act(async () => { root.render(<AiConnectSection />) })
    const select = () => host.querySelector<HTMLSelectElement>('.ai-diary select')!
    expect(select().disabled).toBe(true)
    expect(select().options).toHaveLength(24)
    expect(select().options[22].textContent).toBe('오후 10시')
    await act(async () => { host.querySelector<HTMLInputElement>('.ai-diary input[type="checkbox"]')!.click() })
    expect(select().disabled).toBe(false)
    await act(async () => {
      select().value = '22'
      select().dispatchEvent(new Event('change', { bubbles: true }))
    })
    const puts = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls
      .filter(([url, init]) => init?.method === 'PUT' && String(url).endsWith('/api/me/ai-diary'))
    expect(JSON.parse(String(puts[1][1].body))).toEqual({ enabled: true, hour: 22 })
    expect(host.querySelector('.ai-diary')!.textContent).toContain('매일 오후 10시(한국 시간)')
  })
})
