// 쇼츠 한 편: 장면을 이어 붙이고(옆으로 밀기·위로 밀기·흐려지기를 돌아가며, @remotion/transitions. cut 장면은 점프 컷), 맨 위에 전체 진행 막대를 그린다.
// 소리는 워커가 따로 섞는다(전환마다 휙 소리).
import React from 'react';
import {AbsoluteFill, useCurrentFrame, useVideoConfig} from 'remotion';
import {springTiming, TransitionSeries, type TransitionPresentation} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {none} from '@remotion/transitions/none';
import {slide} from '@remotion/transitions/slide';
import {CharacterName} from './character';
import {FastScene} from './FastScene';
import {Scene} from './Scene';
import './fonts';
import {theme} from './theme';
import type {ShortsProps} from './types';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const presentation = (i: number): TransitionPresentation<any> =>
  [slide({direction: 'from-right'}), fade(), slide({direction: 'from-bottom'})][i % 3];

export const Shorts: React.FC<ShortsProps> = ({title, footer, scenes, transitionFrames, style = 'clean', character = null}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames} = useVideoConfig();
  return (
    <CharacterName.Provider value={character}>
    <AbsoluteFill style={{background: theme.color.bg}}>
      <TransitionSeries>
        {scenes.flatMap((scene, i) => {
          const items = [
            <TransitionSeries.Sequence key={`s${i}`} name={`장면 ${i + 1}`} durationInFrames={scene.durationInFrames} premountFor={fps}>
              {style === 'fast' && !scene.custom ? <FastScene scene={scene} index={i} title={title} footer={footer} last={i === scenes.length - 1} /> :
                <Scene scene={scene} index={i} title={title} footer={footer} last={i === scenes.length - 1} />}
            </TransitionSeries.Sequence>,
          ];
          if (i < scenes.length - 1 && transitionFrames > 0) {
            items.push(
              <TransitionSeries.Transition key={`t${i}`} presentation={scenes[i + 1].cut ? none() : presentation(i)}
                timing={springTiming({config: theme.motion.transition, durationInFrames: transitionFrames, durationRestThreshold: 0.001})} />,
            );
          }
          return items;
        })}
      </TransitionSeries>
      <div style={{position: 'absolute', top: 0, left: 0, right: 0, height: 10, background: theme.color.surfaceAlt}}>
        <div style={{height: '100%', width: `${(frame / Math.max(1, durationInFrames - 1)) * 100}%`, background: theme.color.ink}} />
      </div>
    </AbsoluteFill>
    </CharacterName.Provider>
  );
};
