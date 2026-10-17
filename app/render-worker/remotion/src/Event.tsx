// 영상 중간의 '사건'(참고 영상 분석 2026-10-17): 목소리가 시작할 때 한 번, 화면이 흔들리거나(shake) 섬광이 터지거나(flash)
// '중요' 도장이 찍힌다(stamp). 워커(look.py)가 영상 한 편에 한두 장면만 고르고, 같은 때 소리도 낸다.
import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {theme} from './theme';
import type {SceneData} from './types';

const SHAKE_FRAMES = 12;

/** 흔들림: 장면 전체를 옮길 양(px). 사건이 shake가 아니면 0 */
export const useShake = (scene: SceneData): string | undefined => {
  const frame = useCurrentFrame();
  const k = frame - scene.leadFrames;
  if (scene.event !== 'shake' || k < 0 || k > SHAKE_FRAMES) return undefined;
  const fade = 1 - k / SHAKE_FRAMES;
  return `${Math.sin(k * 2.7) * 22 * fade}px ${Math.cos(k * 3.3) * 14 * fade}px`;
};

/** 섬광·도장. 장면 맨 위에 덮는다 */
export const EventOverlay: React.FC<{scene: SceneData; tint: string}> = ({scene, tint}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const k = frame - scene.leadFrames;
  if (k < 0) return null;
  if (scene.event === 'flash') {
    const o = interpolate(k, [0, 2, 9], [0, 0.85, 0], {extrapolateRight: 'clamp'});
    return o > 0 ? <AbsoluteFill style={{background: '#ffffff', opacity: o, pointerEvents: 'none'}} /> : null;
  }
  if (scene.event === 'stamp') {
    const s = spring({frame: k, fps, config: {damping: 11, stiffness: 260}});
    const out = interpolate(k, [1.6 * fps, 2 * fps], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
    if (out <= 0) return null;
    return (
      <AbsoluteFill style={{alignItems: 'flex-end', justifyContent: 'flex-start', padding: '540px 90px 0 0', pointerEvents: 'none'}}>
        <div style={{font: `900 92px/1 ${theme.font}`, color: tint, border: `10px solid ${tint}`, borderRadius: 24, padding: '14px 30px',
          background: '#ffffffd9', rotate: '-12deg', scale: String(2.2 - 1.2 * s), opacity: Math.min(1, s * 2) * out, letterSpacing: '0.04em'}}>
          중요
        </div>
      </AbsoluteFill>
    );
  }
  return null;
};
