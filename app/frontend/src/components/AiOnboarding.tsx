import { useEffect, useRef, useState } from 'react'
import { Modal } from './Modal'
import { CopyCode } from './CopyCode'
import { Link } from '../lib/router'
import { mcpEndpoint, tokensApi } from '../lib/mcp'
import { markOnboardingDone, ONBOARDING_STEPS } from '../lib/aiOnboarding'
import { t } from '../lib/i18n'

/**
 * 첫 로그인 AI 연결 안내 팝업 (spec 098).
 * 세 단계: 무엇이 되나 → 어떻게 연결하나 → 공개는 내가. 단계마다 짧은 장면(CSS 애니메이션)과 한 줄 설명을 둔다.
 * 마지막 단계의 [AI 연결하러 가기]가 설정 › AI 연결(토큰 만들기·연결 안내, 052·087)로 바로 보낸다.
 * 움직임 줄이기를 켠 사람에게는 styles.css의 전역 규칙이 장면을 멈춘 마지막 모습으로 보인다.
 */
export function AiOnboarding({ memberId, onClose }: { memberId: number; onClose: () => void }) {
  const [step, setStep] = useState(0)
  const [checked, setChecked] = useState(false)
  const nextRef = useRef<HTMLButtonElement>(null)

  // 이미 AI를 연결한 회원(다른 기기에서 연결 등)에게는 띄우지 않는다. 목록을 못 받으면 그냥 보여 준다
  useEffect(() => {
    let live = true
    tokensApi.list()
      .then((list) => {
        if (!live) return
        if (list.some((tok) => !tok.expired)) close()
        else setChecked(true)
      })
      .catch(() => { if (live) setChecked(true) })
    return () => { live = false }
  }, [])

  // 모달은 첫 조작 요소(단계 점)에 초점을 두므로, 열린 다음 프레임에 [다음]으로 옮긴다. Enter만 눌러도 넘어가게
  useEffect(() => {
    if (!checked) return
    const id = requestAnimationFrame(() => nextRef.current?.focus())
    return () => cancelAnimationFrame(id)
  }, [checked])

  function close() {
    markOnboardingDone(memberId)
    onClose()
  }

  if (!checked) return null
  const last = step === ONBOARDING_STEPS - 1

  return (
    <Modal labelledBy="ai-onboarding-title" onClose={close}>
      <div className="ai-onboarding">
        <p className="ai-onboarding-kicker muted small">{t('devlog 시작하기 · {0}/{1}', { 0: step + 1, 1: ONBOARDING_STEPS })}</p>
        {/* key로 단계가 바뀔 때마다 장면을 처음부터 다시 튼다 */}
        <div key={step} className="ai-onboarding-body">
          {step === 0 && <StepTalk />}
          {step === 1 && <StepConnect />}
          {step === 2 && <StepPublish onLeave={close} />}
        </div>

        <ol className="ai-onboarding-dots" aria-label={t('안내 단계')}>
          {Array.from({ length: ONBOARDING_STEPS }, (_, i) => (
            <li key={i}>
              <button type="button" className={i === step ? 'active' : undefined} aria-current={i === step ? 'step' : undefined}
                      title={t('{0}단계 보기', { 0: i + 1 })} aria-label={t('{0}단계 보기', { 0: i + 1 })} onClick={() => setStep(i)} />
            </li>
          ))}
        </ol>

        <div className="dialog-footer ai-onboarding-footer">
          <button type="button" className="btn btn-text" title={t('안내를 닫아요. 다시 뜨지 않고, 설정 › AI 연결에서 언제든 연결할 수 있어요')} onClick={close}>
            {t('나중에 하기')}
          </button>
          <span className="ai-onboarding-nav">
            {step > 0 && (
              <button type="button" className="btn btn-outline" title={t('앞 단계로 돌아가요')} onClick={() => setStep(step - 1)}>{t('이전')}</button>
            )}
            {last ? (
              <Link to="/settings#ai" className="btn btn-primary" title={t('설정 › AI 연결에서 연결을 시작해요')} onClick={close}>
                {t('AI 연결하러 가기')}
              </Link>
            ) : (
              <button ref={nextRef} type="button" className="btn btn-primary" title={t('다음 단계로 넘어가요')} onClick={() => setStep(step + 1)}>{t('다음')}</button>
            )}
          </span>
        </div>
      </div>
    </Modal>
  )
}

/** 1단계: AI에게 한마디 하면 임시글이 생긴다 */
function StepTalk() {
  return (
    <>
      <div className="ai-scene ai-scene-talk" aria-hidden="true">
        <div className="ai-scene-app">
          <span className="ai-scene-label">{t('AI 앱')}</span>
          <span className="ai-scene-bubble">{t('오늘 개발 일지 써 줘')}</span>
        </div>
        <span className="ai-scene-arrow">→</span>
        <div className="ai-scene-draft">
          <span className="ai-scene-label">devlog</span>
          <b>{t('임시글')}</b>
          <span className="ai-scene-lines"><i /><i /><i /></span>
        </div>
      </div>
      <h2 id="ai-onboarding-title">{t('AI에게 말하면 개발 일지가 써져요')}</h2>
      <p className="muted">{t('Claude·ChatGPT 같은 AI 앱에 devlog를 연결하면 "개발 일지 써 줘" 한마디로 오늘 한 일이 임시글로 쌓여요.')}</p>
    </>
  )
}

/** 2단계: 커넥터에 주소를 넣고 [허용] */
function StepConnect() {
  const endpoint = mcpEndpoint(window.location.origin)
  return (
    <>
      <div className="ai-scene ai-scene-connect" aria-hidden="true">
        <div className="ai-scene-field">
          <span className="ai-scene-label">{t('커넥터 주소')}</span>
          <span className="ai-scene-typed">{endpoint}</span>
        </div>
        <span className="ai-scene-allow">{t('허용')}</span>
        <span className="ai-scene-done">✓ {t('연결됨')}</span>
      </div>
      <h2 id="ai-onboarding-title">{t('주소 하나만 넣으면 연결돼요')}</h2>
      <p className="muted">{t('AI 앱의 커넥터(플러그인)에 아래 주소를 넣고, devlog 동의 화면에서 [허용]을 누르면 끝이에요.')}</p>
      <CopyCode label={t('MCP 서버 주소')} code={endpoint} />
      <ul className="ai-onboarding-kinds small">
        <li><b>{t('로그인 연결')}</b> <span className="muted">{t('ChatGPT·Claude 앱. 토큰 없이 [허용]만 누르면 돼요.')}</span></li>
        <li><b>{t('토큰 연결')}</b> <span className="muted">{t('Claude Code·Cursor·Codex. 설정에서 토큰을 만들어 붙여 넣어요.')}</span></li>
      </ul>
    </>
  )
}

/** 3단계: AI는 임시글까지, 공개는 내가 */
function StepPublish({ onLeave }: { onLeave: () => void }) {
  return (
    <>
      <div className="ai-scene ai-scene-publish" aria-hidden="true">
        <div className="ai-scene-draft">
          <span className="ai-scene-label">{t('임시글')}</span>
          <span className="ai-scene-lines"><i /><i /></span>
          <span className="ai-scene-publish-btn">{t('발행')}</span>
        </div>
        <span className="ai-scene-arrow">→</span>
        <span className="ai-scene-done">✓ {t('공개')}</span>
      </div>
      <h2 id="ai-onboarding-title">{t('공개는 언제나 내가 정해요')}</h2>
      <p className="muted">{t('AI는 임시글까지만 써요. 읽어 보고 내가 [발행]을 눌러야 공개되고, 연결은 설정에서 언제든 끊을 수 있어요.')}</p>
      <p className="small">
        <Link to="/mcp" onClick={onLeave} title={t('앱별 연결 순서와 AI가 쓸 수 있는 도구를 봐요')}>{t('연결 방법 자세히 보기')}</Link>
      </p>
    </>
  )
}
