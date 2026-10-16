import { describe, expect, it } from 'vitest'
import { change, estimateBuy, estimateSell, floorQty, limitBuyCost, maxBuyQty, newOrderId, pnlRate, price, qty, stepPrice, tickSize, toTick, tone } from './paper'

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

  it('호가 단위로 가격을 맞추고 −/＋는 한 호가씩 움직인다 (#332)', () => {
    expect(tickSize(100_000_000)).toBe(1000)
    expect(tickSize(250_000)).toBe(50)
    expect(tickSize(800)).toBe(0.1)
    expect(toTick(100_000_456)).toBe(100_000_000)
    expect(toTick(812.37)).toBe(812.3)
    expect(stepPrice(100_000_000, 1)).toBe(100_001_000)
    expect(stepPrice(100_000_000, -1)).toBe(99_999_000)
    // 구간 경계에서 내리면 아래 구간 호가를 쓴다
    expect(stepPrice(2_000_000, -1)).toBe(1_999_500)
    expect(stepPrice(1000, -1)).toBe(999.9)
    expect(stepPrice(0.2, 1)).toBe(0.2001)
  })

  it('지정가 매수에 묶일 크레딧과 살 수 있는 최대 수량은 서버와 같이 올림으로 센다 (#332)', () => {
    expect(limitBuyCost(90_000_000, 0.01, 0.0005)).toEqual({ gross: 900_000, fee: 450, total: 900_450 })
    expect(limitBuyCost(0, 1, 0.0005).total).toBe(0)
    const q = maxBuyQty(1_000_000, 100_000_000, 0.0005)
    expect(limitBuyCost(100_000_000, q, 0.0005).total).toBeLessThanOrEqual(1_000_000)
    expect(limitBuyCost(100_000_000, q + 1e-8, 0.0005).total).toBeGreaterThan(1_000_000)
    expect(maxBuyQty(10_000, 300, 0.0005, 2)).toBe(33.31)
    expect(floorQty(1.23456789, 4)).toBe(1.2345)
    expect(floorQty(0.1 + 0.2, 8)).toBe(0.3)
  })
})
