// 구절 자막: 1~2줄을 한 번에 띄우고, 지금 읽는 단어를 노랗게 키운다(틱톡식). 시각은 장면 목소리 시작 기준 ms.
import React from 'react';
import {spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {FONT} from './fonts';
import type {Page} from './types';

export const Captions: React.FC<{pages: Page[]; leadFrames: number; top: number}> = ({pages, leadFrames, top}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const ms = ((frame - leadFrames) / fps) * 1000;
  const page = pages.find((p) => ms >= p.startMs && ms < p.endMs);
  if (!page) return null;
  let current = -1;
  page.words.forEach((w, k) => {
    if (ms >= w.startMs) current = k;
  });
  const enter = spring({frame: frame - leadFrames - Math.round((page.startMs / 1000) * fps), fps, config: {damping: 14, stiffness: 220}});
  return (
    <div style={{position: 'absolute', left: 70, right: 70, top, display: 'flex', justifyContent: 'center', scale: String(0.86 + 0.14 * enter),
      opacity: Math.min(1, enter * 1.6)}}>
      <div style={{textAlign: 'center', font: `900 70px/1.28 ${FONT}`, letterSpacing: '-0.01em', wordBreak: 'keep-all'}}>
        {page.words.map((w, k) => {
          // 지금 읽는 단어 하나만: 시작한 단어 가운데 마지막(쉬는 틈에도 앞 단어가 남아 있게)
          const active = k === current;
          return (
            <span key={k} style={{color: active ? '#fde047' : '#ffffff', WebkitTextStroke: '10px #111827', paintOrder: 'stroke',
              display: 'inline-block', scale: active ? '1.08' : '1', margin: '0 0.17em', textShadow: '0 8px 0 rgba(0,0,0,0.35)'}}>
              {w.text}
            </span>
          );
        })}
      </div>
    </div>
  );
};
