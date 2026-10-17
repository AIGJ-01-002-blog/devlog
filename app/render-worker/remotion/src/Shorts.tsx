// 쇼츠 한 편: 장면을 이어 붙이고(전환은 워커가 장면마다 고른 scene.transition, @remotion/transitions. cut 장면은 점프 컷), 맨 위에 전체 진행 막대를 그린다.
// 영상마다 룩(색·배경 무늬·자막 모양, looks.ts)이 다르고, 장면마다 배치(stage: Scene, split: FastScene)도 바뀐다.
// 맨 끝 장면은 워커가 늘 붙이는 블로그 광고(custom.kind 'outro', Outro.tsx)다.
// 소리는 워커가 따로 섞는다(전환마다 그 전환에 맞는 소리).
import React from 'react';
import {AbsoluteFill, useCurrentFrame, useVideoConfig} from 'remotion';
import {springTiming, TransitionSeries} from '@remotion/transitions';
import {none} from '@remotion/transitions/none';
import {CharacterName} from './character';
import {FastScene} from './FastScene';
import {applyLook, transition} from './looks';
import {Outro} from './Outro';
import {Scene} from './Scene';
import './fonts';
import {theme} from './theme';
import type {ShortsProps} from './types';

/** 워커가 전환을 고르지 않은 옛 props면 예전처럼 옆으로 밀기·흐려지기·위로 밀기를 돌아가며 */
const OLD_CYCLE = ['slide-right', 'fade', 'slide-up'];

export const Shorts: React.FC<ShortsProps> = ({title, footer, scenes, transitionFrames, style = 'clean', character = null, look = null}) => {
  const frame = useCurrentFrame();
  const {fps, durationInFrames, width, height} = useVideoConfig();
  // 자식 장면이 그려지기 전에 이 영상의 룩을 정한다(theme 색이 룩으로 바뀐다)
  const lk = applyLook(look);
  // 끝 광고(outro)를 뺀 마지막 본문 장면이 마무리 연기(cta)를 한다
  const lastBody = scenes.length - 1 - (scenes[scenes.length - 1]?.custom?.kind === 'outro' ? 1 : 0);
  return (
    <CharacterName.Provider value={character}>
    <AbsoluteFill style={{background: theme.color.bg}}>
      <TransitionSeries>
        {scenes.flatMap((scene, i) => {
          const items = [
            <TransitionSeries.Sequence key={`s${i}`} name={`장면 ${i + 1}`} durationInFrames={scene.durationInFrames} premountFor={fps}>
              {scene.custom?.kind === 'outro' ? <Outro /> :
                (scene.layout ? scene.layout === 'split' : style === 'fast') && !scene.custom ? <FastScene scene={scene} index={i} title={title} footer={footer} last={i === lastBody} /> :
                <Scene scene={scene} index={i} title={title} footer={footer} last={i === lastBody} />}
            </TransitionSeries.Sequence>,
          ];
          if (i < scenes.length - 1 && transitionFrames > 0) {
            items.push(
              <TransitionSeries.Transition key={`t${i}`}
                presentation={scenes[i + 1].cut ? none() : transition(scenes[i + 1].transition ?? OLD_CYCLE[i % 3], width, height)}
                timing={springTiming({config: theme.motion.transition, durationInFrames: transitionFrames, durationRestThreshold: 0.001})} />,
            );
          }
          return items;
        })}
      </TransitionSeries>
      <div style={{position: 'absolute', top: 0, left: 0, right: 0, height: 10, background: lk.color.surfaceAlt}}>
        <div style={{height: '100%', width: `${(frame / Math.max(1, durationInFrames - 1)) * 100}%`, background: lk.dark ? theme.accents[0] : theme.color.ink}} />
      </div>
    </AbsoluteFill>
    </CharacterName.Provider>
  );
};
