import { useId } from 'react'
import { tierKey, tierName } from '../lib/shop'

/**
 * 상점 티어 문장 (#268). 아이언~챌린저를 금속 방패로 직접 그렸다(게임 회사의 문장 그림은 쓰지 않고 이름·색만 빌렸다).
 * 방패는 빛 받는 면·깎은 윗변·테두리 홈·깎은 보석이 있고, 아이언·브론즈는 리벳, 실버부터는 점 각인이 들어간다.
 * 실버부터 양옆 장식, 다이아부터 날개, 마스터부터 왕관이 붙는다.
 * 색은 티어 토큰(--ta 밝은 쪽, --tb 어두운 쪽)이고 빛·반짝임은 CSS(.tier-emblem.t6~t9)가 맡는다.
 */
export function TierEmblem({ tier, size = 22, label = true }: { tier: number; size?: number; label?: boolean }) {
  const id = useId().replace(/:/g, '')
  const name = tierName(tier)
  const metal = `tm${id}`
  const face = `tf${id}`
  const wing = `tw${id}`
  return (
    <span className={`tier-emblem t${tier} tier-${tierKey(tier)}`} title={label ? undefined : name}>
      <svg width={size} height={size} viewBox="0 0 40 40" aria-hidden="true">
        <defs>
          <linearGradient id={metal} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" className="s-a" />
            <stop offset="0.5" className="s-m" />
            <stop offset="1" className="s-b" />
          </linearGradient>
          <radialGradient id={face} cx="0.38" cy="0.28" r="0.9">
            <stop offset="0" className="s-a" />
            <stop offset="0.6" className="s-m" />
            <stop offset="1" className="s-b" />
          </radialGradient>
          <linearGradient id={wing} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" className="s-a" />
            <stop offset="1" className="s-b" />
          </linearGradient>
        </defs>
        {tier >= 6 && (
          <g fill={`url(#${wing})`} className="wing">
            <path d="M10 13 C6 11 3 9 0.5 8.5 C2 12 3 14 5 15.5 C3 16 1.6 17 1 18.5 C4 19 6.5 19 9 18 C7 20 6 21.5 5.8 23 C8.5 22 10.5 20.5 11.5 19 Z" />
            <path d="M30 13 C34 11 37 9 39.5 8.5 C38 12 37 14 35 15.5 C37 16 38.4 17 39 18.5 C36 19 33.5 19 31 18 C33 20 34 21.5 34.2 23 C31.5 22 29.5 20.5 28.5 19 Z" />
          </g>
        )}
        {tier >= 3 && tier < 6 && (
          <g fill={`url(#${metal})`} className="flank">
            <path d="M9.5 12 L5.5 14 L7.5 22 L10.5 24 Z" />
            <path d="M30.5 12 L34.5 14 L32.5 22 L29.5 24 Z" />
          </g>
        )}
        {tier >= 7 && (
          <g className="crown">
            <path d="M12.5 9.5 L11.5 3.5 L15.5 6.5 L20 1.5 L24.5 6.5 L28.5 3.5 L27.5 9.5 Z" fill={`url(#${metal})`} />
            <circle cx="20" cy="4.6" r="1.2" className="jewel" />
            {tier >= 8 && <><circle cx="13" cy="6.4" r="0.9" className="jewel" /><circle cx="27" cy="6.4" r="0.9" className="jewel" /></>}
          </g>
        )}
        <path className="shield" d="M20 7 L31.5 11 L30.5 23 Q28.5 32 20 37.5 Q11.5 32 9.5 23 L8.5 11 Z" fill={`url(#${metal})`} />
        <path className="shield-face" d="M20 9.8 L29 13 L28.2 22.6 Q26.6 29.8 20 34.4 Q13.4 29.8 11.8 22.6 L11 13 Z" fill={`url(#${face})`} />
        <path className="groove" d="M20 9.8 L29 13 L28.2 22.6 Q26.6 29.8 20 34.4 Q13.4 29.8 11.8 22.6 L11 13 Z" />
        {/* 빛 받는 윗변: 금속 방패의 깎은 모서리 */}
        <path className="bevel-hi" d="M10.2 12 L20 8.4 L30.2 12" />
        {/* 아이언·브론즈는 리벳으로 박은 철판, 실버부터는 테 안쪽에 점 각인 */}
        {tier <= 2 ? (
          <g className="rivet"><circle cx="12.6" cy="14.2" r="0.95" /><circle cx="27.4" cy="14.2" r="0.95" /><circle cx="13.6" cy="24.6" r="0.95" /><circle cx="26.4" cy="24.6" r="0.95" /></g>
        ) : (
          <path className="stipple" d="M20 11.6 L27.4 14.2 L26.8 22.4 Q25.4 28.6 20 32.6 Q14.6 28.6 13.2 22.4 L12.6 14.2 Z" />
        )}
        {/* 깎은 보석: 밝은 면 둘, 어두운 면 둘 */}
        <path className="gem-lo" d="M20 14 L25 20.5 L20 29 Z" />
        <path className="gem-hi" d="M20 14 L15 20.5 L20 29 Z" />
        <path className="gem-top" d="M20 14 L25 20.5 L20 22 L15 20.5 Z" />
        <path className="gem-edge" d="M20 14 L25 20.5 L20 29 L15 20.5 Z" />
        <ellipse cx="16.2" cy="13.6" rx="3.2" ry="1.4" transform="rotate(-24 16.2 13.6)" className="shine" />
      </svg>
      {label && <span className="tier-emblem-name">{name}</span>}
    </span>
  )
}
