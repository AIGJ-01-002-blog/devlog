// 장면 하나: 배경 · 시리즈 제목 · 요점 자막(형광펜) · 무대(고양이 연기 또는 등록된 장면) · 구절 자막 · 블로그 주소
import React from 'react';
import {noise2D} from '@remotion/noise';
import {AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Act, STAGE_H, STAGE_W} from './actions';
import {Background} from './Background';
import {Captions} from './Captions';
import {CUSTOM_SCENES} from './custom';
import {FONT} from './fonts';
import type {SceneData} from './types';

export const STAGE_TOP = 600;

export const Scene: React.FC<{scene: SceneData; index: number; title: string; footer: string}> = ({scene, index, title, footer}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const Custom = scene.custom ? CUSTOM_SCENES[scene.custom.kind] : undefined;
  const words = scene.caption.split(' ');
  // 카메라: 장면이 시작할 때 살짝 당겨 들어오고(펀치 인), 구절이 바뀔 때마다 톡 튀고, 손으로 든 듯 조금씩 흔들린다(@remotion/noise)
  const punch = interpolate(spring({frame, fps, config: {damping: 14, stiffness: 120}}), [0, 1], [1.12, 1]);
  const ms = ((frame - scene.leadFrames) / fps) * 1000;
  const page = [...scene.pages].reverse().find((p) => ms >= p.startMs);
  const bump = page ? spring({frame: frame - scene.leadFrames - Math.round((page.startMs / 1000) * fps), fps, config: {damping: 10, stiffness: 260}}) : 1;
  const beat = 1 + 0.025 * Math.sin(Math.min(1, bump) * Math.PI);
  const driftX = noise2D('x' + index, frame / 90, 0) * 10;
  const driftY = noise2D('y' + index, frame / 90, 0) * 8;
  const marker = interpolate(frame, [0.25 * fps, 0.6 * fps], [0, 100], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
  return (
    <AbsoluteFill>
      <Background index={index} image={scene.image} />
      <div style={{position: 'absolute', top: 130, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}>
        <div style={{font: `700 34px ${FONT}`, color: '#e2e8f0', background: 'rgba(15,23,42,0.55)', padding: '10px 26px', borderRadius: 999,
          border: '2px solid rgba(255,255,255,0.15)', maxWidth: 900, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>
          {title}
        </div>
      </div>
      {/* 요점 자막: 낱말이 차례로 튀어나오고 뒤로 형광펜이 그어진다. 자막이 없는 장면(매매 재현 끝 고지 카드)은 형광펜도 그리지 않는다 */}
      {scene.caption && <div style={{position: 'absolute', top: 230, left: 70, right: 70, height: 330, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
        <div style={{position: 'relative', textAlign: 'center', font: `900 ${scene.caption.length > 12 ? 92 : 108}px/1.18 ${FONT}`,
          letterSpacing: '-0.01em', wordBreak: 'keep-all'}}>
          <div style={{position: 'absolute', left: -14, right: -14, bottom: 6, height: '38%', background: '#facc15', opacity: 0.85,
            borderRadius: 12, clipPath: `inset(0 ${100 - marker}% 0 0)`, rotate: '-1deg'}} />
          {words.map((w, k) => {
            const s = spring({frame: frame - k * 3, fps, config: {damping: 12, stiffness: 200}});
            return (
              <span key={k} style={{position: 'relative', display: 'inline-block', color: '#fff', WebkitTextStroke: '10px #111827',
                paintOrder: 'stroke', marginRight: '0.22em', scale: String(0.4 + 0.6 * s), translate: `0 ${(1 - s) * 40}px`, opacity: Math.min(1, s * 2)}}>
                {w}
              </span>
            );
          })}
        </div>
      </div>}
      <div style={{position: 'absolute', top: STAGE_TOP, left: 0, width: STAGE_W, height: STAGE_H, transformOrigin: '50% 60%',
        scale: String(punch * beat), translate: `${driftX}px ${driftY}px`}}>
        {Custom ? <Custom scene={scene} data={scene.custom?.data} /> : <Act scene={scene} first={index === 0} />}
      </div>
      <Captions pages={scene.pages} leadFrames={scene.leadFrames} top={1330} />
      <div style={{position: 'absolute', top: 1580, left: 0, right: 0, textAlign: 'center', font: `700 32px ${FONT}`, color: 'rgba(226,232,240,0.85)'}}>
        {footer}
      </div>
    </AbsoluteFill>
  );
};
