import { t } from '../lib/i18n'
import { badgeName } from '../lib/points'

/**
 * 연속 출석 휘장 (#266). 리본이 달린 메달 모양을 직접 그렸다. 색은 일수마다 다르고(styles.css의 --badge-* 토큰),
 * 365일 특별 휘장은 금빛 테두리와 빛이 지나가는 효과가 있다(움직임 줄이기 설정이면 멈춘다).
 */
export function StreakBadgeIcon({ days, size = 28, locked = false, title }: { days: number; size?: number; locked?: boolean; title?: string }) {
  const label = title ?? (locked ? t('{0} (아직 못 받음)', { 0: badgeName(days) }) : badgeName(days))
  return (
    <span className={`streak-badge d${days}${locked ? ' locked' : ''}${days >= 365 ? ' special' : ''}`} style={{ width: size, height: size }}
      role="img" aria-label={label} data-tip={label}>
      <svg viewBox="0 0 32 32" width={size} height={size} aria-hidden="true">
        <path className="ribbon" d="M10 2h5l-3 10H7zM17 2h5l3 10h-5z" />
        <circle className="medal" cx="16" cy="20" r="10" />
        <circle className="rim" cx="16" cy="20" r="7.5" />
        <text x="16" y="23.4" textAnchor="middle" className="num">{days >= 365 ? '1Y' : days}</text>
      </svg>
    </span>
  )
}
