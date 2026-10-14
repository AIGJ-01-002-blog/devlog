// 장면 배경: 천천히 떠다니는 빛 두 개와 점 무늬. 글 이미지가 있으면 흐리고 어둡게 깐다.
import React from 'react';
import {AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';

const PALETTES = [
  ['#0b1024', '#2563eb', '#0ea5e9'],
  ['#120c22', '#7c3aed', '#ec4899'],
  ['#071a1e', '#0d9488', '#3b82f6'],
  ['#1a0f16', '#e11d48', '#f97316'],
];

export const Background: React.FC<{index: number; image: string | null}> = ({index, image}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames} = useVideoConfig();
  const t = frame / fps;
  const [base, a, b] = PALETTES[index % PALETTES.length];
  return (
    <AbsoluteFill style={{background: base, overflow: 'hidden'}}>
      {image ? (
        <Img src={staticFile(image)} style={{position: 'absolute', inset: -60, width: 'calc(100% + 120px)', height: 'calc(100% + 120px)',
          objectFit: 'cover', filter: 'blur(28px) brightness(0.42) saturate(0.8)',
          scale: String(interpolate(frame, [0, durationInFrames], [1.05, 1.15]))}} />
      ) : (
        <>
          <div style={{position: 'absolute', width: 1300, height: 1300, borderRadius: '50%', left: -380 + Math.sin(t * 0.5) * 80,
            top: -420 + Math.cos(t * 0.4) * 90, background: `radial-gradient(circle, ${a}99 0%, ${a}00 65%)`}} />
          <div style={{position: 'absolute', width: 1400, height: 1400, borderRadius: '50%', left: 200 + Math.cos(t * 0.45) * 90,
            top: 1000 + Math.sin(t * 0.35) * 80, background: `radial-gradient(circle, ${b}88 0%, ${b}00 65%)`}} />
        </>
      )}
      <AbsoluteFill style={{backgroundImage: 'radial-gradient(rgba(255,255,255,0.07) 2px, transparent 2px)', backgroundSize: '44px 44px',
        backgroundPosition: `0 ${-t * 12}px`}} />
    </AbsoluteFill>
  );
};
