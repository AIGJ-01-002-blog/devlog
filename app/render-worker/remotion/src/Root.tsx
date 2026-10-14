import React from 'react';
import {Composition} from 'remotion';
import {Shorts} from './Shorts';
import {sample} from './sample';
import type {ShortsProps} from './types';

export const RemotionRoot: React.FC = () => (
  <Composition
    id="Shorts"
    component={Shorts}
    width={1080}
    height={1920}
    fps={30}
    durationInFrames={sample.totalFrames}
    defaultProps={sample}
    calculateMetadata={({props}: {props: ShortsProps}) => ({durationInFrames: props.totalFrames})}
  />
);
