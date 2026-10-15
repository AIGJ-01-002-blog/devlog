// 빠른 견본 스타일(#277 참고 영상 분석 1·2·4): 위에는 그림(도식·요점·코드), 아래에는 캐릭터, 그 경계에 한 줄 자막.
// 장면 안에서도 약 2초마다 화면을 끊어 바꾸고(첫 1.5초 안에 두 번), 자막은 흰 굵은 글씨에 검은 테두리, 강조 낱말만 빨강·노랑.
// 승인된 깔끔한 스타일(Scene.tsx)은 그대로 두고, 대본이 style: "fast"일 때만 쓴다.
import React from 'react';
import {AbsoluteFill, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Act, Presenter} from './actions';
import {Background} from './Background';
import {allItems, DiagramStage} from './Diagram';
import {CodeLine} from './props';
import {accent, card, theme} from './theme';
import type {Page, SceneData} from './types';

/** 위 그림 칸(y 170~930), 경계 자막(가운데 y 975), 아래 캐릭터 칸(y 1030~1570) */
const TOP = 170;
const TOP_H = 760;
const LINE = 975;
const BOTTOM = 1030;
const CHAR_SCALE = 0.75;
/** 도식 장면의 발표자는 혼자 아래 칸을 쓰므로 크게(발 아래 끝이 y 1575) */
const PRESENTER_FEET = 1575;
const PRESENTER_SCALE = 1.5;

/** 컷마다 바꾸는 구도: 위 그림 확대·기준점, 아래 캐릭터 확대 */
const FRAMES = [
  {scale: 1, origin: '50% 50%', char: 1},
  {scale: 1.14, origin: '30% 40%', char: 1.12},
  {scale: 1.08, origin: '70% 60%', char: 0.94},
] as const;

const EMPH = {red: '#ef4444', yellow: '#facc15'} as const;

/** 컷 시각(ms, 목소리 시작 기준): 구절 두 개마다 한 번(약 2초). 첫 장면은 시작 1.5초 안에 두 번 더 끊는다 */
const cutTimes = (pages: Page[], first: boolean): number[] => {
  const cuts = pages.filter((_, k) => k > 0 && k % 2 === 0).map((p) => p.startMs);
  return first ? [500, 1100, ...cuts.filter((ms) => ms > 1600)] : cuts;
};

export const FastScene: React.FC<{scene: SceneData; index: number; title: string; footer: string; last?: boolean}> = ({scene, index, title, footer, last = false}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const tint = accent(index);
  const ms = ((frame - scene.leadFrames) / fps) * 1000;
  const cut = cutTimes(scene.pages, index === 0).filter((t) => ms >= t).length + index;
  const f = FRAMES[cut % FRAMES.length];
  const d = scene.diagram;
  const done = d ? ms >= Math.max(...allItems(d).map((it) => it.atMs)) + 1200 : false;
  const beats = d ? allItems(d).map((it) => scene.leadFrames / fps + it.atMs / 1000) : [];
  return (
    <AbsoluteFill>
      <Background index={index} image={scene.image} />
      <div style={{position: 'absolute', top: 84, left: 0, right: 0, textAlign: 'center', font: theme.type.title, color: theme.color.sub,
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', padding: '0 80px'}}>{title}</div>
      {/* 위: 그림. 컷마다 구도가 끊어 바뀐다(이어지는 움직임 없이) */}
      <div style={{position: 'absolute', top: TOP, left: 36, right: 36, height: TOP_H, overflow: 'hidden', ...card({background: '#ffffffee'})}}>
        <div style={{position: 'absolute', inset: 0, scale: String(f.scale), transformOrigin: f.origin}}>
          {d ? (
            // 도식은 원래 왼쪽에 발표자 자리를 비워 두므로, 발표자가 아래로 내려간 여기서는 그만큼 왼쪽으로 당긴다
            <div style={{position: 'absolute', left: d.type === 'stack' || d.type === 'steps' ? -150 : -36, top: 110, width: 1080, height: 720}}>
              <DiagramStage scene={scene} tint={tint} presenter={false} />
            </div>
          ) : (
            <Keyword scene={scene} tint={tint} />
          )}
        </div>
      </div>
      {/* 아래: 캐릭터. 도식 장면은 가리키는 발표자, 그 밖은 원래 연기 */}
      <div style={d ? {position: 'absolute', top: PRESENTER_FEET - 720, left: 0, width: 1080, height: 720, transformOrigin: '50% 100%', scale: String(PRESENTER_SCALE * f.char)}
        : {position: 'absolute', top: BOTTOM, left: 0, width: 1080, height: 720, transformOrigin: '50% 0%', scale: String(CHAR_SCALE * f.char)}}>
        {d ? <Presenter scene={scene} beats={beats} done={done} x={540} /> : <Act scene={scene} first={index === 0} last={last} />}
      </div>
      <FastCaptions pages={scene.pages} leadFrames={scene.leadFrames} />
      <div style={{position: 'absolute', top: theme.layout.footerTop, left: 0, right: 0, textAlign: 'center', font: theme.type.footer, color: theme.color.muted}}>
        {footer}
      </div>
    </AbsoluteFill>
  );
};

/** 도식이 없는 장면의 위 그림: 요점 한 줄을 크게, 코드가 있으면 그 아래 어두운 코드 칸 */
const Keyword: React.FC<{scene: SceneData; tint: string}> = ({scene, tint}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const s = spring({frame, fps, config: theme.motion.pop});
  // 타자 연기는 아래 캐릭터 칸에 코드 창을 직접 그리므로 위에는 요점만 둔다
  const code = scene.action === 'typing' ? [] : scene.code.slice(0, 6);
  return (
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 44, padding: '0 56px'}}>
      <div style={{font: theme.type.heading(scene.caption.length > 10), color: theme.color.ink, textAlign: 'center', wordBreak: 'keep-all',
        letterSpacing: '-0.03em', scale: String(0.8 + 0.2 * s)}}>
        <span style={{background: `linear-gradient(180deg, transparent 64%, ${tint}40 64%)`, padding: '0 8px'}}>{scene.caption}</span>
      </div>
      {code.length ? (
        <div style={{alignSelf: 'stretch', background: theme.color.code.bg, borderRadius: theme.radius.small, padding: '26px 30px', opacity: Math.min(1, s * 1.5)}}>
          {code.map((line, k) => <CodeLine key={k} line={line} size={30} />)}
        </div>
      ) : null}
    </div>
  );
};

/** 경계 자막: 한 줄 2~6낱말, 흰 굵은 글씨에 검은 테두리. 워커가 고른 낱말(emph)만 빨강·노랑 */
const FastCaptions: React.FC<{pages: Page[]; leadFrames: number}> = ({pages, leadFrames}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const ms = ((frame - leadFrames) / fps) * 1000;
  const page = pages.find((p) => ms >= p.startMs && ms < p.endMs);
  if (!page) return null;
  const enter = spring({frame: frame - leadFrames - Math.round((page.startMs / 1000) * fps), fps, config: theme.motion.pop});
  return (
    <div style={{position: 'absolute', left: 30, right: 30, top: LINE - 60, height: 120, display: 'flex', alignItems: 'center', justifyContent: 'center',
      scale: String(0.85 + 0.15 * enter)}}>
      <div style={{font: `900 78px/1.1 ${theme.font}`, letterSpacing: '-0.02em', color: '#ffffff', WebkitTextStroke: '14px #111111', paintOrder: 'stroke fill',
        textAlign: 'center', whiteSpace: 'nowrap', textShadow: '0 6px 0 rgba(0,0,0,0.25)'}}>
        {page.words.map((w, k) => (
          <span key={k} style={{margin: '0 0.12em', color: w.emph ? EMPH[w.emph] : '#ffffff'}}>{w.text}</span>
        ))}
      </div>
    </div>
  );
};
