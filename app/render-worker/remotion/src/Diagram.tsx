// CS 설명 도식(#277): 흐름·층·순서·비교. 칸은 내레이션이 그 이름을 말할 때 나타나고(atMs), 지금 말하는 칸이 강조된다.
// 도식은 무대 위쪽 DIAGRAM_H(440px)에 그리고, 고양이는 그 아래 띠에서 작게 서서 가리킨다(actions.tsx Presenter). 무대는 1080×720.
import React from 'react';
import {interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Presenter} from './actions';
import {IconBadge, iconFor, ICONS} from './Icons';
import {card, theme} from './theme';
import type {Diagram, DiagramItem, SceneData} from './types';

const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
const {color} = theme;

type Clock = {ms: number; frame: number; fps: number; leadFrames: number};

/** atMs부터 0→1 등장 */
const appear = (c: Clock, atMs: number) =>
  spring({frame: c.frame - c.leadFrames - Math.round((atMs / 1000) * c.fps), fps: c.fps, config: theme.motion.enter});

/** 지금 말하고 있는 칸: 나타난 칸 가운데 마지막 */
const currentIndex = (items: DiagramItem[], ms: number) => items.reduce((cur, it, k) => (ms >= it.atMs ? k : cur), -1);

export const allItems = (d: Diagram): DiagramItem[] => (d.type === 'compare' ? [...d.left.items, ...d.right.items] : d.items);

/** 도식 칸의 높이. 그 아래 띠(무대 720 중 나머지)에 발표자가 서서 도식을 가리지 않는다 */
export const DIAGRAM_H = 440;

export const DiagramStage: React.FC<{scene: SceneData; tint: string; presenter?: boolean}> = ({scene, tint, presenter = true}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const d = scene.diagram;
  if (!d) return null;
  const c: Clock = {ms: ((frame - scene.leadFrames) / fps) * 1000, frame, fps, leadFrames: scene.leadFrames};
  const items = allItems(d);
  const beats = items.map((it) => scene.leadFrames / fps + it.atMs / 1000);
  const done = items.length > 0 && c.ms >= Math.max(...items.map((it) => it.atMs)) + 1200;
  const Body = {flow: Flow, stack: Stack, steps: Steps, compare: Compare}[d.type] as React.FC<{d: Diagram; c: Clock; tint: string}>;
  return (
    <>
      <div style={{position: 'absolute', left: 40, right: 40, top: 0, height: DIAGRAM_H}}>
        <Body d={d} c={c} tint={tint} />
      </div>
      {presenter ? <Presenter scene={scene} beats={beats} done={done} /> : null}
    </>
  );
};

/** 왼쪽→오른쪽 흐름(예: 브라우저 → 서버 → DB). 화살표를 따라 점이 흘러간다 */
const Flow: React.FC<{d: Diagram; c: Clock; tint: string}> = ({d, c, tint}) => {
  if (d.type !== 'flow') return null;
  const n = d.items.length;
  const gap = 70;
  const w = Math.min(300, (1000 - (n - 1) * gap) / n);
  const cur = currentIndex(d.items, c.ms);
  const t = c.frame / c.fps;
  return (
    <div style={{position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', gap}}>
      {d.items.map((it, k) => {
        const s = appear(c, it.atMs);
        const on = k === cur;
        const next = d.items[k + 1];
        const line = next ? interpolate(appear(c, next.atMs), [0, 1], [0, 1], clamp) : 0;
        const dot = (t * 0.9 + k * 0.3) % 1;
        return (
          <div key={k} style={{position: 'relative', width: w, opacity: Math.min(1, s * 1.5), translate: `0 ${(1 - s) * 40}px`, scale: String(on ? 1.06 : 1)}}>
            <div style={card({padding: '34px 16px 28px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 18,
              outline: on ? `6px solid ${tint}` : 'none'})}>
              <IconBadge Icon={iconFor(it.label, it.icon)} color={tint} size={Math.min(130, w * 0.5)} on={on ? 1 : 0} />
              <div style={{font: theme.type.label, color: color.ink, textAlign: 'center', wordBreak: 'keep-all', fontSize: w < 220 ? 32 : 38}}>{it.label}</div>
              {it.note ? <div style={{font: theme.type.body, fontSize: 28, color: color.sub, textAlign: 'center', wordBreak: 'keep-all'}}>{it.note}</div> : null}
            </div>
            {next ? (
              <div style={{position: 'absolute', left: w + 8, top: '50%', width: gap - 16, height: 8, marginTop: -4}}>
                <div style={{position: 'absolute', inset: 0, background: color.line, borderRadius: 4}} />
                <div style={{position: 'absolute', left: 0, top: 0, bottom: 0, width: `${line * 100}%`, background: tint, borderRadius: 4}} />
                <div style={{position: 'absolute', right: -6, top: -10, width: 0, height: 0, borderTop: '14px solid transparent', borderBottom: '14px solid transparent',
                  borderLeft: `18px solid ${line >= 1 ? tint : color.line}`}} />
                {line >= 1 ? <div style={{position: 'absolute', left: `${dot * 100}%`, top: -6, width: 20, height: 20, marginLeft: -10, borderRadius: 10,
                  background: tint, boxShadow: `0 0 0 6px ${tint}33`}} /> : null}
              </div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
};

/** 위→아래 층(예: 컨테이너 / 볼륨 / 디스크). highlight 층은 다 나온 뒤 강조색으로 남는다 */
const Stack: React.FC<{d: Diagram; c: Clock; tint: string}> = ({d, c, tint}) => {
  if (d.type !== 'stack') return null;
  const n = d.items.length;
  const h = Math.min(100, (DIAGRAM_H - 40 - (n - 1) * 18) / n);
  const cur = currentIndex(d.items, c.ms);
  const allShown = c.ms >= Math.max(...d.items.map((it) => it.atMs)) + 600;
  return (
    <div style={{position: 'absolute', left: 40, right: 40, top: 0, bottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 18}}>
      {d.items.map((it, k) => {
        const s = appear(c, it.atMs);
        const on = allShown && d.highlight !== null ? k === d.highlight : k === cur;
        return (
          <div key={k} style={card({height: h, display: 'flex', alignItems: 'center', gap: 24, padding: '0 30px', opacity: Math.min(1, s * 1.5),
            translate: `${(1 - s) * 80}px 0`, background: on ? tint : color.surface, width: `${100 - k * 4}%`, alignSelf: 'center'})}>
            <IconBadge Icon={iconFor(it.label, it.icon)} color={on ? '#ffffff' : tint} size={h * 0.66} on={0} />
            <div style={{font: theme.type.label, color: on ? '#ffffff' : color.ink, flex: 1}}>{it.label}</div>
            {it.note ? <div style={{font: theme.type.body, fontSize: 28, color: on ? '#ffffffcc' : color.sub}}>{it.note}</div> : null}
          </div>
        );
      })}
    </div>
  );
};

/** 순서(1, 2, 3…). 말한 단계는 번호가 체크로 바뀐다 */
const Steps: React.FC<{d: Diagram; c: Clock; tint: string}> = ({d, c, tint}) => {
  if (d.type !== 'steps') return null;
  const n = d.items.length;
  const h = Math.min(92, (DIAGRAM_H - 40 - (n - 1) * 16) / n);
  const cur = currentIndex(d.items, c.ms);
  const Check = ICONS.check;
  return (
    <div style={{position: 'absolute', left: 40, right: 40, top: 0, bottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 16}}>
      {d.items.map((it, k) => {
        const s = appear(c, it.atMs);
        const passed = k < cur;
        const on = k === cur;
        return (
          <div key={k} style={card({height: h, display: 'flex', alignItems: 'center', gap: 26, padding: '0 28px', opacity: Math.min(1, s * 1.5),
            translate: `0 ${(1 - s) * 30}px`, outline: on ? `5px solid ${tint}` : 'none'})}>
            <div style={{width: h * 0.62, height: h * 0.62, borderRadius: '50%', display: 'grid', placeItems: 'center', flex: 'none',
              background: passed || on ? tint : color.surfaceAlt, color: passed || on ? '#fff' : color.sub, font: `900 ${h * 0.34}px ${theme.font}`}}>
              {passed ? <Check size={h * 0.36} color="#fff" strokeWidth={3} /> : k + 1}
            </div>
            <div style={{font: theme.type.label, color: color.ink, flex: 1, wordBreak: 'keep-all'}}>{it.label}</div>
            {it.note ? <div style={{font: theme.type.body, fontSize: 28, color: color.sub}}>{it.note}</div> : null}
          </div>
        );
      })}
    </div>
  );
};

/** 비교: 왼쪽(전·나쁜 쪽, 빨강 ✕) 대 오른쪽(후·좋은 쪽, 초록 ✓) */
const Compare: React.FC<{d: Diagram; c: Clock; tint: string}> = ({d, c}) => {
  if (d.type !== 'compare') return null;
  const col = (side: {title: string; atMs: number; items: DiagramItem[]}, good: boolean) => {
    const s = appear(c, side.atMs);
    const tone = good ? color.good : color.bad;
    const Mark = good ? ICONS.check : ICONS.x;
    return (
      <div style={card({flex: 1, padding: '30px 30px 34px', display: 'flex', flexDirection: 'column', gap: 20, opacity: Math.min(1, s * 1.5),
        translate: `0 ${(1 - s) * 40}px`, borderTop: `10px solid ${tone}`})}>
        <div style={{display: 'flex', alignItems: 'center', gap: 14}}>
          <div style={{width: 52, height: 52, borderRadius: 26, background: `${tone}1f`, display: 'grid', placeItems: 'center', flex: 'none'}}>
            <Mark size={32} color={tone} strokeWidth={3} />
          </div>
          <div style={{font: theme.type.label, fontSize: 42, color: color.ink, wordBreak: 'keep-all'}}>{side.title}</div>
        </div>
        {side.items.map((it, k) => {
          const si = appear(c, it.atMs);
          const Icon = iconFor(it.label, it.icon);
          return (
            <div key={k} style={{display: 'flex', alignItems: 'center', gap: 14, opacity: Math.min(1, si * 1.5), translate: `${(1 - si) * 24}px 0`}}>
              {Icon ? <Icon size={34} color={color.sub} strokeWidth={2.2} /> : <div style={{width: 12, height: 12, borderRadius: 6, background: tone, margin: '0 11px'}} />}
              <div style={{font: theme.type.body, color: color.ink, wordBreak: 'keep-all'}}>{it.label}</div>
            </div>
          );
        })}
      </div>
    );
  };
  return (
    <div style={{position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, display: 'flex', gap: 28, alignItems: 'center'}}>
      {col(d.left, false)}
      {col(d.right, true)}
    </div>
  );
};
