import { describe, expect, it } from 'vitest'
import { coinUnit, exchangeName, fillTime, isDate, mainCurrency, money, moneyClass, moneys, pnlClass, qty, tradePeriodOf, winRate, won } from './trades'

const summary = { trades: 0, buys: 0, sells: 0, wins: 0, losses: 0, realizedPnl: {}, fees: {} }

describe('매매 일지 (#243)', () => {
  it('금액은 원 단위로 반올림하고 이익에만 +를 붙인다', () => {
    expect(won(14600, true)).toBe('+14,600원')
    expect(won(-24300.4, true)).toBe('-24,300원')
    expect(won(0, true)).toBe('0원')
    expect(won(71000)).toBe('71,000원')
  })

  it('손익 색은 반올림한 값으로 정한다', () => {
    expect(pnlClass(1)).toBe('good')
    expect(pnlClass(-1)).toBe('bad')
    expect(pnlClass(0.4)).toBe('flat')
  })

  it('이긴 매도 비율은 손익이 난 매도만 센다', () => {
    expect(winRate(summary)).toBeNull()
    expect(winRate({ ...summary, wins: 2, losses: 1 })).toBe(67)
  })

  it('주소의 기간·날짜를 읽는다', () => {
    expect(tradePeriodOf('7')).toBe(7)
    expect(tradePeriodOf('14')).toBe(30)
    expect(tradePeriodOf(null)).toBe(30)
    expect(isDate('2026-10-14')).toBe(true)
    expect(isDate('2026-10-14x')).toBe(false)
    expect(isDate(null)).toBe(false)
  })

  it('체결 시각은 한국 시간, 수량은 소수점 아래 0을 지운다', () => {
    expect(fillTime('2026-10-14T00:05:00Z')).toBe('09:05')
    expect(qty(10)).toBe('10')
    expect(qty(0.5)).toBe('0.5')
    expect(qty(0.00012345)).toBe('0.00012345')
    expect(qty(1200)).toBe('1,200')
  })

  it('코인 거래소 금액은 통화별로 적고 원화와 더하지 않는다 (#254)', () => {
    expect(money(83752.3, 'USDT')).toBe('83,752.30 USDT')
    expect(money(1.0549, 'USDT', true)).toBe('+1.05 USDT')
    expect(money(0.2023, 'USDT')).toBe('0.2023 USDT')
    expect(money(-0.004, 'USDT', true)).toBe('-0.004 USDT')
    expect(moneys({ KRW: -4200, USDT: 1.0549 }, 'KRW', true)).toBe('-4,200원 · +1.05 USDT')
    expect(moneys({}, 'USDT', true)).toBe('0.00 USDT')
    expect(mainCurrency({ ...summary, fees: { USDT: 0.2 } })).toBe('USDT')
    expect(mainCurrency(summary)).toBe('KRW')
  })

  it('여러 통화의 손익 색은 모두 같은 쪽일 때만 칠한다', () => {
    expect(moneyClass({ USDT: 2, KRW: 100 })).toBe('good')
    expect(moneyClass({ USDT: -2 })).toBe('bad')
    expect(moneyClass({ USDT: 2, KRW: -100 })).toBe('flat')
    expect(pnlClass(0.004, 'USDT')).toBe('good')
    expect(pnlClass(0.004, 'KRW')).toBe('flat')
  })

  it('코인은 마켓 코드에서 코인 이름을 읽고 주식은 주로 읽는다', () => {
    expect(coinUnit({ exchange: 'BINANCE', symbol: 'BTCUSDT', currency: 'USDT' })).toBe('BTC')
    expect(coinUnit({ exchange: 'UPBIT', symbol: 'KRW-ETH', currency: 'KRW' })).toBe('ETH')
    expect(coinUnit({ exchange: '', symbol: '005930', currency: 'KRW' })).toBeNull()
    expect(exchangeName('UPBIT')).toBe('업비트')
    expect(exchangeName('FOO')).toBe('FOO')
  })
})
