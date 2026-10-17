// 구절 자막: 1~2줄을 한 번에 띄우고 지금 읽는 단어를 강조한다. 강조 방식은 룩마다 다르다(looks.ts CaptionStyle).
// marker: 강조색 글자+형광펜, outline: 흰 글씨 검은 테두리에 워커가 고른 낱말만 빨강·노랑, box: 강조색 칸에 흰 글씨(밝은 칸이면 검정), pop: 강조색으로 커진다.
// 시각은 장면 목소리 시작 기준 ms.
import React from 'react';
import {spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {look} from './looks';
import {theme} from './theme';
import type {Page} from './types';

const EMPH = {red: '#ef4444', yellow: '#facc15'} as const;

/** 칸 색 위 글자색: 검정·흰색 가운데 대비가 큰 쪽(#rrggbb 상대 휘도 0.179 기준) */
const onColor = (hex: string): string => {
  const m = /^#([0-9a-f]{6})/i.exec(hex);
  if (!m) return '#ffffff';
  const [r, g, b] = [0, 2, 4].map((k) => {
    const c = parseInt(m[1].slice(k, k + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.179 ? '#111111' : '#ffffff';
};

export const Captions: React.FC<{pages: Page[]; leadFrames: number; top: number; color: string}> = ({pages, leadFrames, top, color}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const lk = look();
  const ms = ((frame - leadFrames) / fps) * 1000;
  const page = pages.find((p) => ms >= p.startMs && ms < p.endMs);
  if (!page) return null;
  let current = -1;
  page.words.forEach((w, k) => {
    if (ms >= w.startMs) current = k;
  });
  const enter = spring({frame: frame - leadFrames - Math.round((page.startMs / 1000) * fps), fps, config: theme.motion.caption});
  const wordStart = current >= 0 ? Math.round((page.words[current].startMs / 1000) * fps) + leadFrames : 0;
  const pop = spring({frame: frame - wordStart, fps, config: theme.motion.pop});
  const outline = lk.caption === 'outline';
  return (
    <div style={{position: 'absolute', left: theme.layout.gutter, right: theme.layout.gutter, top, display: 'flex', justifyContent: 'center',
      translate: `0 ${(1 - enter) * 18}px`, opacity: Math.min(1, enter * 1.6)}}>
      <div style={{textAlign: 'center', font: outline ? `900 68px/1.3 ${theme.font}` : theme.type.caption, letterSpacing: '-0.02em', wordBreak: 'keep-all',
        color: outline ? '#ffffff' : lk.color.onBg, ...(outline ? {WebkitTextStroke: '12px #111111', paintOrder: 'stroke fill'} : {})}}>
        {page.words.map((w, k) => {
          // 지금 읽는 단어 하나만: 시작한 단어 가운데 마지막(쉬는 틈에도 앞 단어가 남아 있게)
          const active = k === current;
          let style: React.CSSProperties;
          switch (lk.caption) {
            case 'outline':
              style = {color: w.emph ? EMPH[w.emph] : active ? color : '#ffffff', scale: active ? String(1 + 0.06 * pop) : '1'};
              break;
            case 'box':
              style = active ? {color: onColor(color), background: color, padding: '0 0.18em', borderRadius: 14} : {};
              break;
            case 'pop':
              style = active ? {color, scale: String(1 + 0.16 * pop), translate: `0 ${-6 * pop}px`} : {opacity: 0.85};
              break;
            default:
              style = active ? {color, background: `linear-gradient(180deg, transparent 62%, ${theme.color.highlight} 62%)`} : {};
          }
          return (
            <span key={k} style={{display: 'inline-block', margin: '0 0.14em', padding: '0 0.08em', borderRadius: 10, ...style}}>
              {w.text}
            </span>
          );
        })}
      </div>
    </div>
  );
};
