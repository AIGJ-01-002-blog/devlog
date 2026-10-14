import { useId } from 'react'
import { t } from '../lib/i18n'
import { badgeName } from '../lib/points'

/** 메달 가장자리의 월계수 잎: 왼쪽 아래에서 위로, 오른쪽은 거울로 (중심 20,36 · 반지름 9.4) */
const LEAVES = Array.from({ length: 6 }, (_, i) => {
  const deg = 118 + i * 21
  const rad = (deg * Math.PI) / 180
  return { x: 20 + Math.cos(rad) * 9.4, y: 36 + Math.sin(rad) * 9.4, rot: deg + 90 }
})

/** 365일 휘장의 햇살 별: 꼭짓점 16개 */
const STAR = Array.from({ length: 32 }, (_, i) => {
  const r = i % 2 === 0 ? 16 : 12.6
  const a = (i * Math.PI) / 16 - Math.PI / 2
  return `${(20 + Math.cos(a) * r).toFixed(2)},${(36 + Math.sin(a) * r).toFixed(2)}`
}).join(' ')

/**
 * 연속 출석 휘장 (#266). 실제 훈장처럼 줄무늬 리본, 고리, 금속 메달(빛 받는 면·테두리 홈·월계수·새긴 숫자)을 직접 그렸다.
 * 20일 동 · 50일 은 · 100일 금 · 150일 비취 · 200일 청옥 · 300일 자수정, 365일은 햇살 별 모양 금 훈장에 루비가 박히고 빛이 지나간다.
 * 색은 styles.css의 --badge-* 토큰을 쓰고, 아직 못 받은 휘장은 흐린 회색이다.
 */
export function StreakBadgeIcon({ days, size = 28, locked = false, title }: { days: number; size?: number; locked?: boolean; title?: string }) {
  const id = useId().replace(/:/g, '')
  const label = title ?? (locked ? t('{0} (아직 못 받음)', { 0: badgeName(days) }) : badgeName(days))
  const special = days >= 365
  const metal = `bm${id}`
  const face = `bf${id}`
  const gem = `bg${id}`
  const text = special ? '365' : String(days)
  const height = Math.round(size * 1.3)
  return (
    <span className={`streak-badge d${days}${locked ? ' locked' : ''}${special ? ' special' : ''}`} style={{ width: size, height }}
      role="img" aria-label={label} data-tip={label}>
      <svg viewBox="0 0 40 52" width={size} height={height} aria-hidden="true">
        <defs>
          <linearGradient id={metal} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" className="s-hi" />
            <stop offset="0.45" className="s-base" />
            <stop offset="1" className="s-lo" />
          </linearGradient>
          <radialGradient id={face} cx="0.35" cy="0.3" r="0.85">
            <stop offset="0" className="s-hi" />
            <stop offset="0.55" className="s-base" />
            <stop offset="1" className="s-lo" />
          </radialGradient>
          <radialGradient id={gem} cx="0.35" cy="0.3" r="0.8">
            <stop offset="0" className="s-gem-hi" />
            <stop offset="1" className="s-gem" />
          </radialGradient>
        </defs>
        {/* 리본: 두 갈래로 접힌 띠, 가운데 줄무늬, 가장자리 선, 접힌 그림자 */}
        <path className="rib" d="M7 0h11l3.4 21.5-9.2 1.2z" />
        <path className="rib" d="M22 0h11l-5.2 22.7-9.2-1.2z" />
        <path className="rib-stripe" d="M10.6 0h3.6l3.2 21.8-3.4.4z" />
        <path className="rib-stripe" d="M25.8 0h3.6l-3.4 22.2-3.4-.4z" />
        <path className="rib-edge" d="M7 0h1.4l4.6 22.6-.8.1zM31.6 0H33l-5.2 22.7-.8-.1z" />
        <path className="rib-fold" d="M18 0h4l-.6 21.5h-2.8z" />
        <rect x="17.2" y="19.6" width="5.6" height="4.6" rx="2.3" fill={`url(#${metal})`} className="ring" />
        {special ? (
          <>
            <polygon points={STAR} fill={`url(#${metal})`} className="medal-edge" />
            <circle cx="20" cy="36" r="10" fill={`url(#${face})`} />
            <circle cx="20" cy="36" r="10" className="bevel" />
            <circle cx="20" cy="36" r="4.2" fill={`url(#${gem})`} className="gem" />
            <ellipse cx="18.6" cy="34.6" rx="1.4" ry="0.9" className="gem-glint" />
          </>
        ) : (
          <>
            {/* 메달: 바깥 테(홈), 빛 받는 면, 안쪽 홈, 월계수, 새긴 숫자 */}
            <circle cx="20" cy="36" r="14" fill={`url(#${metal})`} className="medal-edge" />
            <circle cx="20" cy="36" r="13.2" className="reeding" />
            <circle cx="20" cy="36" r="11.4" fill={`url(#${face})`} />
            <circle cx="20" cy="36" r="11.4" className="bevel" />
            {LEAVES.map((l, i) => (
              <g key={i}>
                <ellipse cx={l.x} cy={l.y} rx="1.7" ry="0.75" transform={`rotate(${l.rot} ${l.x} ${l.y})`} className="leaf" />
                <ellipse cx={40 - l.x} cy={l.y} rx="1.7" ry="0.75" transform={`rotate(${180 - l.rot} ${40 - l.x} ${l.y})`} className="leaf" />
              </g>
            ))}
            <text x="20" y="39.4" textAnchor="middle" className="num num-shadow">{text}</text>
            <text x="20" y="38.9" textAnchor="middle" className="num">{text}</text>
          </>
        )}
        <ellipse cx="15.5" cy="30.5" rx="5" ry="2.6" transform="rotate(-30 15.5 30.5)" className="shine" />
      </svg>
    </span>
  )
}
