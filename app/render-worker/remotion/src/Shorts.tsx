// 쇼츠 한 편: 장면을 이어 붙이고(밀기·닦기·시계 닦기를 돌아가며, @remotion/transitions), 맨 위에 전체 진행 막대를 그린다.
// 소리는 워커가 따로 섞는다(전환마다 휙 소리).
import React from 'react';
import {AbsoluteFill, useCurrentFrame, useVideoConfig} from 'remotion';
import {springTiming, TransitionSeries, type TransitionPresentation} from '@remotion/transitions';
import {clockWipe} from '@remotion/transitions/clock-wipe';
import {slide} from '@remotion/transitions/slide';
import {wipe} from '@remotion/transitions/wipe';
import {Scene} from './Scene';
import './fonts';
import type {ShortsProps} from './types';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const presentation = (i: number, width: number, height: number): TransitionPresentation<any> =>
  [slide({direction: 'from-right'}), wipe({direction: 'from-bottom-left'}), slide({direction: 'from-bottom'}), clockWipe({width, height})][i % 4];

export const Shorts: React.FC<ShortsProps> = ({title, footer, scenes, transitionFrames}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames, width, height} = useVideoConfig();
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
              <TransitionSeries.Transition key={`t${i}`} presentation={presentation(i, width, height)}
                timing={springTiming({config: {damping: 200}, durationInFrames: transitionFrames, durationRestThreshold: 0.001})} />,
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
