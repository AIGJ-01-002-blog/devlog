// 쇼츠 한 편: 장면을 이어 붙이고(밀어 넘기기 전환), 맨 위에 전체 진행 막대를 그린다. 소리는 워커가 따로 섞는다.
import React from 'react';
import {AbsoluteFill, useCurrentFrame, useVideoConfig} from 'remotion';
import {linearTiming, TransitionSeries} from '@remotion/transitions';
import {slide} from '@remotion/transitions/slide';
import {Scene} from './Scene';
import './fonts';
import type {ShortsProps} from './types';

export const Shorts: React.FC<ShortsProps> = ({title, footer, scenes, transitionFrames}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames} = useVideoConfig();
  return (
    <AbsoluteFill style={{background: '#0b1024'}}>
      <TransitionSeries>
        {scenes.flatMap((scene, i) => {
          const items = [
            <TransitionSeries.Sequence key={`s${i}`} name={`장면 ${i + 1}`} durationInFrames={scene.durationInFrames} premountFor={fps}>
              <Scene scene={scene} index={i} title={title} footer={footer} />
            </TransitionSeries.Sequence>,
          ];
          if (i < scenes.length - 1 && transitionFrames > 0) {
            items.push(
              <TransitionSeries.Transition key={`t${i}`} presentation={slide({direction: i % 2 === 0 ? 'from-right' : 'from-bottom'})}
                timing={linearTiming({durationInFrames: transitionFrames})} />,
            );
          }
          return items;
        })}
      </TransitionSeries>
      <div style={{position: 'absolute', top: 0, left: 0, height: 12, width: `${(frame / Math.max(1, durationInFrames - 1)) * 100}%`,
        background: 'linear-gradient(90deg, #38bdf8, #facc15)'}} />
    </AbsoluteFill>
  );
};
