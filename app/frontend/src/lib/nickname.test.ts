import { describe, expect, it } from 'vitest'
import { nicknameRules, nicknameRulesOk, normalizeNickname } from './nickname'

describe('nicknameRules', () => {
  it('서버 NicknamePolicy와 같은 조건을 본다', () => {
    expect(nicknameRulesOk('민서')).toBe(true)
    expect(nicknameRulesOk('dev123')).toBe(true)
    expect(nicknameRulesOk(' 민서 ')).toBe(true) // 앞뒤 공백은 서버도 뗀다
    expect(nicknameRules('a').find((r) => r.id === 'length')!.ok).toBe(false)
    expect(nicknameRules('가나다라마바사아자차카').find((r) => r.id === 'length')!.ok).toBe(false)
    expect(nicknameRules('민 서').find((r) => r.id === 'chars')!.ok).toBe(false)
    expect(nicknameRules('민서!').find((r) => r.id === 'chars')!.ok).toBe(false)
    expect(nicknameRules('ㅋㅋ').find((r) => r.id === 'chars')!.ok).toBe(false) // 자음만은 안 된다
    expect(nicknameRules('1234').find((r) => r.id === 'letter')!.ok).toBe(false)
  })
})

describe('normalizeNickname', () => {
  it('끝에 붙은 NBSP까지 떼어 서버로 보낼 값을 만든다', () => {
    expect(normalizeNickname('민서\u00a0')).toBe('민서')
    expect(normalizeNickname(' 민서 ')).toBe('민서')
  })
})
