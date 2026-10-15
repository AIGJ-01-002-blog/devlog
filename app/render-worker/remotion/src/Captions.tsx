// 구절 자막: 1~2줄을 한 번에 띄우고, 지금 읽는 단어를 장면 강조색으로 칠하고 밑에 형광펜을 깐다. 시각은 장면 목소리 시작 기준 ms.
import React from 'react';
import {spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {theme} from './theme';
import type {Page} from './types';

export const Captions: React.FC<{pages: Page[]; leadFrames: number; top: number; color: string}> = ({pages, leadFrames, top, color}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const ms = ((frame - leadFrames) / fps) * 1000;
  const page = pages.find((p) => ms >= p.startMs && ms < p.endMs);
  if (!page) return null;
  let current = -1;
  page.words.forEach((w, k) => {
    if (ms >= w.startMs) current = k;
  });
  const enter = spring({frame: frame - leadFrames - Math.round((page.startMs / 1000) * fps), fps, config: theme.motion.caption});
  return (
    <div style={{position: 'absolute', left: theme.layout.gutter, right: theme.layout.gutter, top, display: 'flex', justifyContent: 'center',
      translate: `0 ${(1 - enter) * 18}px`, opacity: Math.min(1, enter * 1.6)}}>
      <div style={{textAlign: 'center', font: theme.type.caption, letterSpacing: '-0.02em', wordBreak: 'keep-all', color: theme.color.ink}}>
        {page.words.map((w, k) => {
          // 지금 읽는 단어 하나만: 시작한 단어 가운데 마지막(쉬는 틈에도 앞 단어가 남아 있게)
          const active = k === current;
          return (
            <span key={k} style={{display: 'inline-block', margin: '0 0.14em', padding: '0 0.08em', borderRadius: 10, color: active ? color : theme.color.ink,
              background: active ? `linear-gradient(180deg, transparent 62%, ${theme.color.highlight} 62%)` : 'transparent'}}>
              {w.text}
            </span>
          );
        })}
      </div>
    </div>
  );
};
