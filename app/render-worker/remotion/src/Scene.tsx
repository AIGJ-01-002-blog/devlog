// 장면 하나: 배경 · 시리즈 제목 · 요점 자막 · 무대(도식, 고양이 연기 또는 등록된 장면) · 구절 자막 · 블로그 주소. 모양은 theme.ts.
// 요점 자막 등장 방식과 바탕 위 글자색은 룩(looks.ts), 무대 카메라 움직임은 워커가 장면마다 고른 scene.camera를 따른다.
import React from 'react';
import {AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Act} from './actions';
import {Background} from './Background';
import {Captions} from './Captions';
import {CUSTOM_SCENES} from './custom';
import {DiagramStage} from './Diagram';
import {EventOverlay, useShake} from './Event';
import {camera, look} from './looks';
import {accent, theme} from './theme';
import type {SceneData} from './types';

export const STAGE_TOP = theme.layout.stageTop;

export const Scene: React.FC<{scene: SceneData; index: number; title: string; footer: string; last?: boolean}> = ({scene, index, title, footer, last = false}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const {layout, color} = theme;
  const lk = look();
  const tint = accent(index);
  const Custom = scene.custom ? CUSTOM_SCENES[scene.custom.kind] : undefined;
  const cam = camera(scene.camera, frame, fps, scene.durationInFrames);
  const shake = useShake(scene);
  return (
    <AbsoluteFill style={{translate: shake}}>
      <Background index={index} image={scene.image} />
      <div style={{position: 'absolute', top: layout.titleTop, left: 0, right: 0, display: 'flex', justifyContent: 'center'}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 14, font: theme.type.title, color: color.sub, background: color.surface,
          padding: '12px 28px', borderRadius: theme.radius.chip, boxShadow: theme.shadow.soft, maxWidth: 900, whiteSpace: 'nowrap', overflow: 'hidden'}}>
          <div style={{width: 14, height: 14, borderRadius: 7, background: tint, flex: 'none'}} />
          <span style={{overflow: 'hidden', textOverflow: 'ellipsis'}}>{title}</span>
        </div>
      </div>
      {scene.caption.trim() ? (
      <div style={{position: 'absolute', top: layout.headingTop, left: layout.gutter, right: layout.gutter, height: layout.headingHeight,
        display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
        <Heading text={scene.caption} tint={tint} duration={scene.durationInFrames} />
      </div>
      ) : null}
      <div style={{position: 'absolute', top: layout.stageTop, left: 0, width: layout.stageWidth, height: layout.stageHeight, transformOrigin: '50% 60%',
        scale: String(Custom ? 1 : cam.scale), translate: Custom ? undefined : `${cam.x}px ${cam.y}px`}}>
        {Custom ? <Custom scene={scene} data={scene.custom?.data} index={index} /> : scene.diagram ? <DiagramStage scene={scene} tint={tint} /> :
          <Act scene={scene} first={index === 0} last={last} />}
      </div>
      <Captions pages={scene.pages} leadFrames={scene.leadFrames} top={layout.captionTop} color={tint} />
      <div style={{position: 'absolute', top: layout.footerTop, left: 0, right: 0, textAlign: 'center', font: theme.type.footer, color: lk.color.onBgSub}}>
        {footer}
      </div>
      <EventOverlay scene={scene} tint={tint} />
    </AbsoluteFill>
  );
};

/** 요점 자막. 룩마다 등장 방식이 다르다: rise(낱말이 떠오르고 형광펜), pop(통째로 튀어나오고 강조색 밑줄), type(한 글자씩), slide(왼쪽에서 밀려오고 강조색 막대) */
const Heading: React.FC<{text: string; tint: string; duration: number}> = ({text, tint, duration}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const {color, motion} = theme;
  const lk = look();
  const marker = interpolate(frame, [0.3 * fps, 0.7 * fps], [0, 100], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
  const base: React.CSSProperties = {position: 'relative', textAlign: 'center', font: theme.type.heading(text.length > 12), letterSpacing: '-0.03em',
    wordBreak: 'keep-all', color: lk.color.onBg};
  if (lk.heading === 'pop') {
    const s = spring({frame, fps, config: motion.pop});
    return (
      <div style={{...base, scale: String(0.6 + 0.4 * s), opacity: Math.min(1, s * 2)}}>
        {text}
        <div style={{position: 'absolute', left: '10%', right: '10%', bottom: -18, height: 12, borderRadius: 6, background: tint,
          clipPath: `inset(0 ${100 - marker}% 0 0)`}} />
      </div>
    );
  }
  if (lk.heading === 'type') {
    const chars = Array.from(text);
    // 한 글자에 1.6프레임. 장면이 짧으면 끝나기 전에 다 써지도록 빨라진다
    const step = Math.min(1.6, Math.max(1, duration - 1) / Math.max(1, chars.length));
    const shown = Math.min(chars.length, Math.floor(frame / step));
    const caret = shown < chars.length || Math.floor(frame / (fps / 2)) % 2 === 0;
    // 아직 안 쓴 글자도 투명하게 자리를 잡아 두어 글자가 늘어날 때 줄이 흔들리지 않게 한다. 커서는 마지막으로 쓴 글자 뒤에 붙는다
    return (
      <div style={base}>
        {chars.map((ch, k) => (
          <span key={k} style={{color: k < shown ? undefined : 'transparent', position: 'relative',
            background: k < shown ? `linear-gradient(180deg, transparent 64%, ${color.highlight} 64%)` : undefined,
            boxShadow: k === shown - 1 && caret ? `6px 0 0 0 ${tint}` : undefined}}>{ch}</span>
        ))}
      </div>
    );
  }
  if (lk.heading === 'slide') {
    const s = spring({frame, fps, config: motion.enter});
    return (
      <div style={{...base, display: 'flex', alignItems: 'stretch', gap: 26, textAlign: 'left', translate: `${(1 - s) * -140}px 0`, opacity: Math.min(1, s * 1.6)}}>
        <div style={{width: 16, borderRadius: 8, background: tint, flex: 'none', scale: `1 ${s}`}} />
        <div>{text}</div>
      </div>
    );
  }
  // rise: 낱말이 차례로 떠오르고 뒤로 형광펜이 그어진다
  return (
    <div style={base}>
      <div style={{position: 'absolute', left: -12, right: -12, bottom: 4, height: '34%', background: color.highlight, borderRadius: 10,
        clipPath: `inset(0 ${100 - marker}% 0 0)`}} />
      {text.split(' ').map((w, k) => {
        const s = spring({frame: frame - k * motion.wordStagger, fps, config: motion.word});
        return (
          <span key={k} style={{position: 'relative', display: 'inline-block', marginRight: '0.22em', translate: `0 ${(1 - s) * 28}px`,
            opacity: Math.min(1, s * 1.8)}}>
            {w}
          </span>
        );
      })}
    </div>
  );
};
