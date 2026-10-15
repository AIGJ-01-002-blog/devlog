// 쇼츠 공통 테마: 글꼴·색·여백·화면 배치·자막 모양·움직임 속도. 캐릭터 쇼츠와 매매 재현(#265) 같은 다른 장면이 같은 깔끔한 스타일을 쓴다.
// 밝은 바탕에 진한 글자, 강조색 하나, 둥근 흰 카드, 부드러운 그림자. 값은 여기서만 바꾼다.
import type React from 'react';
import {FONT, MONO} from './fonts';

export const theme = {
  font: FONT,
  mono: MONO,
  color: {
    bg: '#f6f7f9', // 화면 바탕
    surface: '#ffffff', // 카드
    surfaceAlt: '#eef1f5', // 카드 안 칸, 진행 막대 바탕
    ink: '#111827', // 본문 글자
    sub: '#4b5563', // 보조 글자
    muted: '#9ca3af', // 흐린 글자(주소 등)
    line: '#e5e7eb', // 테두리·화살표 바탕
    highlight: '#fde68a', // 형광펜
    good: '#16a34a',
    bad: '#dc2626',
    warn: '#f59e0b',
    code: {bg: '#0f172a', bar: '#1e293b', text: '#e2e8f0', comment: '#7dd3fc', muted: '#94a3b8'},
  },
  /** 장면마다 돌아가며 쓰는 강조색. accent(index)로 고른다 */
  accents: ['#2563eb', '#7c3aed', '#0d9488', '#e11d48'],
  radius: {card: 32, chip: 999, small: 16},
  shadow: {card: '0 18px 40px rgba(15, 23, 42, 0.10), 0 2px 6px rgba(15, 23, 42, 0.06)', soft: '0 8px 20px rgba(15, 23, 42, 0.08)'},
  /** 1080×1920 화면 배치(px). 아래 1600 밑은 쇼츠 앱 버튼·설명에 가리므로 비운다 */
  layout: {gutter: 72, titleTop: 120, headingTop: 210, headingHeight: 340, stageTop: 600, stageWidth: 1080, stageHeight: 720,
    captionTop: 1340, footerTop: 1580},
  type: {
    title: `700 32px ${FONT}`,
    heading: (long: boolean) => `900 ${long ? 88 : 104}px/1.16 ${FONT}`,
    caption: `800 64px/1.3 ${FONT}`,
    label: `800 38px/1.2 ${FONT}`,
    body: `700 34px/1.3 ${FONT}`,
    footer: `600 30px ${FONT}`,
  },
  /** 움직임: spring 설정. 큰 요소는 차분하게, 작은 요소는 톡 튀게 */
  motion: {
    enter: {damping: 18, stiffness: 120}, // 카드·도식 등장
    pop: {damping: 12, stiffness: 180}, // 아이콘·배지
    word: {damping: 14, stiffness: 200}, // 요점 자막 낱말
    caption: {damping: 20, stiffness: 220}, // 구절 자막 바뀜
    transition: {damping: 200}, // 장면 전환
    wordStagger: 3, // 요점 자막 낱말 사이 프레임
  },
} as const;

export const accent = (index: number): string => theme.accents[((index % theme.accents.length) + theme.accents.length) % theme.accents.length];

/** 흰 카드 공통 모양 */
export const card = (extra: React.CSSProperties = {}): React.CSSProperties => ({
  background: theme.color.surface, borderRadius: theme.radius.card, boxShadow: theme.shadow.card, ...extra,
});
