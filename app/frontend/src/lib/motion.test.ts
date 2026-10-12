import css from '../styles.css?raw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { armMotion, fadeInPage, MOTION_READY } from './motion'

describe('화면 움직임 (095)', () => {
  afterEach(() => document.documentElement.classList.remove(MOTION_READY))

  it('첫 사용자 동작 전에는 등장 효과를 켜지 않고, 동작하면 한 번 켠다', () => {
    const stop = armMotion()
    expect(document.documentElement.classList.contains(MOTION_READY)).toBe(false)
    window.dispatchEvent(new Event('scroll'))
    expect(document.documentElement.classList.contains(MOTION_READY)).toBe(true)
    stop()
  })

  it('동작 줄이기를 켰으면 화면 이동 흐림을 하지 않는다', () => {
    const el = document.createElement('div')
    const animate = vi.fn()
    Object.assign(el, { animate })
    vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('reduce') }))
    fadeInPage(el)
    expect(animate).not.toHaveBeenCalled()
    vi.stubGlobal('matchMedia', () => ({ matches: false }))
    fadeInPage(el)
    expect(animate).toHaveBeenCalledOnce()
    vi.unstubAllGlobals()
  })

  it('카드·목록 등장은 motion-ready 아래에서만 움직여 첫 화면을 다시 그릴 때 깜빡이지 않는다', () => {
    expect(css).toMatch(/\.motion-ready \.card \{ animation: motion-rise/)
    expect(css).toMatch(/\.motion-ready \.bl-body \{ animation: motion-rise/)
    expect(css).not.toMatch(/(^|\n)\.card \{[^}]*animation/)
  })

  it('움직임 줄이기에서는 엇갈림 지연도 없앤다', () => {
    const block = css.split('@media (prefers-reduced-motion: reduce)')[1]
    expect(block).toMatch(/animation-delay: 0s !important/)
  })
})
