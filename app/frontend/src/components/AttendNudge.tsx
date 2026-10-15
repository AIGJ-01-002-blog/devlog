import { useEffect, useState } from 'react'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { t } from '../lib/i18n'
import { attend, badgeName, credits, kstToday, loadPointToday } from '../lib/points'
import { Link } from '../lib/router'

const KEY = 'attend-nudge-closed'

/**
 * 출석 알림 (#266, 블로그 주인 결정 "버튼 + 알림"). 오늘 아직 출석하지 않은 회원에게 화면 위에 한 줄로 띄우고 바로 출석하게 한다.
 * [닫기]는 오늘 하루만 숨긴다(한국 시간 기준). 내 포인트 화면에는 출석 버튼이 따로 있어 띄우지 않는다.
 */
export function AttendNudge({ path }: { path: string }) {
  const { me } = useAuth()
  const on = !!me?.authenticated && !!me.features?.points
  const [reward, setReward] = useState<{ streak: number; reward: number } | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!on || closedToday()) return
    let alive = true
    loadPointToday().then((s) => { if (alive && !s.attendedToday) setReward({ streak: s.streak, reward: s.reward }) }).catch(() => {})
    return () => { alive = false }
  }, [on])

  if (!on || path === '/points' || path.startsWith('/write')) return null
  if (done) {
    return (
      <div className="container" role="status">
        <div className="banner banner-ok attend-nudge">
          <span>{done}</span>
          <button type="button" className="btn btn-text btn-small" onClick={() => setDone(null)} aria-label={t('닫기')} data-tip={t('닫기')}>✕</button>
        </div>
      </div>
    )
  }
  if (!reward) return null

  const go = async () => {
    setBusy(true)
    try {
      const r = await attend()
      const badge = r.newBadges.length > 0 ? ' ' + t('{0} 휘장을 받았어요!', { 0: badgeName(r.newBadges[r.newBadges.length - 1]) }) : ''
      setDone((r.streak > 1 ? t('{0}일 연속 출석! {1}을 받았어요.', { 0: r.streak, 1: credits(r.earned) }) : t('출석했어요. {0}을 받았어요.', { 0: credits(r.earned) })) + badge)
    } catch (e) {
      setDone(e instanceof ApiError ? e.message : t('출석하지 못했어요. 잠시 뒤 다시 시도해 주세요.'))
    } finally {
      setReward(null)
      setBusy(false)
    }
  }
  const close = () => {
    try { localStorage.setItem(KEY, kstToday()) } catch { /* 저장소를 못 쓰면 이번 화면에서만 닫는다 */ }
    setReward(null)
  }

  return (
    <div className="container">
      <div className="banner attend-nudge" role="region" aria-label={t('출석 알림')}>
        <span>
          {reward.streak > 0
            ? t('{0}일 연속 출석 중이에요. 오늘 출석하면 {1}을 받아요.', { 0: reward.streak, 1: credits(reward.reward) })
            : t('오늘 출석하고 {0}을 받으세요.', { 0: credits(reward.reward) })}
        </span>
        <span className="attend-nudge-actions">
          <button type="button" className="btn btn-primary btn-small" disabled={busy} onClick={() => void go()}
            data-tip={t('하루에 한 번, 한국 시간 0시에 새로 출석할 수 있어요')}>{busy ? t('출석하는 중…') : t('출석하기')}</button>
          <Link to="/points" className="btn btn-text btn-small" data-tip={t('출석하고 크레딧 잔액과 내역 보기')}>{t('내 크레딧')}</Link>
          <button type="button" className="btn btn-text btn-small" onClick={close} aria-label={t('오늘은 닫기')} data-tip={t('오늘은 이 알림을 띄우지 않아요')}>✕</button>
        </span>
      </div>
    </div>
  )
}

function closedToday(): boolean {
  try {
    return localStorage.getItem(KEY) === kstToday()
  } catch {
    return false
  }
}
