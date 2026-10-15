// 디자이너가 만든 움직이는 그림: Google Noto 애니메이션 이모지(Lottie, CC BY 4.0).
// 마스코트(고양이 얼굴)와 소품(전구·로켓·폭죽 등)을 @remotion/lottie로 그린다. 재생은 장면 프레임에 맞춰 Remotion이 돌린다.
import {Lottie, type LottieAnimationData} from '@remotion/lottie';
import React from 'react';
import {Freeze, useCurrentFrame} from 'remotion';
import type {Pose} from './Cat';
import {MOUTH, ONCE, useCharacter, type Clip, type Clips} from './character';
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
import pointR from './emoji/1f449.json';
import pointL from './emoji/1f448.json';
import wave from './emoji/1f44b.json';
import thumbs from './emoji/1f44d.json';

const DATA = {
  bulb, party, rocket, hourglass, fire, sparkles, check, siren, trash, thinking, boom, hundred, sleepy,
  catGrin, catSmile, catJoy, catLove, catWry, catKiss, catShock, catCry, catPout, pointR, pointL, wave, thumbs,
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

/** 마스코트 몸 색: Noto 고양이 얼굴과 같은 색(노랑 #ffc021, 줄무늬 #ff6f43)으로 이어 그린다 */
const FUR = '#ffc021';
const FUR_D = '#f5a300';
const STRIPE = '#ff6f43';
const CREAM = '#fff1c4';

/** 팔 하나: 어깨(sx, sy)에서 angle도(0 = 아래, 양수 = 바깥으로 들어 올림)로 뻗는다. side -1 왼쪽, 1 오른쪽 */
const Arm: React.FC<{H: number; side: -1 | 1; angle: number}> = ({H, side, angle}) => {
  const len = H * 0.24;
  const w = H * 0.085;
  const sx = side * H * 0.17;
  const sy = -H * 0.36;
  // SVG 회전은 시계 방향. 오른팔은 바깥(오른쪽)으로 들면 반시계(-), 왼팔은 시계(+)
  // 머리가 커서 위로 곧게 들면 얼굴을 가리므로, 위로 든 팔은 바깥 대각선(최대 120도)까지만 든다
  const a = Math.min(angle, 120);
  const rot = side === 1 ? -a : a;
  return (
    <g transform={`translate(${sx} ${sy}) rotate(${rot})`}>
      <rect x={-w / 2} y={0} width={w} height={len} rx={w / 2} fill={FUR} />
      <circle cx={0} cy={len} r={w * 0.62} fill={FUR} />
      <path d={`M ${-w * 0.22} ${len + w * 0.3} v ${w * 0.22} M ${w * 0.22} ${len + w * 0.3} v ${w * 0.22}`} stroke={FUR_D} strokeWidth={w * 0.12} strokeLinecap="round" />
    </g>
  );
};

/** 마스코트: Noto 고양이 얼굴(Lottie) + 같은 색으로 그린 몸·팔·다리·꼬리. 연기(actions.tsx)가 정한 위치·크기·찌그러짐·기울기·팔 각도·꼬리·말하기를 받는다 */
const HAPPY: Face[] = ['grin', 'joy', 'love'];

/** 표정 → 블로그 주인 캐릭터의 동작(character/). 연기가 clip을 직접 주면 그걸 쓴다 */
const CLIP_OF: Record<Face, Clip> = {grin: 'idle', smile: 'idle', joy: 'happy', love: 'happy', wry: 'typing', kiss: 'think', shock: 'shock', cry: 'sad',
  pout: 'sleepy'};

/** 블로그 주인 캐릭터: 동작 Lottie를 발끝 기준으로 그린다. 없는 동작은 말할 때 talk, 아니면 idle */
const Character: React.FC<{p: Pose; clip: Clip; clips: Clips}> = ({p, clip, clips}) => {
  // 캐릭터 그림은 512 칸 안에 발끝까지 약 80%라, 고양이와 같은 키로 보이게 조금 크게 그린다
  const H = p.size * 1.18;
  const talking = p.talk > 0.06;
  const want: Clip = clip === 'idle' && talking ? 'talk' : clip;
  const data = clips[want] ?? (talking ? clips.talk : undefined) ?? clips.idle!;
  const anim = <Lottie animationData={data} loop={!ONCE.includes(want)} style={{width: H, height: H}} />;
  // 말하기 동작은 목소리 크기에 맞춰 입 모양 프레임(닫힘·조금·크게)에 세운다
  const mouth = p.talk < 0.08 ? MOUTH.closed : p.talk < 0.32 ? MOUTH.small : MOUTH.open;
  return (
    <div style={{position: 'absolute', left: p.x - H / 2, top: p.y - H, width: H, height: H, transformOrigin: '50% 100%',
      scale: `${(p.flip ? -1 : 1) / Math.sqrt(p.squash)} ${p.squash}`, rotate: `${p.bodyTilt}deg`}}>
      {want === 'talk' && data === clips.talk ? <Freeze frame={mouth}>{anim}</Freeze> : anim}
    </div>
  );
};

export const Mascot: React.FC<{p: Pose; face: Face; clip?: Clip}> = ({p, face, clip}) => {
  const clips = useCharacter();
  if (clips) return <Character p={p} clip={clip ?? CLIP_OF[face]} clips={clips} />;
  return <Cat p={p} face={face} />;
};

/** 기본 캐릭터: Noto 고양이 얼굴 + 같은 색 몸 */
const Cat: React.FC<{p: Pose; face: Face}> = ({p, face}) => {
  const frame = useCurrentFrame();
  const H = p.size;
  const head = H * 0.66;
  // 기쁜 표정 뒤에는 숨 쉬듯 커졌다 작아지는 빛을 깔아 시선을 모은다
  const rays = HAPPY.includes(face);
  // 말할 때 머리가 소리 크기만큼 살짝 커지고 까딱인다
  const talk = 1 + 0.05 * p.talk;
  // 발: 공중에 뜨면(뛰기) 오므리고, 걷는 듯 번갈아 들썩인다
  const step = Math.sin(frame / 4) * H * 0.008;
  const tailRot = -20 + p.tail * 0.8;
  return (
    <>
    {rays ? (
      <div style={{position: 'absolute', left: p.x - H * 0.6, top: p.y - H * 1.2, width: H * 1.2, height: H * 1.2, borderRadius: '50%',
        background: 'radial-gradient(circle, rgba(253,224,71,0.45) 0%, rgba(253,224,71,0.18) 35%, rgba(253,224,71,0) 62%)',
        scale: String(1 + 0.06 * Math.sin(frame / 8))}} />
    ) : null}
    <div style={{position: 'absolute', left: p.x - H / 2, top: p.y - H, width: H, height: H, transformOrigin: '50% 100%',
      scale: `${(p.flip ? -1 : 1) / Math.sqrt(p.squash)} ${p.squash}`, rotate: `${p.bodyTilt}deg`}}>
      <svg viewBox={`${-H / 2} ${-H} ${H} ${H}`} width={H} height={H} style={{position: 'absolute', inset: 0, overflow: 'visible'}}>
        <ellipse cx={0} cy={-H * 0.012} rx={H * 0.26} ry={H * 0.035} fill="rgba(15,23,42,0.16)" />
        {/* 꼬리: 엉덩이 뒤에서 위로 말려 올라가고 꼬리 각도만큼 흔들린다 */}
        <g transform={`translate(${H * 0.13} ${-H * 0.12}) rotate(${tailRot})`}>
          <path d={`M 0 0 C ${H * 0.16} ${-H * 0.02}, ${H * 0.2} ${-H * 0.16}, ${H * 0.14} ${-H * 0.28}`} stroke={FUR} strokeWidth={H * 0.07} fill="none" strokeLinecap="round" />
          <path d={`M ${H * 0.165} ${-H * 0.13} l ${H * 0.05} ${-H * 0.012} M ${H * 0.16} ${-H * 0.21} l ${H * 0.05} ${H * 0.01}`} stroke={STRIPE} strokeWidth={H * 0.022} strokeLinecap="round" />
        </g>
        {/* 다리 */}
        {[-1, 1].map((s) => (
          <ellipse key={s} cx={s * H * 0.09} cy={-H * 0.04 + (s === 1 ? step : -step)} rx={H * 0.075} ry={H * 0.05} fill={FUR} />
        ))}
        {/* 몸통과 배 */}
        <ellipse cx={0} cy={-H * 0.2} rx={H * 0.19} ry={H * 0.19} fill={FUR} />
        <ellipse cx={0} cy={-H * 0.17} rx={H * 0.11} ry={H * 0.13} fill={CREAM} />
      </svg>
      {/* 머리: Noto 고양이 얼굴. 기울기·말하기는 머리만 */}
      <div style={{position: 'absolute', left: (H - head) / 2, top: H * 0.04, width: head, height: head, transformOrigin: '50% 85%',
        rotate: `${p.headTilt}deg`, scale: String(talk)}}>
        <Lottie animationData={DATA[FACES[face]]} loop style={{width: head, height: head}} />
      </div>
      {/* 팔은 머리 앞에 그려서 들어 올려도 보인다 */}
      <svg viewBox={`${-H / 2} ${-H} ${H} ${H}`} width={H} height={H} style={{position: 'absolute', inset: 0, overflow: 'visible'}}>
        {/* 놀란 얼굴(🙀)은 이모지에 손이 있어 팔을 내린다 */}
        <Arm H={H} side={-1} angle={face === 'shock' ? 15 : p.armL} />
        <Arm H={H} side={1} angle={face === 'shock' ? 15 : p.armR} />
      </svg>
    </div>
    </>
  );
};
