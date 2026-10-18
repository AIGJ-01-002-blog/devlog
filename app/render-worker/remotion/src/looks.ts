// 영상마다 다른 연출(2026-10-17, 수익화 기준: 채널의 영상끼리 서로 달라 보여야 한다). 워커(devlog_render/look.py)가 영상 한 편마다
// 룩 이름 하나와 장면마다 전환·카메라·배치를 골라 props로 넘긴다. 이름은 look.py와 같아야 한다.
// 룩 = 색(바탕·카드·글자·형광펜·강조색) + 배경 무늬 + 구절 자막 모양 + 요점 자막 등장 방식.
import {interpolate, spring} from 'remotion';
import type {TransitionPresentation} from '@remotion/transitions';
import {clockWipe} from '@remotion/transitions/clock-wipe';
import {fade} from '@remotion/transitions/fade';
import {flip} from '@remotion/transitions/flip';
import {iris} from '@remotion/transitions/iris';
import {none} from '@remotion/transitions/none';
import {pushCut} from '@remotion/transitions/push-cut';
import {slide} from '@remotion/transitions/slide';
import {wipe} from '@remotion/transitions/wipe';
import {theme} from './theme';

/** 배경 무늬. grid: 모눈, dots: 점, stripes: 사선, rings: 동심원, plain: 무늬 없이 빛만 */
export type Pattern = 'grid' | 'dots' | 'stripes' | 'rings' | 'plain';
/** 구절 자막. marker: 지금 단어에 강조색+형광펜, outline: 흰 글씨 검은 테두리(강조 낱말만 색), box: 지금 단어를 강조색 칸에 흰 글씨, pop: 지금 단어가 커진다,
 *  chunk: 지금 읽는 1~2어절만 크게(움직임 없이) */
export type CaptionStyle = 'marker' | 'outline' | 'box' | 'pop' | 'chunk';
/** 요점 자막 등장. rise: 낱말이 떠오르고 형광펜, pop: 통째로 튀어나오고 강조색 밑줄, type: 한 글자씩 타자, slide: 왼쪽에서 밀려오고 강조색 막대 */
export type HeadingStyle = 'rise' | 'pop' | 'type' | 'slide';

export type Look = {
  /** 바탕 위 글자(요점·구절 자막·주소)를 밝게 쓰는 어두운 룩인지 */
  dark: boolean;
  /** surfaceAlt: 진행 막대 바탕, line: 배경 무늬 선 */
  color: {bg: string; surfaceAlt: string; highlight: string; onBg: string; onBgSub: string; line: string};
  accents: string[];
  pattern: Pattern;
  caption: CaptionStyle;
  heading: HeadingStyle;
};

export const LOOKS: Record<string, Look> = {
  // 원래 쓰던 깔끔한 밝은 룩
  paper: {dark: false, color: {bg: '#f6f7f9', surfaceAlt: '#eef1f5', highlight: '#fde68a', onBg: '#111827', onBgSub: '#9ca3af', line: '#e5e7eb'},
    accents: ['#2563eb', '#7c3aed', '#0d9488', '#e11d48'], pattern: 'grid', caption: 'marker', heading: 'rise'},
  // 어두운 남색 바탕, 형광 강조색, 테두리 자막
  night: {dark: true, color: {bg: '#0f172a', surfaceAlt: '#1e293b', highlight: '#a78bfa88', onBg: '#f8fafc', onBgSub: '#94a3b8', line: '#334155'},
    accents: ['#22d3ee', '#a3e635', '#f472b6', '#facc15'], pattern: 'dots', caption: 'outline', heading: 'slide'},
  // 연한 민트 바탕, 칸 자막
  mint: {dark: false, color: {bg: '#ecfdf5', surfaceAlt: '#d1fae5', highlight: '#a7f3d0', onBg: '#064e3b', onBgSub: '#6b9080', line: '#bbf7d0'},
    accents: ['#059669', '#0284c7', '#d97706', '#7c3aed'], pattern: 'stripes', caption: 'box', heading: 'pop'},
  // 따뜻한 살구 바탕, 타자 요점
  sunset: {dark: false, color: {bg: '#fff7ed', surfaceAlt: '#ffedd5', highlight: '#fed7aa', onBg: '#431407', onBgSub: '#9a6b4f', line: '#fde3c8'},
    accents: ['#ea580c', '#db2777', '#9333ea', '#0891b2'], pattern: 'rings', caption: 'pop', heading: 'type'},
  // 검정 바탕·노랑 강조. 빠른 정보 릴스 느낌
  mono: {dark: true, color: {bg: '#111111', surfaceAlt: '#262626', highlight: '#facc1599', onBg: '#ffffff', onBgSub: '#a3a3a3', line: '#2a2a2a'},
    accents: ['#facc15', '#ffffff', '#f87171', '#4ade80'], pattern: 'grid', caption: 'outline', heading: 'pop'},
  // 연보라 바탕
  grape: {dark: false, color: {bg: '#f5f3ff', surfaceAlt: '#ede9fe', highlight: '#ddd6fe', onBg: '#2e1065', onBgSub: '#8b7fb0', line: '#e4dcff'},
    accents: ['#7c3aed', '#c026d3', '#2563eb', '#16a34a'], pattern: 'dots', caption: 'chunk', heading: 'slide'},
  // 짙은 바다색 바탕
  ocean: {dark: true, color: {bg: '#082f49', surfaceAlt: '#0c4a6e', highlight: '#38bdf866', onBg: '#f0f9ff', onBgSub: '#7dd3fc', line: '#164e63'},
    accents: ['#38bdf8', '#fbbf24', '#34d399', '#fb7185'], pattern: 'rings', caption: 'box', heading: 'rise'},
};

const DEFAULT = LOOKS.paper;

/** 지금 그리는 영상의 룩. Shorts가 그릴 때마다 useLook으로 정한다(한 영상 안에서는 바뀌지 않는다) */
let current: Look = DEFAULT;

/** 룩을 정하고 theme의 바탕·형광펜·강조색을 그 룩으로 바꾼다. 카드(흰색)와 카드 안 글자·칸 색(surfaceAlt·line)은 그대로 둬서
 *  도식·캐릭터·코드 창은 어느 룩에서나 읽힌다. 바탕 위에 바로 쓰는 글자·무늬는 look().color.onBg·line을 쓴다 */
export const applyLook = (name: string | null | undefined): Look => {
  const look = (name && LOOKS[name]) || DEFAULT;
  current = look;
  const color = theme.color as unknown as Record<string, string>;
  color.bg = look.color.bg;
  color.highlight = look.color.highlight;
  const accents = theme.accents as unknown as string[];
  accents.splice(0, accents.length, ...look.accents);
  return look;
};

export const look = (): Look => current;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Presentation = TransitionPresentation<any>;

/** 장면 전환 이름(look.py TRANSITIONS) → @remotion/transitions. 모르는 이름이면 옆으로 밀기 */
export const transition = (name: string | null | undefined, width: number, height: number): Presentation => {
  switch (name) {
    case 'slide-left': return slide({direction: 'from-left'});
    case 'slide-up': return slide({direction: 'from-bottom'});
    case 'slide-down': return slide({direction: 'from-top'});
    case 'fade': return fade();
    case 'wipe-left': return wipe({direction: 'from-left'});
    case 'wipe-right': return wipe({direction: 'from-right'});
    case 'wipe-diagonal': return wipe({direction: 'from-top-left'});
    case 'flip': return flip({direction: 'from-right'});
    case 'flip-up': return flip({direction: 'from-bottom'});
    case 'clock': return clockWipe({width, height});
    case 'iris': return iris({width, height});
    case 'push': return pushCut({flashColor: '#ffffff', flashOpacity: 0.5});
    case 'cut': return none();
    default: return slide({direction: 'from-right'});
  }
};

/** 무대 카메라(look.py CAMERAS·JUMP): 장면 안에서 무대에 주는 크기·위치. 흔들림 없이 천천히 움직인다.
 *  jump는 cuts(프레임, 구절 자막이 바뀌는 때)마다 확대 배율을 1↔1.1배로 번갈아 끊는다(참고 영상의 점프 컷. 더 키우면 무대 양옆이 잘린다) */
export const camera = (name: string | null | undefined, frame: number, fps: number, duration: number, cuts: number[] = []): {scale: number; x: number; y: number} => {
  const p = interpolate(frame, [0, Math.max(1, duration)], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  switch (name) {
    case 'jump': return {scale: cuts.filter((c) => c <= frame).length % 2 === 0 ? 1 : 1.1, x: 0, y: 0};
    case 'zoom-in': return {scale: interpolate(p, [0, 1], [1, 1.08]), x: 0, y: 0};
    case 'zoom-out': return {scale: interpolate(p, [0, 1], [1.1, 1]), x: 0, y: 0};
    case 'pan-left': return {scale: 1.06, x: interpolate(p, [0, 1], [30, -30]), y: 0};
    case 'pan-right': return {scale: 1.06, x: interpolate(p, [0, 1], [-30, 30]), y: 0};
    case 'tilt-up': return {scale: 1.05, x: 0, y: interpolate(p, [0, 1], [24, -24])};
    case 'still': return {scale: 1, x: 0, y: 0};
    default: // punch: 장면이 시작할 때 아주 살짝 당겨 들어온다
      return {scale: interpolate(spring({frame, fps, config: theme.motion.enter}), [0, 1], [1.03, 1]), x: 0, y: 0};
  }
};
