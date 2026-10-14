import { useEffect, useRef, useState } from 'react'
import { api, ApiError } from './api'
import { t } from './i18n'

// 닉네임 규칙 (docs/09, 서버 NicknamePolicy). 입력 중에 조건을 바로 보여 주기 위한 사본이고,
// 예약어·금칙어·중복을 포함한 최종 판정은 서버가 한다.
const ALLOWED = /^[가-힣a-zA-Z0-9]*$/
const LETTER = /[가-힣a-zA-Z]/

export interface NicknameRule {
  id: 'length' | 'chars' | 'letter'
  label: string
  ok: boolean
}

/**
 * 앞뒤 공백을 떼고 NFC로 맞춘다. JS trim은 NBSP 같은 유니코드 공백까지 떼어 서버 strip보다 넓으므로,
 * 확인한 값과 저장하는 값이 어긋나지 않게 가입·저장 요청에도 이 값을 보낸다.
 */
export function normalizeNickname(raw: string): string {
  return raw.trim().normalize('NFC')
}

export function nicknameRules(raw: string): NicknameRule[] {
  const v = normalizeNickname(raw)
  const len = [...v].length
  return [
    { id: 'length', label: t('2~10자'), ok: len >= 2 && len <= 10 },
    { id: 'chars', label: t('한글·영문·숫자만 (공백·특수문자 불가)'), ok: v !== '' && ALLOWED.test(v) },
    { id: 'letter', label: t('한글이나 영문 1자 이상'), ok: LETTER.test(v) },
  ]
}

export function nicknameRulesOk(raw: string): boolean {
  return nicknameRules(raw).every((r) => r.ok)
}

/**
 * - idle: 비어 있거나 지금 쓰는 닉네임 그대로
 * - rules: 형식 조건을 아직 못 채움(서버에 묻지 않는다)
 * - checking: 입력을 멈추길 기다리거나 서버에 묻는 중
 * - available / taken: 서버 판정(taken은 중복·예약어·금칙어)
 * - error: 확인하지 못함(네트워크·요청 제한). [다시 확인]으로 다시 묻는다
 */
export type NicknameCheckState = 'idle' | 'rules' | 'checking' | 'available' | 'taken' | 'error'

export interface NicknameCheck {
  state: NicknameCheckState
  message: string | null
  /** 서버 판정을 지금 바로 다시 받는다(입력 대기 없이) */
  recheck: () => void
}

export const NICKNAME_CHECK_DELAY_MS = 400

/**
 * 입력을 잠깐 멈추면 사용 가능 여부를 자동으로 묻는다 (/api/nicknames/availability, IP당 1분 30번).
 * 형식 조건을 못 채운 값은 묻지 않고, 늦게 도착한 옛 응답은 버린다.
 * @param unchanged 지금 쓰는 닉네임과 같으면 true(설정 화면). 묻지 않고 idle로 둔다.
 */
export function useNicknameCheck(raw: string, unchanged = false): NicknameCheck {
  const value = normalizeNickname(raw)
  const [result, setResult] = useState<{ value: string; state: NicknameCheckState; message: string | null } | null>(null)
  const [nonce, setNonce] = useState(0)
  const seq = useRef(0)
  const immediate = useRef(false)
  const rulesOk = nicknameRulesOk(value)
  const skip = !value || unchanged || !rulesOk

  useEffect(() => {
    const my = ++seq.current
    if (skip) return
    const delay = immediate.current ? 0 : NICKNAME_CHECK_DELAY_MS
    immediate.current = false
    const timer = window.setTimeout(() => {
      api<{ available: boolean; message: string | null }>(`/api/nicknames/availability?nickname=${encodeURIComponent(value)}`)
        .then((r) => {
          if (my === seq.current) setResult({ value, state: r.available ? 'available' : 'taken', message: r.available ? null : r.message })
        })
        .catch((err) => {
          if (my !== seq.current) return
          const message = err instanceof ApiError && err.status === 429
            ? t('확인을 너무 자주 했어요. 잠시 뒤 다시 확인해 주세요.')
            : t('쓸 수 있는지 확인하지 못했어요.')
          setResult({ value, state: 'error', message })
        })
    }, delay)
    return () => clearTimeout(timer)
  }, [value, skip, nonce])

  const recheck = () => {
    immediate.current = true
    setResult(null)
    setNonce((n) => n + 1)
  }
  if (!value || unchanged) return { state: 'idle', message: null, recheck }
  if (!rulesOk) return { state: 'rules', message: null, recheck }
  if (!result || result.value !== value) return { state: 'checking', message: null, recheck }
  return { state: result.state, message: result.message, recheck }
}
