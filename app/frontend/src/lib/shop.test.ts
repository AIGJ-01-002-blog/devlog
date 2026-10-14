import { describe, expect, it } from 'vitest'
import { bannerProps, effectName, frameProps, needText, shortCredits, themeStyle, tierName, tierPrice, TIERS } from './shop'

const look = (value: string, imageUrl: string | null = null) => ({ itemId: 1, name: 'x', tier: 1, value, imageUrl })

describe('shop', () => {
  it('테마 색은 #RRGGBB만 화면에 넣는다', () => {
    expect(themeStyle(look('#2f9e44'))).toEqual({ '--blog-theme': '#2f9e44' })
    expect(themeStyle(look('red; background: url(x)'))).toBeUndefined()
    expect(themeStyle(null)).toBeUndefined()
  })

  it('테두리는 색이나 티어 모양만', () => {
    expect(frameProps(look('challenger'))).toEqual({ 'data-frame': 'challenger' })
    expect(frameProps(look('#20c997'))).toEqual({ 'data-frame': 'color', style: { '--frame-color': '#20c997' } })
    expect(frameProps(look('neon'))).toEqual({})
  })

  it('표지는 티어 표지나 따옴표 없는 사진 주소만', () => {
    expect(bannerProps(look('diamond'))).toEqual({ 'data-banner': 'diamond', image: false })
    expect(bannerProps(look('', 'https://img.devlog.life/images/a.webp')).image).toBe(true)
    expect(bannerProps(look('', 'https://x/a.png") , url("evil')).image).toBe(false)
    expect(bannerProps(null)).toEqual({ image: false })
  })

  it('효과는 정해 둔 이름만', () => {
    expect(effectName(look('radiance'))).toBe('radiance')
    expect(effectName(look('blink'))).toBeUndefined()
    expect(effectName(null)).toBeUndefined()
  })

  it('티어는 아이언부터 챌린저까지 아홉, 가격은 100에서 열 배씩', () => {
    expect(TIERS).toHaveLength(9)
    expect(tierName(1)).toBe('아이언')
    expect(tierName(9)).toBe('챌린저')
    expect(tierName(10)).toBe('')
    expect(tierPrice(1)).toBe(100)
    expect(tierPrice(9)).toBe(10_000_000_000)
    expect(shortCredits(1_000)).toBe('1,000')
    expect(shortCredits(10_000_000_000)).toBe('100억')
  })

  it('잠긴 아이템은 먼저 살 것을 알려 준다', () => {
    expect(needText({ needTier: 1, needKind: 'FRAME' })).toBe('아이언 테두리부터 사야 열려요')
    expect(needText({ needTier: 5, needKind: null })).toBe('플래티넘 티어 아이템부터 사야 열려요')
    expect(needText({ needTier: null, needKind: null })).toBe('')
  })
})
