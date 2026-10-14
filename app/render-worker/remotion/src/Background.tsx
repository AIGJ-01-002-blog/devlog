// 장면 배경: 밝은 바탕에 장면 강조색으로 아주 옅은 빛 하나와 모눈. 글 이미지가 있으면 흐리고 밝게 깐다(theme.ts).
import React from 'react';
import {AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {accent, theme} from './theme';

export const Background: React.FC<{index: number; image: string | null}> = ({index, image}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames} = useVideoConfig();
  const t = frame / fps;
  const a = accent(index);
  return (
    <AbsoluteFill style={{background: theme.color.bg, overflow: 'hidden'}}>
      {image ? (
        <>
          <Img src={staticFile(image)} style={{position: 'absolute', inset: -60, width: 'calc(100% + 120px)', height: 'calc(100% + 120px)',
            objectFit: 'cover', filter: 'blur(30px) saturate(0.9)', opacity: 0.35,
            scale: String(interpolate(frame, [0, durationInFrames], [1.05, 1.15]))}} />
          <AbsoluteFill style={{background: 'rgba(246,247,249,0.72)'}} />
        </>
      ) : (
        <div style={{position: 'absolute', width: 1400, height: 1400, borderRadius: '50%', left: -160 + Math.sin(t * 0.4) * 60,
          top: -700 + Math.cos(t * 0.3) * 50, background: `radial-gradient(circle, ${a}1f 0%, ${a}00 62%)`}} />
      )}
      <AbsoluteFill style={{backgroundImage: `linear-gradient(${theme.color.line}66 2px, transparent 2px), linear-gradient(90deg, ${theme.color.line}66 2px, transparent 2px)`,
        backgroundSize: '72px 72px', maskImage: 'linear-gradient(180deg, transparent 0%, #000 30%, #000 75%, transparent 100%)'}} />
    </AbsoluteFill>
  );
};
