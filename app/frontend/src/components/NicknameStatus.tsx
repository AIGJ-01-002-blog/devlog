import { nicknameRules, type NicknameCheck } from '../lib/nickname'
import { t } from '../lib/i18n'

/**
 * 닉네임 칸 아래 안내: 조건별 ✓와 사용 가능 여부 한 줄 (docs/09).
 * 조건은 치는 대로 바뀌고, 조건을 다 채우면 입력을 멈춘 뒤 서버가 판정한 결과를 보여 준다.
 * 색만으로 구분하지 않도록 ✓·글자를 함께 쓴다(PasswordRules와 같은 모양).
 */
export function NicknameStatus({ value, check, error, hint, id }: {
  value: string
  check: NicknameCheck
  /** 저장·가입 요청이 돌려준 오류. 있으면 판정 줄 대신 보여 준다 */
  error?: string
  /** 비어 있거나 그대로일 때 보여 줄 안내 */
  hint?: string
  id?: string
}) {
  const typed = value.trim() !== ''
  const showRules = typed && check.state !== 'idle'
  const line = error ?? (check.state === 'checking' ? t('쓸 수 있는지 확인하는 중…')
    : check.state === 'available' ? t('쓸 수 있는 닉네임이에요.')
    : check.state === 'taken' || check.state === 'error' ? check.message
    : check.state === 'rules' ? t('위 조건을 채우면 쓸 수 있는지 바로 확인해요.')
    : hint ?? null)
  const tone = error || check.state === 'taken' || check.state === 'error' ? 'error'
    : check.state === 'available' ? 'ok' : 'muted'

  return (
    <div id={id} className="nick-status">
      {showRules && (
        <ul className="pw-rules nick-rules">
          {nicknameRules(value).map((r) => (
            <li key={r.id} className={r.ok ? 'ok' : 'bad'}>
              <span aria-hidden="true">{r.ok ? '✓' : '·'}</span> {r.label}
              <span className="sr-only">{r.ok ? t(' 충족') : t(' 미충족')}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="nick-status-line">
        {line && (
          <small className={tone} role="status" aria-live="polite">
            {check.state === 'available' && !error && <span aria-hidden="true">✓ </span>}{line}
          </small>
        )}
        {check.state === 'error' && !error && (
          <button type="button" className="btn btn-outline btn-small" onClick={check.recheck}
                  data-tip={t('이 닉네임을 쓸 수 있는지 다시 확인해요')}>{t('다시 확인')}</button>
        )}
      </div>
    </div>
  )
}
