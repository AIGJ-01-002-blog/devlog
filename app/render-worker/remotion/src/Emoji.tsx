// 디자이너가 만든 움직이는 그림: Google Noto 애니메이션 이모지(Lottie, CC BY 4.0).
// 마스코트(고양이 얼굴)와 소품(전구·로켓·폭죽 등)을 @remotion/lottie로 그린다. 재생은 장면 프레임에 맞춰 Remotion이 돌린다.
import {Lottie, type LottieAnimationData} from '@remotion/lottie';
import React from 'react';
import type {Pose} from './Cat';
import bulb from './emoji/1f4a1.json';
import party from './emoji/1f389.json';
import rocket from './emoji/1f680.json';
import hourglass from './emoji/23f3.json';
import fire from './emoji/1f525.json';
import sparkles from './emoji/2728.json';
import check from './emoji/2705.json';
import siren from './emoji/1f6a8.json';
import trash from './emoji/1f5d1_fe0f.json';
import thinking from './emoji/1f914.json';
import boom from './emoji/1f4a5.json';
import hundred from './emoji/1f4af.json';
import sleepy from './emoji/1f62a.json';
import catGrin from './emoji/1f63a.json';
import catSmile from './emoji/1f638.json';
import catJoy from './emoji/1f639.json';
import catLove from './emoji/1f63b.json';
import catWry from './emoji/1f63c.json';
import catKiss from './emoji/1f63d.json';
import catShock from './emoji/1f640.json';
import catCry from './emoji/1f63f.json';
import catPout from './emoji/1f63e.json';

const DATA = {
  bulb, party, rocket, hourglass, fire, sparkles, check, siren, trash, thinking, boom, hundred, sleepy,
  catGrin, catSmile, catJoy, catLove, catWry, catKiss, catShock, catCry, catPout,
} as unknown as Record<string, LottieAnimationData>;

export type EmojiName = keyof typeof DATA;

/** 가운데가 (x, y)인 움직이는 이모지 하나 */
export const Emoji: React.FC<{name: EmojiName; x: number; y: number; size: number; scale?: number; rotate?: number; opacity?: number;
  speed?: number}> = ({name, x, y, size, scale = 1, rotate = 0, opacity = 1, speed = 1}) => (
  <div style={{position: 'absolute', left: x - size / 2, top: y - size / 2, width: size, height: size, scale: String(scale),
    rotate: `${rotate}deg`, opacity}}>
    <Lottie animationData={DATA[name]} loop playbackRate={speed} style={{width: size, height: size}} />
  </div>
);

/** 표정 이름 → 고양이 얼굴 */
export type Face = 'grin' | 'smile' | 'joy' | 'love' | 'wry' | 'kiss' | 'shock' | 'cry' | 'pout';
const FACES: Record<Face, EmojiName> = {
  grin: 'catGrin', smile: 'catSmile', joy: 'catJoy', love: 'catLove', wry: 'catWry', kiss: 'catKiss', shock: 'catShock', cry: 'catCry',
  pout: 'catPout',
};

/** 마스코트: 고양이 얼굴 이모지. 연기(actions.tsx)가 정한 위치·크기·찌그러짐·기울기·말하기를 그대로 받는다 */
export const Mascot: React.FC<{p: Pose; face: Face}> = ({p, face}) => {
  const size = p.size * 0.92;
  // 말할 때 소리 크기만큼 살짝 커졌다 작아져서 말하는 느낌을 준다
  const talk = 1 + 0.05 * p.talk;
  return (
    <div style={{position: 'absolute', left: p.x - size / 2, top: p.y - size, width: size, height: size, transformOrigin: '50% 100%',
      scale: `${(talk / Math.sqrt(p.squash)).toFixed(4)} ${(talk * p.squash).toFixed(4)}`, rotate: `${p.bodyTilt + p.headTilt * 0.6}deg`}}>
      <div style={{position: 'absolute', left: size * 0.2, right: size * 0.2, bottom: size * 0.02, height: size * 0.07, borderRadius: '50%',
        background: 'rgba(0,0,0,0.28)'}} />
      <Lottie animationData={DATA[FACES[face]]} loop style={{width: size, height: size}} />
    </div>
  );
};
