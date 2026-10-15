// 캐릭터 무대 대신 쓸 장면 컴포넌트 등록부.
// 예: 매매 재현(#265)은 CUSTOM_SCENES.replay = ReplayScene 으로 등록하고, 워커가 장면에 {"custom": {"kind": "replay", "data": {...}}}를 넣는다.
// 컴포넌트는 무대 크기(STAGE_W×STAGE_H) 안에 그리고, 장면 안 프레임은 useCurrentFrame()으로 읽는다.
// 요점 자막·구절 자막·배경·진행 막대·전환은 Scene이 그대로 그려 준다.
import type React from 'react';
import {ReplayCard, ReplayScene, ReplayTitle} from './Replay';
import type {SceneData} from './types';

/** index: 영상 안 장면 번호(accent(index)로 다른 장면과 강조색을 맞춘다) */
export type CustomSceneProps = {scene: SceneData; data: unknown; index?: number};

export const CUSTOM_SCENES: Record<string, React.FC<CustomSceneProps>> = {
  replay: ReplayScene,
  'replay-title': ReplayTitle,
  'replay-card': ReplayCard,
};
