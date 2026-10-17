// 장면 배경: 룩의 바탕색을 장면 강조색 쪽으로 조금 물들이고(장면마다 바탕색이 달라진다), 강조색 빛 하나와 룩마다 다른 무늬(모눈·점·사선·동심원)를 깐다.
// 글 이미지가 있으면 흐리게 깐다(looks.ts).
import React from 'react';
import {AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {look, type Pattern} from './looks';
import {accent, theme} from './theme';

const MASK = 'linear-gradient(180deg, transparent 0%, #000 30%, #000 75%, transparent 100%)';

const pattern = (kind: Pattern, line: string, t: number): React.CSSProperties | null => {
  switch (kind) {
    case 'grid':
      return {backgroundImage: `linear-gradient(${line}66 2px, transparent 2px), linear-gradient(90deg, ${line}66 2px, transparent 2px)`, backgroundSize: '72px 72px'};
    case 'dots':
      return {backgroundImage: `radial-gradient(${line} 3px, transparent 3.5px)`, backgroundSize: '48px 48px', backgroundPosition: `0 ${(t * 8) % 48}px`};
    case 'stripes':
      return {backgroundImage: `repeating-linear-gradient(135deg, ${line}55 0 3px, transparent 3px 40px)`, backgroundPosition: `${(t * 10) % 57}px 0`};
    case 'rings':
      return {backgroundImage: `repeating-radial-gradient(circle at 50% 38%, ${line}66 0 3px, transparent 3px 64px)`};
    default:
      return null;
  }
};

export const Background: React.FC<{index: number; image: string | null}> = ({index, image}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames} = useVideoConfig();
  const t = frame / fps;
  const a = accent(index);
  const lk = look();
  const lines = pattern(lk.pattern, lk.color.line, t);
  // 참고 영상 분석(2026-10-17): 잘 되는 릴스는 장면마다 바탕색이 바뀐다. 글자가 읽히도록 강조색은 조금만 섞는다
  const bg = `color-mix(in srgb, ${theme.color.bg} ${lk.dark ? 84 : 90}%, ${a})`;
  return (
    <AbsoluteFill style={{background: bg, overflow: 'hidden'}}>
      {image ? (
        <>
          <Img src={staticFile(image)} style={{position: 'absolute', inset: -60, width: 'calc(100% + 120px)', height: 'calc(100% + 120px)',
            objectFit: 'cover', filter: 'blur(30px) saturate(0.9)', opacity: 0.35,
            scale: String(interpolate(frame, [0, durationInFrames], [1.05, 1.15]))}} />
          <AbsoluteFill style={{background: `${theme.color.bg}b8`}} />
        </>
      ) : (
        <div style={{position: 'absolute', width: 1400, height: 1400, borderRadius: '50%', left: -160 + Math.sin(t * 0.4) * 60,
          top: -700 + Math.cos(t * 0.3) * 50, background: `radial-gradient(circle, ${a}${lk.dark ? '33' : '1f'} 0%, ${a}00 62%)`}} />
      )}
      {lines ? <AbsoluteFill style={{...lines, maskImage: MASK}} /> : null}
    </AbsoluteFill>
  );
};
