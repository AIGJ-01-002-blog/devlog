// devlog 쇼츠의 2D 고양이 캐릭터. 포즈 값(Pose)만 받아 SVG로 그린다. 움직임은 연기(actions.tsx)가 프레임마다 정한다.
import React from 'react';

export type Eyes = 'open' | 'blink' | 'happy' | 'shock' | 'bored' | 'sad' | 'focus';
export type Mouth = 'smile' | 'talk' | 'o' | 'frown' | 'flat' | 'grin';

export type Pose = {
  x: number; // 무대 안 발 중심 x(px)
  y: number; // 발 중심 y(px)
  size: number; // 캐릭터 높이(px)
  squash: number; // 1보다 작으면 납작, 크면 길쭉
  bodyTilt: number; // 몸 기울기(도)
  headTilt: number; // 머리 기울기(도)
  armL: number; // 왼팔 각도(도, 0 = 아래, 양수 = 바깥으로 들어 올림)
  armR: number;
  tail: number; // 꼬리 흔들기(도)
  eyes: Eyes;
  look: [number, number]; // 눈동자 방향 -1~1
  mouth: Mouth;
  talk: number; // 0~1 입 벌림
  brows: '' | 'worried' | 'angry' | 'up';
  sweat: number; // 0~1
  tears: number; // 0~1 (흐르는 위치)
  blush: number; // 0~1
  flip: boolean; // 좌우 뒤집기
};

export const basePose = (over: Partial<Pose> = {}): Pose => ({
  x: 540, y: 640, size: 520, squash: 1, bodyTilt: 0, headTilt: 0, armL: 12, armR: 12, tail: 0,
  eyes: 'open', look: [0, 0], mouth: 'smile', talk: 0, brows: '', sweat: 0, tears: 0, blush: 1, flip: false,
  ...over,
});

const INK = '#3b2a22';
const FUR = '#ffd9a0';
const FUR_SHADE = '#f4c27e';
const STRIPE = '#eea55a';
const BELLY = '#fff3dd';
const PINK = '#ff9fb0';

/** 팔 하나: 어깨를 축으로 돌아가는 둥근 막대 + 발 */
const Arm: React.FC<{sx: number; sy: number; angle: number; side: -1 | 1}> = ({sx, sy, angle, side}) => (
  <g transform={`rotate(${-side * angle} ${sx} ${sy})`}>
    <rect x={sx - 21} y={sy - 10} width={42} height={92} rx={21} fill={FUR} stroke={INK} strokeWidth={7} />
    <ellipse cx={sx} cy={sy + 80} rx={25} ry={22} fill={FUR} stroke={INK} strokeWidth={7} />
    <path d={`M ${sx - 8} ${sy + 92} v -8 M ${sx + 8} ${sy + 92} v -8`} stroke={INK} strokeWidth={4} strokeLinecap="round" />
  </g>
);

const EyeShape: React.FC<{cx: number; cy: number; p: Pose; side: -1 | 1}> = ({cx, cy, p, side}) => {
  const [lx, ly] = p.look;
  const px = cx + lx * 7;
  const py = cy + ly * 6;
  switch (p.eyes) {
    case 'blink':
      return <path d={`M ${cx - 20} ${cy + 2} Q ${cx} ${cy + 14} ${cx + 20} ${cy + 2}`} stroke={INK} strokeWidth={7} fill="none" strokeLinecap="round" />;
    case 'happy':
      return <path d={`M ${cx - 22} ${cy + 8} Q ${cx} ${cy - 22} ${cx + 22} ${cy + 8}`} stroke={INK} strokeWidth={8} fill="none" strokeLinecap="round" />;
    case 'shock':
      return (
        <g>
          <ellipse cx={cx} cy={cy} rx={28} ry={32} fill="#fff" stroke={INK} strokeWidth={6} />
          <circle cx={px} cy={py} r={8} fill={INK} />
        </g>
      );
    case 'focus':
      return (
        <g>
          <ellipse cx={px} cy={py + 4} rx={17} ry={20} fill={INK} />
          <path d={`M ${cx - 26} ${cy - 14} L ${cx + 26} ${cy - 10 + side * 0}`} stroke={INK} strokeWidth={7} strokeLinecap="round" />
        </g>
      );
    default: {
      const lid = p.eyes === 'bored';
      return (
        <g>
          <ellipse cx={px} cy={py} rx={21} ry={27} fill={INK} />
          <circle cx={px - 7} cy={py - 10} r={8} fill="#fff" />
          <circle cx={px + 7} cy={py + 9} r={4} fill="#fff" />
          {lid ? (
            <>
              <rect x={cx - 30} y={cy - 36} width={60} height={34} fill={FUR} />
              <path d={`M ${cx - 27} ${cy - 2} L ${cx + 27} ${cy - 2}`} stroke={INK} strokeWidth={7} strokeLinecap="round" />
            </>
          ) : null}
        </g>
      );
    }
  }
};

const MouthShape: React.FC<{cx: number; cy: number; p: Pose}> = ({cx, cy, p}) => {
  if ((p.mouth === 'talk' && p.talk > 0.06) || p.mouth === 'grin') {
    const open = p.mouth === 'grin' ? 0.8 : p.talk;
    const h = 8 + 30 * open;
    return (
      <g>
        <path d={`M ${cx - 20} ${cy} Q ${cx} ${cy + h * 1.8} ${cx + 20} ${cy} Z`} fill="#8a2e3b" stroke={INK} strokeWidth={5} strokeLinejoin="round" />
        <ellipse cx={cx} cy={cy + h * 0.75} rx={10} ry={Math.max(2, h * 0.3)} fill="#ff8fa0" />
      </g>
    );
  }
  if (p.mouth === 'o') return <ellipse cx={cx} cy={cy + 14} rx={13} ry={18} fill="#8a2e3b" stroke={INK} strokeWidth={5} />;
  if (p.mouth === 'frown') return <path d={`M ${cx - 18} ${cy + 16} Q ${cx} ${cy} ${cx + 18} ${cy + 16}`} stroke={INK} strokeWidth={6} fill="none" strokeLinecap="round" />;
  if (p.mouth === 'flat') return <path d={`M ${cx - 14} ${cy + 8} L ${cx + 14} ${cy + 8}`} stroke={INK} strokeWidth={6} strokeLinecap="round" />;
  return (
    <path d={`M ${cx - 22} ${cy} Q ${cx - 11} ${cy + 14} ${cx} ${cy} Q ${cx + 11} ${cy + 14} ${cx + 22} ${cy}`} stroke={INK} strokeWidth={6}
      fill="none" strokeLinecap="round" strokeLinejoin="round" />
  );
};

/** 고양이. viewBox 원점은 발 가운데(0,0), 키 약 440 */
export const Cat: React.FC<{p: Pose}> = ({p}) => {
  const scale = p.size / 440;
  const sq = p.squash;
  return (
    <svg
      style={{position: 'absolute', left: p.x - 220 * scale, top: p.y - 460 * scale, overflow: 'visible'}}
      width={440 * scale}
      height={480 * scale}
      viewBox="-220 -460 440 480"
    >
      <ellipse cx={0} cy={4} rx={130} ry={16} fill="rgba(0,0,0,0.28)" />
      <g transform={`${p.flip ? 'scale(-1 1)' : ''} scale(${1 / Math.sqrt(sq)} ${sq}) rotate(${p.bodyTilt} 0 -20)`}>
        {/* 꼬리 */}
        <g transform={`rotate(${p.tail} 70 -50)`}>
          <path d="M 70 -50 C 150 -40 170 -120 140 -190" stroke={INK} strokeWidth={36} fill="none" strokeLinecap="round" />
          <path d="M 70 -50 C 150 -40 170 -120 140 -190" stroke={FUR_SHADE} strokeWidth={22} fill="none" strokeLinecap="round" />
          <path d="M 152 -128 l 18 -6 M 146 -165 l 18 -2" stroke={STRIPE} strokeWidth={8} strokeLinecap="round" />
        </g>
        {/* 발 */}
        <ellipse cx={-48} cy={-18} rx={42} ry={24} fill={FUR} stroke={INK} strokeWidth={7} />
        <ellipse cx={48} cy={-18} rx={42} ry={24} fill={FUR} stroke={INK} strokeWidth={7} />
        {/* 몸 */}
        <path d="M -95 -40 C -110 -150 -70 -205 0 -205 C 70 -205 110 -150 95 -40 C 85 -10 -85 -10 -95 -40 Z" fill={FUR} stroke={INK} strokeWidth={7} />
        <ellipse cx={0} cy={-90} rx={56} ry={62} fill={BELLY} />
        <Arm sx={-72} sy={-170} angle={p.armL} side={-1} />
        <Arm sx={72} sy={-170} angle={p.armR} side={1} />
        {/* 머리 */}
        <g transform={`rotate(${p.headTilt} 0 -200)`}>
          <path d="M -130 -330 L -118 -440 L -40 -385 Z" fill={FUR} stroke={INK} strokeWidth={7} strokeLinejoin="round" />
          <path d="M -115 -350 L -108 -415 L -62 -382 Z" fill={PINK} />
          <path d="M 130 -330 L 118 -440 L 40 -385 Z" fill={FUR} stroke={INK} strokeWidth={7} strokeLinejoin="round" />
          <path d="M 115 -350 L 108 -415 L 62 -382 Z" fill={PINK} />
          <ellipse cx={0} cy={-300} rx={150} ry={118} fill={FUR} stroke={INK} strokeWidth={7} />
          <path d="M -26 -414 l 4 30 M 0 -418 l 0 34 M 26 -414 l -4 30" stroke={STRIPE} strokeWidth={10} strokeLinecap="round" />
          {p.blush > 0 ? (
            <>
              <ellipse cx={-92} cy={-262} rx={26} ry={13} fill="#ff9aa6" opacity={0.55 * p.blush} />
              <ellipse cx={92} cy={-262} rx={26} ry={13} fill="#ff9aa6" opacity={0.55 * p.blush} />
            </>
          ) : null}
          <EyeShape cx={-55} cy={-305} p={p} side={-1} />
          <EyeShape cx={55} cy={-305} p={p} side={1} />
          {p.brows === 'worried' ? (
            <path d="M -80 -352 L -36 -364 M 80 -352 L 36 -364" stroke={INK} strokeWidth={7} strokeLinecap="round" />
          ) : p.brows === 'angry' ? (
            <path d="M -80 -366 L -36 -350 M 80 -366 L 36 -350" stroke={INK} strokeWidth={7} strokeLinecap="round" />
          ) : p.brows === 'up' ? (
            <path d="M -78 -362 Q -56 -378 -34 -362 M 78 -362 Q 56 -378 34 -362" stroke={INK} strokeWidth={6} fill="none" strokeLinecap="round" />
          ) : null}
          {p.tears > 0 ? (
            <>
              <path d={`M -55 ${-270 + p.tears * 60} q -10 14 0 22 q 10 -8 0 -22`} fill="#7dd3fc" />
              <path d={`M 55 ${-270 + ((p.tears + 0.5) % 1) * 60} q -10 14 0 22 q 10 -8 0 -22`} fill="#7dd3fc" />
            </>
          ) : null}
          <path d="M -12 -272 L 12 -272 L 0 -260 Z" fill="#e87a88" stroke={INK} strokeWidth={3} strokeLinejoin="round" />
          <MouthShape cx={0} cy={-256} p={p} />
          <path d="M -100 -278 L -160 -288 M -100 -262 L -160 -256 M 100 -278 L 160 -288 M 100 -262 L 160 -256" stroke={INK} strokeWidth={4}
            strokeLinecap="round" />
          {p.sweat > 0 ? (
            <path d="M 150 -380 q -16 26 0 34 q 16 -8 0 -34" fill="#7dd3fc" stroke={INK} strokeWidth={3} opacity={p.sweat} />
          ) : null}
        </g>
      </g>
    </svg>
  );
};
