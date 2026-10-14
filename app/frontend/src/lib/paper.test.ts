import { describe, expect, it } from 'vitest'
import { change, estimateBuy, estimateSell, newOrderId, pnlRate, price, qty, tone } from './paper'

describe('paper', () => {
  it('서버와 같이 수수료는 올림, 수량은 그대로 나눈다', () => {
    expect(estimateBuy(1_000_000, 100_000_000, 0.0005)).toEqual({ fee: 500, quantity: 0.009995 })
    expect(estimateBuy(0, 100, 0.0005)).toBeNull()
    expect(estimateBuy(10_000, null, 0.0005)).toBeNull()
    expect(estimateSell(0.009995, 120_000_000, 0.0005)).toEqual({ fee: 600, proceeds: 1_198_800 })
  })

  it('등락은 +·- 를 붙이고 이익·상승은 good, 손해·하락은 bad', () => {
    expect(change(0.0123)).toBe('+1.23%')
    expect(change(-0.05)).toBe('-5.00%')
    expect(change(null)).toBe('-')
    expect(tone(3)).toBe('good')
    expect(tone(-1)).toBe('bad')
    expect(tone(0)).toBe('flat')
    expect(pnlRate({ pnl: -500, cost: 10_000 })).toBe('-5.00%')
    expect(pnlRate({ pnl: 0, cost: 0 })).toBe('0.00%')
  })

  it('작은 가격은 소수까지, 수량은 꼬리 0 없이 보인다', () => {
    expect(price(312.4)).toContain('312')
    expect(price(87.123)).toContain('87.12')
    expect(price(null)).toBe('-')
    expect(qty(0.00999500)).toBe('0.009995')
  })

  it('주문 번호는 서버가 받는 모양(8~64자, 영문·숫자·-)이다', () => {
    const id = newOrderId()
    expect(id).toMatch(/^[A-Za-z0-9-]{8,64}$/)
    expect(newOrderId()).not.toBe(id)
  })
})
