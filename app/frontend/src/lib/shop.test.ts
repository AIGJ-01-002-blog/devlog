import { describe, expect, it } from 'vitest'
import { bannerProps, frameProps, themeStyle } from './shop'

const look = (value: string, imageUrl: string | null = null) => ({ itemId: 1, name: 'x', value, imageUrl })

describe('shop', () => {
  it('테마 색은 #RRGGBB만 화면에 넣는다', () => {
    expect(themeStyle(look('#2f9e44'))).toEqual({ '--blog-theme': '#2f9e44' })
    expect(themeStyle(look('red; background: url(x)'))).toBeUndefined()
    expect(themeStyle(null)).toBeUndefined()
  })

  it('테두리는 색이나 정해 둔 모양만', () => {
    expect(frameProps(look('gold'))).toEqual({ 'data-frame': 'gold' })
    expect(frameProps(look('#20c997'))).toEqual({ 'data-frame': 'color', style: { '--frame-color': '#20c997' } })
    expect(frameProps(look('neon'))).toEqual({})
  })

  it('표지는 정해 둔 그러데이션이나 따옴표 없는 사진 주소만', () => {
    expect(bannerProps(look('night'))).toEqual({ 'data-banner': 'night', image: false })
    expect(bannerProps(look('', 'https://img.devlog.life/images/a.webp')).image).toBe(true)
    expect(bannerProps(look('', 'https://x/a.png") , url("evil')).image).toBe(false)
    expect(bannerProps(null)).toEqual({ image: false })
  })
})
