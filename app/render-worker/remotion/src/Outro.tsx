// 모든 쇼츠 맨 끝 3초: 팔로우 권유와 devlog 주소. 2026-10-28 인스타 인사이트에서 1,175명이 보고 프로필 방문·팔로우가 0이라
// 블로그 소개 대신 "매일 하나씩 올라온다"는 다음 행동을 먼저 보여 준다. 문구와 바탕은 대본·룩과 상관없이 여기 고정한다(바꿀 때는 OUTRO만 고친다).
// 장면 틀(제목 칩·자막·주소) 없이 화면 전체를 쓰고, 아래 1600 밑은 쇼츠 앱 버튼에 가리므로 비운다.
import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {LOGO_PATH} from './logo';
import {LOOKS} from './looks';
import {theme} from './theme';

export const OUTRO = {
  name: 'devlog',
  tagline: '매일 저녁 8시에 하나씩',
  invite: '팔로우하고 내일 질문도 받아 보세요',
  url: 'devlog.life',
  color: '#7c3aed',
} as const;

export const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const {color, motion} = theme;
  const at = (delay: number, config: {damping: number; stiffness: number} = motion.enter) =>
    spring({frame: frame - delay * fps, fps, config});
  const logo = at(0.05, motion.pop);
  const name = at(0.2);
  const tagline = at(0.35);
  const pill = at(0.6, motion.pop);
  // 주소 칩은 자리 잡은 뒤 천천히 숨 쉬듯 커졌다 작아진다
  const breathe = 1 + Math.max(0, Math.sin((frame / fps - 1.1) * Math.PI * 1.4)) * 0.04 * Math.min(1, Math.max(0, frame / fps - 1.1) * 4);
  const rise = (s: number, px = 40): React.CSSProperties => ({opacity: Math.min(1, s * 1.6), translate: `0 ${(1 - s) * px}px`});
  return (
    <AbsoluteFill style={{background: LOOKS.paper.color.bg, overflow: 'hidden'}}>
      <div style={{position: 'absolute', width: 1500, height: 1500, borderRadius: '50%', left: -210, top: -150,
        background: `radial-gradient(circle, ${OUTRO.color}26 0%, ${OUTRO.color}00 60%)`,
        scale: String(interpolate(frame, [0, 3 * fps], [0.9, 1.05], {extrapolateRight: 'clamp'}))}} />
      <AbsoluteFill style={{alignItems: 'center', paddingTop: 430}}>
        <div style={{width: 260, height: 260, borderRadius: 64, background: color.ink, display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: theme.shadow.card, scale: String(logo), rotate: `${(1 - logo) * -12}deg`}}>
          <svg viewBox="0 0 1070 1070" width={190} height={190}>
            <path fillRule="evenodd" d={LOGO_PATH} fill="#ffffff" />
          </svg>
        </div>
        <div style={{font: `900 150px/1 ${theme.font}`, letterSpacing: '-0.05em', color: color.ink, marginTop: 64, ...rise(name)}}>{OUTRO.name}</div>
        <div style={{font: `800 58px/1.3 ${theme.font}`, color: color.ink, marginTop: 48, ...rise(tagline)}}>{OUTRO.tagline}</div>
        <div style={{font: `700 44px/1.3 ${theme.font}`, color: color.sub, marginTop: 14, ...rise(tagline)}}>{OUTRO.invite}</div>
        <div style={{marginTop: 80, padding: '30px 64px', borderRadius: theme.radius.chip, background: OUTRO.color, color: '#ffffff',
          font: `900 64px/1 ${theme.font}`, letterSpacing: '-0.02em', boxShadow: `0 18px 40px ${OUTRO.color}55`,
          opacity: Math.min(1, pill * 1.6), scale: String((0.6 + 0.4 * pill) * breathe)}}>
          {OUTRO.url}
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
