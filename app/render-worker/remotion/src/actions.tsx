// 장면의 상황을 고양이가 연기한다. 무대 크기는 STAGE_W×STAGE_H, 시각은 장면 안 프레임.
import React from 'react';
import {Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {basePose, type Pose} from './Cat';
import {Emoji, Mascot} from './Emoji';
import {FONT} from './fonts';
import {Badge, C, CodeLine, Database, Mark, Paper, Pill, Server, Shield, Star} from './props';
import {theme} from './theme';
import type {SceneData} from './types';

export const STAGE_W = 1080;
export const STAGE_H = 720;
const FEET = STAGE_H - 30;

const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

/** 공통 움직임: 숨쉬기, 깜빡임, 목소리에 맞춘 입·몸 들썩임, 구절마다 콩 뛰기, 장면마다 옆에서 뛰어 들어오기 */
/** 문장(구절)마다 바꾸는 손짓 [왼팔, 오른팔] 각도. 연기가 팔을 정하지 않은 장면에서만 쓴다(참고 쇼츠: 문장마다 손짓을 바꾼다) */
const GESTURES: [number, number][] = [[18, 18], [18, 100], [95, 22], [60, 60], [20, 125], [110, 110]];

/** 훅·마무리 강조: 첫 장면 첫 구절(훅)과 마지막 장면 마지막 구절(행동 요청)에서 캐릭터를 크게 당기고 앞으로 기울인다 */
export type Emphasis = {hook: boolean; cta: boolean};

const useLife = (scene: SceneData, enterFrom = -1, beats: number[] = [], emphasis: Emphasis = {hook: false, cta: false}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const t = frame / fps;
  const talkIndex = frame - scene.leadFrames;
  const talk = talkIndex >= 0 && talkIndex < scene.mouth.length ? scene.mouth[talkIndex] : 0;
  const blink = (t + 0.7) % 3.2 < 0.11;
  // 콩 뛰기: 구절이 바뀔 때와 beats(도식 칸이 나타날 때 등)마다 작게 뛰고 내려앉을 때 살짝 눌린다
  const hopAt = [...scene.pages.map((p) => scene.leadFrames + (p.startMs / 1000) * fps), ...beats.map((b) => b * fps)]
    .filter((f) => f <= frame).sort((a, b) => b - a)[0];
  const since = hopAt === undefined ? 99 : (frame - hopAt) / fps;
  const hop = since < 0.3 ? Math.sin((since / 0.3) * Math.PI) * 26 : 0;
  const land = since >= 0.3 && since < 0.45 ? Math.sin(((since - 0.3) / 0.15) * Math.PI) : 0;
  // 뛰어 들어오기: 0→1. enterFrom(-1 왼쪽, 1 오른쪽, 0 없음) 쪽 무대 밖에서 포물선을 그리며 들어온다
  const enter = enterFrom === 0 ? 1 : spring({frame, fps, config: {damping: 15, stiffness: 110}});
  // 지금 몇 번째 구절인지(목소리 전이면 -1)
  const ms = ((frame - scene.leadFrames) / fps) * 1000;
  const pageIdx = scene.pages.reduce((k, pg, i) => (ms >= pg.startMs ? i : k), -1);
  const pages = scene.pages.length;
  const lastStart = pages ? scene.leadFrames + (scene.pages[pages - 1].startMs / 1000) * fps : Infinity;
  // 강조 정도 0→1: 훅은 처음부터 첫 구절이 끝날 때까지, 마무리는 마지막 구절이 시작될 때부터
  const hookEnd = pages > 1 ? scene.leadFrames + (scene.pages[1].startMs / 1000) * fps : scene.leadFrames + 1.2 * fps;
  const zoomIn = emphasis.hook ? 1 - spring({frame: frame - hookEnd, fps, config: {damping: 18, stiffness: 120}}) :
    emphasis.cta ? spring({frame: frame - lastStart, fps, config: {damping: 16, stiffness: 120}}) : 0;
  const live = (over: Partial<Pose>): Pose => {
    const gesture = GESTURES[Math.max(0, pageIdx) % GESTURES.length];
    const p = basePose({y: FEET, ...(over.armL === undefined && over.armR === undefined ? {armL: gesture[0], armR: gesture[1]} : {}), ...over});
    if (zoomIn > 0.001) {
      // 무대 가운데로 와서 1.6배까지 커지고 앞으로 기운다(화면 가득)
      p.x += (540 - p.x) * zoomIn;
      p.y += (STAGE_H + 40 - p.y) * zoomIn;
      p.size *= 1 + 0.6 * zoomIn;
      p.bodyTilt += 7 * zoomIn;
      p.headTilt += 5 * zoomIn;
    }
    p.x += (1 - enter) * enterFrom * 520;
    p.y -= Math.sin(Math.min(1, enter) * Math.PI) * 90 * (enterFrom === 0 ? 0 : 1) + hop + talk * 10;
    p.squash *= (1 + 0.025 * Math.sin(t * 2.8)) * (1 - 0.08 * land) * (1 + 0.04 * talk);
    p.bodyTilt += Math.sin(t * 2.1) * 4 + (1 - enter) * enterFrom * -18;
    p.tail += Math.sin(t * 2.4) * 14;
    if (p.eyes === 'open' && blink) p.eyes = 'blink';
    if ((p.mouth === 'smile' || p.mouth === 'talk') && talk > 0.06) {
      p.mouth = 'talk';
      p.talk = talk;
      p.headTilt += Math.sin(t * 9) * 3 * talk; // 말할 때 고개가 까딱인다
    }
    return p;
  };
  /** start초부터 튀어나오는 정도 0→1(살짝 넘쳤다 돌아옴) */
  const pop = (start: number, damping = 11) => spring({frame: frame - start * fps, fps, config: {damping, stiffness: 160}});
  return {frame, fps, t, talk, live, pop};
};

export const Act: React.FC<{scene: SceneData; first: boolean; last?: boolean}> = ({scene, first, last = false}) => {
  // 첫 장면은 고양이가 통 튀어나오며 시작(훅), 다음 장면부터는 옆에서 뛰어 들어온다
  const life = useLife(scene, first ? 0 : -1, [], {hook: first, cta: last && !first});
  const enter = first ? life.pop(0, 9) : 1;
  const Body = ACTS[scene.action] ?? Explain;
  return (
    <div style={{position: 'absolute', inset: 0, scale: String(0.55 + 0.45 * enter), transformOrigin: '50% 95%'}}>
      <Body scene={scene} life={life} />
    </div>
  );
};

/** 도식 장면의 발표자: 왼쪽 아래에서 도식을 손가락으로 가리키며 설명하고, 칸이 나타날 때마다 콩 뛴다 */
export const Presenter: React.FC<{scene: SceneData; beats: number[]; done: boolean}> = ({scene, beats, done}) => {
  const life = useLife(scene, -1, beats);
  const {t, live, talk} = life;
  // 오른팔로 도식을 가리키고(말할 때 더 크게 흔든다), 다 나오면 두 팔을 들어 기뻐한다
  const reach = Math.sin(t * 3.2) * 8 + talk * 10;
  const p = live({x: 150, y: STAGE_H - 10, size: 300, look: [1, -0.6], bodyTilt: done ? 0 : 6,
    armR: done ? 115 : 105 + reach, armL: done ? 115 : 18 + Math.sin(t * 2) * 6});
  return <Mascot face={done ? 'joy' : talk > 0.06 ? 'grin' : 'smile'} p={p} clip={done ? 'happy' : 'point'} />;
};

type Life = ReturnType<typeof useLife>;
type ActProps = {scene: SceneData; life: Life};

const Explain: React.FC<ActProps> = ({life}) => {
  const {t, live} = life;
  const wave = t < 1.6 ? Math.sin(t * 9) * 18 : 0;
  return <Mascot face="grin" p={live({x: 540, bodyTilt: Math.sin(t * 1.5) * 3 + wave * 0.2, y: FEET - Math.abs(Math.sin(t * 3)) * 12})} />;
};

const Shock: React.FC<ActProps> = ({scene, life}) => {
  const {t, live, pop, fps, frame} = life;
  const hit = 0.9;
  const shocked = t > hit;
  const jump = shocked ? interpolate(t, [hit, hit + 0.18, hit + 0.42], [0, 70, 0], clamp) : 0;
  const papers = [0, 1, 2].map((k) => {
    const start = 1.0 + k * 0.16;
    const f = interpolate(t, [start, start + 0.5], [0, 1], {...clamp, easing: Easing.in(Easing.cubic)});
    const shake = t > 0.55 && t < start ? Math.sin(frame * 2.1 + k) * 7 : 0;
    const x0 = 650 + k * 140;
    const y0 = 200 + (k % 2) * 60;
    return (
      <Paper key={k} x={interpolate(f, [0, 1], [x0, 880]) + shake} y={interpolate(f, [0, 1], [y0, 470]) - Math.sin(f * Math.PI) * 120}
        rotate={f * 260 + shake} scale={1 - 0.6 * f} opacity={f >= 1 ? 0 : 1} label={k === 1 && f === 0 ? scene.params.label : undefined} />
    );
  });
  const lid = interpolate(t, [hit, hit + 0.15, 2.0, 2.2], [0, 1, 1, 0], clamp);
  const crying = t > 2.0;
  return (
    <>
      <Emoji name="trash" x={880} y={540} size={280} scale={1 + 0.12 * lid} rotate={-6 * lid} />
      {papers}
      <Mascot face={crying ? 'cry' : shocked ? 'shock' : 'smile'} p={live({
        x: 330, y: FEET - jump, eyes: crying ? 'sad' : shocked ? 'shock' : 'open',
        mouth: shocked && !crying ? 'o' : crying ? 'frown' : 'smile', brows: shocked ? 'worried' : '',
        armL: shocked ? 165 : 12, armR: shocked ? 165 : 12, sweat: shocked ? 1 : 0, tears: crying ? (t * 1.2) % 1 : 0,
        look: [1, 0], squash: shocked ? interpolate(t, [hit, hit + 0.1, hit + 0.3], [0.85, 1.12, 1], clamp) : 1,
      })} />
      {shocked ? <Emoji name="boom" x={600} y={140} size={230} scale={pop(hit, 8)} rotate={12} /> : null}
      {void fps}
    </>
  );
};

const Typing: React.FC<ActProps> = ({scene, life}) => {
  const {t, live, pop, fps, frame} = life;
  const {params, code} = scene;
  const lead = scene.leadFrames / fps;
  // 말이 시작될 무렵 코드가 거의 다 써져 있어야 읽을 시간이 남는다
  const typeStart = Math.min(0.2, lead);
  const typeEnd = lead + Math.max(0.8, (scene.speechFrames / fps) * 0.3);
  const typed = interpolate(t, [typeStart, typeEnd], [0, 1], clamp);
  const total = code.reduce((a, l) => a + l.length, 0) || 1;
  let budget = typed * total;
  const done = t > typeEnd + 0.15;
  const pulse = done && params.focus !== null ? 0.45 + 0.35 * Math.cos((t - typeEnd) * 7) : 0;
  const size = code.some((l) => l.length > 30) ? 34 : 40;
  const tap = Math.sin(frame * 1.3);
  return (
    <>
      <div style={{position: 'absolute', left: 60, top: 10, width: 960, height: 430, background: C.slateD, borderRadius: 28,
        boxShadow: theme.shadow.card, overflow: 'hidden'}}>
        <div style={{display: 'flex', gap: 12, padding: '18px 24px', background: '#1e293b', alignItems: 'center'}}>
          {[C.red, C.yellow, C.green].map((c) => <div key={c} style={{width: 22, height: 22, borderRadius: 11, background: c}} />)}
          <div style={{marginLeft: 'auto', font: `600 24px ${FONT}`, color: C.muted}}>{params.shell ? '터미널' : '코드'}</div>
        </div>
        <div style={{padding: '22px 30px', display: 'flex', flexDirection: 'column', gap: size * 0.45}}>
          {code.map((line, n) => {
            const shown = line.slice(0, Math.max(0, Math.floor(budget)));
            budget -= line.length;
            const focus = params.focus === n && pulse > 0;
            const prompt = params.shell && line.trim() && !/^\s*(#|\/\/|--)/.test(line);
            return (
              <div key={n} style={{minHeight: size * 1.2, borderRadius: 10, padding: '0 10px', margin: '0 -10px',
                background: focus ? (params.good ? `rgba(34,197,94,${pulse * 0.5})` : `rgba(239,68,68,${pulse * 0.5})`) : 'transparent'}}>
                {prompt ? <span style={{color: C.green, font: `700 ${size}px monospace`}}>$ </span> : null}
                <CodeLine line={shown} size={size} />
                {shown.length < line.length && shown.length > 0 ? <span style={{background: C.code, display: 'inline-block', width: size * 0.55, height: size}} /> : null}
              </div>
            );
          })}
        </div>
      </div>
      <Mascot face={done && params.good ? 'joy' : done && params.focus !== null ? 'shock' : 'wry'} p={live({x: 230, size: 400, armL: typed < 1 ? 60 + tap * 12 : 12, armR: typed < 1 ? 60 - tap * 12 : done && params.good ? 160 : 12,
        eyes: done && params.good ? 'happy' : typed < 1 ? 'focus' : 'open', look: [0.6, -0.8],
        sweat: done && !params.good && params.focus !== null ? 1 : 0, brows: done && !params.good && params.focus !== null ? 'worried' : ''})} />
      {done && params.focus !== null ? (
        params.good ? <Badge x={880} y={560} good scale={pop(typeEnd + 0.15, 9)} /> :
          <Emoji name="thinking" x={470} y={470} size={200} scale={pop(typeEnd + 0.15, 9)} rotate={-10} />
      ) : null}
    </>
  );
};

const Panic: React.FC<ActProps> = ({scene, life}) => {
  const {t, live, frame, fps, pop} = life;
  const target = scene.params.count ?? 100;
  const alert = interpolate(t, [0.9, 1.3], [0, 1], clamp);
  const n = Math.round(target * interpolate(t, [0.3, 0.3 + Math.max(0.6, (scene.speechFrames / fps) * 0.7)], [0, 1], {...clamp, easing: Easing.out(Easing.cubic)}));
  const pills = Array.from({length: 12}).map((_, k) => {
    const phase = (t * 1.5 + k / 12) % 1;
    if (t * 1.5 + k / 12 < 1) return null;
    const y = 80 + ((k * 53) % 520);
    return <Pill key={k} x={interpolate(phase, [0, 1], [40, 760])} y={y} text="GET" bg={C.blue} size={28} opacity={phase > 0.92 ? 0 : 1} />;
  });
  const shake = Math.sin(frame * 2.3) * 7 * alert;
  return (
    <>
      <Server x={880} y={400} alert={alert} rotate={Math.sin(frame * 2.7) * 3 * alert} />
      {pills}
      <Pill x={880} y={190} text={`${n.toLocaleString()}번`} bg={alert > 0.5 ? C.red : '#fff'} color={alert > 0.5 ? '#fff' : '#0f172a'} size={60} />
      {alert > 0.5 ? <Emoji name="siren" x={600} y={110} size={190} scale={pop(0.9, 8)} /> : null}
      {alert > 0.5 ? <Emoji name="fire" x={880} y={620} size={170} /> : null}
      <Mascot face={alert > 0.5 ? 'shock' : 'smile'} clip={alert > 0.5 ? 'panic' : 'idle'} p={live({x: 340 + shake, eyes: alert > 0.5 ? 'shock' : 'open', mouth: alert > 0.5 && life.talk < 0.06 ? 'o' : 'smile',
        armL: alert > 0.5 ? 168 : 12, armR: alert > 0.5 ? 168 : 12, sweat: alert, brows: alert > 0.5 ? 'worried' : '', look: [1, 0]})} />
    </>
  );
};

const Restore: React.FC<ActProps> = ({scene, life}) => {
  const {t, live, pop, fps} = life;
  const fillEnd = 0.5 + Math.max(1.0, (scene.speechFrames / fps) * 0.6);
  const fill = interpolate(t, [0.5, fillEnd], [0, 1], {...clamp, easing: Easing.inOut(Easing.quad)});
  const done = fill >= 1;
  const flying = done ? null : [0, 1, 2, 3].map((k) => {
    const ph = (t * 1.4 + k / 4) % 1;
    return <Paper key={k} x={interpolate(ph, [0, 1], [560, 840])} y={interpolate(ph, [0, 1], [520, 380]) - Math.sin(ph * Math.PI) * 140}
      scale={0.45} rotate={ph * 180} />;
  });
  return (
    <>
      <div style={{position: 'absolute', left: 470, top: 470, width: 190, height: 150, background: '#c08a52', boxShadow: theme.shadow.soft,
        borderRadius: 18, display: 'grid', placeItems: 'center', font: `800 46px ${FONT}`, color: '#fff'}}>백업</div>
      <Database x={860} y={420} fill={fill} color={done ? C.green : C.blue} />
      {flying}
      {done ? <Emoji name="check" x={860} y={150} size={190} scale={pop(fillEnd, 9)} /> : null}
      {done ? <Emoji name="sparkles" x={640} y={250} size={170} scale={pop(fillEnd + 0.1, 9)} /> : null}
      <Mascot face={done ? 'joy' : 'smile'} p={live({x: 250, eyes: done ? 'happy' : 'open', mouth: done ? 'grin' : 'smile', armL: done ? 165 : 20,
        armR: done ? 165 : 70 + Math.sin(t * 8) * 25, y: FEET - (done ? Math.abs(Math.sin(t * 8)) * 34 : 0), look: [1, 0]})} />
    </>
  );
};

const Wait: React.FC<ActProps> = ({scene, life}) => {
  const {t, live, fps} = life;
  const total = parseDuration(scene.params.before) ?? 4;
  const shown = total * interpolate(t, [0.2, 0.2 + Math.max(1, (scene.speechFrames / fps) * 0.8)], [0, 1], clamp);
  const sleepy = t > 1.2;
  return (
    <>
      <div style={{position: 'absolute', left: 470, top: 40, width: 560, height: 430, background: C.slateD, boxShadow: theme.shadow.card,
        borderRadius: 28, padding: 36, display: 'flex', flexDirection: 'column', gap: 26}}>
        {[1, 0.8, 0.6].map((w, k) => <div key={k} style={{height: 46, width: `${w * 100}%`, background: '#1e293b', borderRadius: 14,
          opacity: 0.6 + 0.4 * Math.sin(t * 4 + k)}} />)}
      </div>
      <Emoji name="hourglass" x={750} y={330} size={190} />
      <Pill x={750} y={40} text={`${shown.toFixed(1)}초`} bg={C.red} color="#fff" size={58} />
      <Mascot face={sleepy ? 'pout' : 'smile'} p={live({x: 260, eyes: sleepy ? 'bored' : 'open', mouth: sleepy && life.talk < 0.06 ? 'flat' : 'smile', headTilt: sleepy ? -10 : 0,
        armL: sleepy ? 130 : 12, look: [1, -0.3]})} />
      {sleepy ? [0, 1, 2].map((k) => {
        const ph = ((t - 1.2) * 0.7 + k / 3) % 1;
        return <Mark key={k} x={360 + ph * 90} y={190 - ph * 150} text="z" color={theme.color.muted} size={40 + 34 * ph} opacity={1 - ph} />;
      }) : null}
    </>
  );
};

const Fast: React.FC<ActProps> = ({scene, life}) => {
  const {t, live, pop} = life;
  const {before, after, label} = scene.params;
  const b = parseDuration(before);
  const a = parseDuration(after);
  const ratio = b && a && b > a ? a / b : 0.08;
  const slow = interpolate(t, [0.3, 1.9], [0, 1], clamp);
  const quick = interpolate(t, [0.6, 0.85], [0, 1], clamp) * Math.max(0.05, ratio);
  const cheer = t > 1.0;
  const bar = (y: number, frac: number, color: string, name: string, value: string | null) => (
    <div style={{position: 'absolute', left: 60, top: y, width: 960, display: 'flex', alignItems: 'center', gap: 22}}>
      <div style={{font: `800 46px ${FONT}`, color: theme.color.ink, width: 60}}>{name}</div>
      <div style={{flex: 1, height: 78, background: theme.color.surfaceAlt, borderRadius: 40, overflow: 'hidden'}}>
        <div style={{width: `${Math.max(7, frac * 100)}%`, height: '100%', background: color, borderRadius: 40}} />
      </div>
      <div style={{font: `900 52px ${FONT}`, color, width: 190}}>{value}</div>
    </div>
  );
  return (
    <>
      {bar(20, slow, C.red, '전', before)}
      {bar(130, quick, C.green, '후', after)}
      {cheer ? <Pill x={780} y={360} text={label} bg={C.yellow} size={66} scale={pop(1.0, 8)} /> : null}
      {cheer ? [0, 1, 2, 3, 4].map((k) => {
        const ang = k * 1.3 + t * 2;
        return <Star key={k} x={540 + 380 * Math.cos(ang)} y={470 + 150 * Math.sin(ang)} scale={0.8 + 0.3 * Math.sin(t * 6 + k)} />;
      }) : null}
      {cheer ? <Emoji name="rocket" x={interpolate(t, [1.0, 2.2], [620, 1000], clamp)} y={interpolate(t, [1.0, 2.2], [620, 300], clamp)} size={210} /> : null}
      <Mascot face={cheer ? 'love' : 'smile'} p={live({x: 300, size: 430, eyes: cheer ? 'happy' : 'open', mouth: cheer ? 'grin' : 'smile', armL: cheer ? 160 : 12, armR: cheer ? 160 : 12,
        y: FEET - (cheer ? Math.abs(Math.sin(t * 7)) * 46 : 0)})} />
    </>
  );
};

const Idea: React.FC<ActProps> = ({life}) => {
  const {t, live, pop} = life;
  const on = t > 0.5;
  return (
    <>
      {on ? <Emoji name="bulb" x={770} y={180} size={300} scale={pop(0.5, 8)} /> : null}
      <Mascot face={t > 0.7 ? 'grin' : 'smile'} clip={on ? 'idea' : 'idle'} p={live({x: 450, eyes: t > 0.7 ? 'happy' : 'open', brows: on ? 'up' : '', armR: on ? 150 : 12, look: [0.6, -1],
        y: FEET - interpolate(t, [0.5, 0.65, 0.85], [0, 40, 0], clamp)})} />
    </>
  );
};

const Protect: React.FC<ActProps> = ({life}) => {
  const {t, live, pop} = life;
  const on = t > 0.4;
  return (
    <>
      {on ? <Shield x={760} y={360} scale={pop(0.4, 9)} /> : null}
      {on ? [0, 1, 2, 3].map((k) => {
        const ang = k * 1.57 + t * 1.6;
        return <Star key={k} x={760 + 210 * Math.cos(ang)} y={360 + 190 * Math.sin(ang)} />;
      }) : null}
      <Mascot face={t > 0.6 ? 'wry' : 'smile'} clip={on ? 'point' : 'idle'} p={live({x: 330, eyes: t > 0.6 ? 'happy' : 'open', brows: t > 0.6 ? 'angry' : '', armR: on ? 120 : 12, mouth: t > 0.6 ? 'grin' : 'smile'})} />
    </>
  );
};

const Think: React.FC<ActProps> = ({life}) => {
  const {t, live, pop} = life;
  return (
    <>
      <Mascot face="kiss" p={live({x: 420, armR: 150, headTilt: 8 * Math.sin(t * 1.3), look: [0.8, -0.8], eyes: 'open', mouth: life.talk > 0.06 ? 'talk' : 'flat'})} />
      <Emoji name="thinking" x={800} y={170} size={240} scale={pop(0.3, 8)} />
      {[[960, 80, 90], [660, 70, 80]].map(([x, y, s], k) => (
        <Mark key={k} x={x} y={y + Math.sin(t * 3 + k) * 10} text="?" color={C.yellow} size={s} scale={pop(0.55 + k * 0.25, 8)} rotate={-10 + k * 20} />
      ))}
    </>
  );
};

const Celebrate: React.FC<ActProps> = ({life}) => {
  const {t, live} = life;
  return (
    <>
      {Array.from({length: 14}).map((_, k) => {
        const ph = (t * 0.6 + k / 14) % 1;
        return <Star key={k} x={80 + ((k * 0.37) % 1) * 920} y={-20 + ph * 700} color={[C.yellow, C.pink, C.blue, C.green][k % 4]} rotate={ph * 360} />;
      })}
      <Emoji name="party" x={170} y={150} size={220} rotate={-15} />
      <Emoji name="party" x={910} y={150} size={220} rotate={15} />
      <Mascot face="joy" p={live({x: 540, eyes: 'happy', mouth: 'grin', armL: 160, armR: 160, y: FEET - Math.abs(Math.sin(t * 7)) * 56})} />
    </>
  );
};

const ACTS: Record<string, React.FC<ActProps>> = {
  explain: Explain, shock: Shock, typing: Typing, panic: Panic, restore: Restore, wait: Wait,
  fast: Fast, idea: Idea, protect: Protect, think: Think, celebrate: Celebrate,
};

function parseDuration(v: string | null): number | null {
  if (!v) return null;
  const m = v.match(/([\d.,]+)\s*(ms|초|분|시간)/);
  if (!m) return null;
  const n = parseFloat(m[1].replace(',', '.'));
  return m[2] === 'ms' ? n / 1000 : n * ({초: 1, 분: 60, 시간: 3600} as Record<string, number>)[m[2]];
}
