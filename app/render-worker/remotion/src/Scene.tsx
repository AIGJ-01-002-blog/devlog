// 장면 하나: 배경 · 시리즈 제목 · 요점 자막(형광펜) · 무대(도식, 고양이 연기 또는 등록된 장면) · 구절 자막 · 블로그 주소. 모양은 theme.ts.
import React from 'react';
import {AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Act} from './actions';
import {Background} from './Background';
import {Captions} from './Captions';
import {CUSTOM_SCENES} from './custom';
import {DiagramStage} from './Diagram';
import {accent, theme} from './theme';
import type {SceneData} from './types';

export const STAGE_TOP = theme.layout.stageTop;

export const Scene: React.FC<{scene: SceneData; index: number; title: string; footer: string}> = ({scene, index, title, footer}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const {layout, color, motion} = theme;
  const tint = accent(index);
  const Custom = scene.custom ? CUSTOM_SCENES[scene.custom.kind] : undefined;
  const words = scene.caption.split(' ');
  // 무대는 장면이 시작할 때 아주 살짝 당겨 들어온다(흔들림 없음)
  const punch = interpolate(spring({frame, fps, config: motion.enter}), [0, 1], [1.03, 1]);
  const marker = interpolate(frame, [0.3 * fps, 0.7 * fps], [0, 100], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
  return (
    <AbsoluteFill>
      <Background index={index} image={scene.image} />
      <div style={{position: 'absolute', top: layout.titleTop, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 14, font: theme.type.title, color: color.sub, background: color.surface,
          padding: '12px 28px', borderRadius: theme.radius.chip, boxShadow: theme.shadow.soft, maxWidth: 900, whiteSpace: 'nowrap', overflow: 'hidden'}}>
          <div style={{width: 14, height: 14, borderRadius: 7, background: tint, flex: 'none'}} />
          <span style={{overflow: 'hidden', textOverflow: 'ellipsis'}}>{title}</span>
        </div>
      </div>
      {/* 요점 자막: 낱말이 차례로 떠오르고 뒤로 형광펜이 그어진다 */}
      {scene.caption.trim() ? (
      <div style={{position: 'absolute', top: layout.headingTop, left: layout.gutter, right: layout.gutter, height: layout.headingHeight,
        display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
        <div style={{position: 'relative', textAlign: 'center', font: theme.type.heading(scene.caption.length > 12), letterSpacing: '-0.03em',
          wordBreak: 'keep-all', color: color.ink}}>
          <div style={{position: 'absolute', left: -12, right: -12, bottom: 4, height: '34%', background: color.highlight, borderRadius: 10,
            clipPath: `inset(0 ${100 - marker}% 0 0)`}} />
          {words.map((w, k) => {
            const s = spring({frame: frame - k * motion.wordStagger, fps, config: motion.word});
            return (
              <span key={k} style={{position: 'relative', display: 'inline-block', marginRight: '0.22em', translate: `0 ${(1 - s) * 28}px`,
                opacity: Math.min(1, s * 1.8)}}>
                {w}
              </span>
            );
          })}
        </div>
      </div>
      ) : null}
      <div style={{position: 'absolute', top: layout.stageTop, left: 0, width: layout.stageWidth, height: layout.stageHeight, transformOrigin: '50% 60%',
        scale: String(punch)}}>
        {Custom ? <Custom scene={scene} data={scene.custom?.data} index={index} /> : scene.diagram ? <DiagramStage scene={scene} tint={tint} /> :
          <Act scene={scene} first={index === 0} />}
      </div>
      <Captions pages={scene.pages} leadFrames={scene.leadFrames} top={layout.captionTop} color={tint} />
      <div style={{position: 'absolute', top: layout.footerTop, left: 0, right: 0, textAlign: 'center', font: theme.type.footer, color: color.muted}}>
        {footer}
      </div>
    </AbsoluteFill>
  );
};
