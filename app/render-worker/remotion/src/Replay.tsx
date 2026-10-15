// 매매 재현 장면 (#265): 거래소 차트를 줌아웃한 채 녹화해 빨리감기한 것처럼, 고정된 화면의 오른쪽 끝에서 새 캔들이 자라며 차트가 왼쪽으로 흘러간다.
// 워커(devlog_render/replay.py의 chart_data)가 차트 전체 데이터와 이 장면이 영상에서 시작하는 프레임(startFrame)을 넘긴다.
// head[영상 프레임]은 그 순간의 차트 위치(캔들 단위 실수)다. i번 캔들은 head가 i일 때 생기기 시작해 i+1일 때 다 그려진다.
// 영상 전체 프레임으로 그리므로 장면이 바뀌어도 차트는 끊기지 않는다. 지금 캔들은 화면 가운데에 있고, 카메라는 지금 가격을 부드럽게 따라간다.
// 영상 첫 titleUntil 프레임은 검은 연기 바탕의 제목 카드가 덮는다.
// 체결 순간에만 아래 가운데에 작은 자막(진입 흰색, 익절 초록, 손절 빨강)과 오른쪽 위 체결 알림이 뜬다. 끝은 화면을 어둡게 눌러 결산을 띄운다.
import React from 'react';
import {AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {STAGE_W} from './actions';
import type {CustomSceneProps} from './custom';
import {FONT} from './fonts';
import {card, theme} from './theme';

// 색·글꼴은 쇼츠 공통 테마(theme.ts)를 따른다: 흰 카드 위에 진한 글자, 오르내림만 초록·빨강
const UP = theme.color.good;
const DOWN = theme.color.bad;
const MUTED = theme.color.sub;
const GRID = theme.color.line;
const SOFT = theme.color.ink;
// 어두운 바탕(자막 상자·제목 카드·결산) 위의 글자색
const NIGHT = '#0b0d12';
const TONES = {entry: '#ffffff', win: '#4ade80', loss: '#f87171'} as const;

export type ReplayCandle = {o: number; h: number; l: number; c: number; time: string};
export type ReplayFill = {
  index: number;
  at: number;
  side: 'BUY' | 'SELL';
  price: number;
  closeIndex: number | null;
  text: string;
  detail: string | null;
  tone: keyof typeof TONES;
};
export type ReplayData = {
  startFrame: number;
  header: string;
  mode: string;
  toast: string;
  digits: number;
  unit: string;
  visible: number;
  span: number;
  center: number[];
  minutes: number;
  subtitleFrames: number;
  titleUntil: number;
  title: ReplayTitleData;
  head: number[];
  candles: ReplayCandle[];
  fills: ReplayFill[];
  summary: {at: number; count: string; label: string; good: boolean; period: string} | null;
};
export type ReplayTitleData = {label: string; title: string; line: string; date: string};

// 차트 카드는 요점 자막 자리(무대 위 390px)까지 넓혀 화면 가운데를 크게 쓴다. 아래 구절 자막 자리는 비운다
const CARD = {x: 40, y: -390, w: STAGE_W - 80, h: 1100};
const PLOT = {x0: CARD.x + 24, x1: CARD.x + CARD.w - 170, y0: CARD.y + 170, y1: CARD.y + CARD.h - 190};
const TOAST = 75;

const headAt = (d: ReplayData, g: number) => d.head[Math.max(0, Math.min(d.head.length - 1, g))] ?? 0;

/** 그리는 중인 캔들: 진행률 f(0~1)에서의 지금 가격과 지금까지의 고가·저가 */
const forming = (c: ReplayCandle, f: number, seed: number) => {
  const pts = c.c >= c.o ? [c.o, c.l, c.h, c.c] : [c.o, c.h, c.l, c.c];
  const at = [0, 0.3, 0.7, 1];
  let k = 0;
  while (k < 2 && f > at[k + 1]) k++;
  const u = (f - at[k]) / (at[k + 1] - at[k]);
  let price = pts[k] + (pts[k + 1] - pts[k]) * Math.min(1, Math.max(0, u));
  // 실시간 체결처럼 잘게 떨린다. 다 그려질 때는 종가에 정확히 닿는다
  price += (c.h - c.l) * 0.03 * Math.sin(f * 23 + seed * 1.7) * (1 - f);
  price = Math.min(c.h, Math.max(c.l, price));
  const seen = [...pts.slice(0, k + 1), price];
  return {o: c.o, c: price, h: Math.max(...seen), l: Math.min(...seen)};
};

const fmt = (v: number, digits: number, unit: string) =>
  v.toLocaleString('en-US', {minimumFractionDigits: digits, maximumFractionDigits: digits}) + unit;

/** 눈금 간격: 1·2·5 단위로 4~6칸 */
const niceTicks = (lo: number, hi: number) => {
  const raw = (hi - lo) / 5;
  const mag = Math.pow(10, Math.floor(Math.log10(raw || 1)));
  const step = [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? raw;
  const out: number[] = [];
  for (let p = Math.ceil(lo / step) * step; p <= hi; p += step) out.push(p);
  return out;
};

export const ReplayScene: React.FC<CustomSceneProps> = ({data}) => {
  const d = data as ReplayData;
  const local = useCurrentFrame();
  const {fps} = useVideoConfig();
  const g = local + d.startFrame; // 영상 전체 프레임
  const n = d.candles.length;
  const head = headAt(d, g);
  // 카메라: 캔들 폭과 세로 배율은 영상 내내 고정. 지금 캔들은 가로 가운데, 세로는 지금 가격을 부드럽게 따라간다
  const center = d.center[Math.max(0, Math.min(d.center.length - 1, g))] ?? 0;
  const lo = center - d.span / 2;
  const hi = center + d.span / 2;
  const offset = head - d.visible / 2;
  const slot = (PLOT.x1 - PLOT.x0) / d.visible;
  const cx = (i: number) => PLOT.x0 + (i + 0.5 - offset) * slot;
  const y = (p: number) => PLOT.y1 - ((p - lo) / (hi - lo || 1)) * (PLOT.y1 - PLOT.y0);

  const cur = Math.min(n, Math.floor(head));
  const candles: {i: number; o: number; h: number; l: number; c: number}[] = [];
  for (let i = Math.max(0, Math.floor(offset) - 1); i < cur; i++) candles.push({i, ...d.candles[i]});
  if (cur < n) candles.push({i: cur, ...forming(d.candles[cur], head - cur, cur)});
  const live = candles.length > 0 ? candles[candles.length - 1] : null;
  const liveUp = live ? live.c >= live.o : true;
  const clock = d.candles[Math.min(n - 1, cur)]?.time ?? '';

  const fills = d.fills.filter((f) => f.at <= g);
  const recent = fills.length > 0 ? fills[fills.length - 1] : null;
  const since = recent ? g - recent.at : Infinity;
  const ticks = niceTicks(lo, hi);
  // 시간 눈금: 화면에 다섯 개 안팎(1분봉이면 30분·1시간 간격)
  const every = [5, 15, 30, 60, 120, 240, 480, 1440].find((m) => (d.visible * d.minutes) / m <= 6) ?? 1440;
  const step = Math.max(1, Math.round(every / Math.max(1, d.minutes)));
  const times = candles.filter((c) => c.i % step === 0 && c.i < cur && cx(c.i) > PLOT.x0 + 30 && cx(c.i) < PLOT.x1 - 30);
  const summaryOn = d.summary && g >= d.summary.at;

  return (
    <AbsoluteFill>
      <div style={{position: 'absolute', left: CARD.x, top: CARD.y, width: CARD.w, height: CARD.h, ...card()}} />
      <div style={{position: 'absolute', left: CARD.x + 32, top: CARD.y + 32, font: theme.type.title, color: SOFT}}>{d.header}</div>
      <div style={{position: 'absolute', left: CARD.x + 32, top: CARD.y + 80, font: `700 26px ${FONT}`, color: MUTED}}>{d.mode}</div>
      {/* 시계: 지금 그리는 캔들의 한국 시간 */}
      <div style={{position: 'absolute', right: STAGE_W - CARD.x - CARD.w + 32, top: CARD.y + 26, textAlign: 'right',
        font: `800 60px ${FONT}`, color: SOFT, fontVariantNumeric: 'tabular-nums', letterSpacing: 1}}>{clock}</div>

      <svg width={STAGE_W} height={CARD.h} viewBox={`0 ${CARD.y} ${STAGE_W} ${CARD.h}`} style={{position: 'absolute', left: 0, top: CARD.y}}>
        <defs>
          <clipPath id="plot">
            <rect x={PLOT.x0} y={PLOT.y0 - 30} width={PLOT.x1 - PLOT.x0} height={PLOT.y1 - PLOT.y0 + 60} />
          </clipPath>
        </defs>
        {ticks.map((p, k) => (
          <g key={k}>
            <line x1={PLOT.x0} x2={PLOT.x1} y1={y(p)} y2={y(p)} stroke={GRID} strokeWidth={2} />
            <text x={PLOT.x1 + 14} y={y(p) + 8} fill={MUTED} style={{font: `600 22px ${FONT}`}}>{fmt(p, d.digits, d.unit)}</text>
          </g>
        ))}
        {times.map((c) => (
          <text key={`t${c.i}`} x={cx(c.i)} y={PLOT.y1 + 48} textAnchor="middle" fill={MUTED} style={{font: `600 22px ${FONT}`}}>{d.candles[c.i].time}</text>
        ))}
        <g clipPath="url(#plot)">
          {/* 매수 가격 선: 다음 매도까지(아직이면 지금 캔들까지) 이어 그린다 */}
          {fills.filter((f) => f.side === 'BUY').map((f, k) => {
            const closed = f.closeIndex !== null && fills.some((o) => o.side === 'SELL' && o.index === f.closeIndex);
            const end = closed ? cx(f.closeIndex as number) : cx(Math.max(f.index, cur));
            return <line key={`l${k}`} x1={cx(f.index)} x2={end} y1={y(f.price)} y2={y(f.price)} stroke={UP} strokeOpacity={0.7} strokeWidth={3} strokeDasharray="10 8" />;
          })}
          {candles.map((c) => {
            const color = c.c >= c.o ? UP : DOWN;
            const top = y(Math.max(c.o, c.c));
            const h = y(Math.min(c.o, c.c)) - top;
            return (
              <g key={c.i}>
                <rect x={cx(c.i) - 0.75} y={y(c.h)} width={1.5} height={Math.max(1.5, y(c.l) - y(c.h))} fill={color} />
                <rect x={cx(c.i) - Math.max(1, slot * 0.36)} y={top} width={Math.max(2, slot * 0.72)} height={Math.max(1.5, h)} fill={color} />
              </g>
            );
          })}
        </g>
        {/* 지금 가격: 그리는 중인 캔들의 값이 오른쪽 눈금에서 실시간으로 따라 움직인다 */}
        {live && head < n && (
          <g>
            <line x1={PLOT.x0} x2={PLOT.x1} y1={y(live.c)} y2={y(live.c)} stroke={liveUp ? UP : DOWN} strokeOpacity={0.5} strokeWidth={2} strokeDasharray="4 6" />
            <rect x={PLOT.x1 + 4} y={y(live.c) - 19} width={156} height={38} rx={8} fill={liveUp ? UP : DOWN} />
            <text x={PLOT.x1 + 12} y={y(live.c) + 8} fill="#fff" style={{font: `700 22px ${FONT}`}}>{fmt(live.c, d.digits, d.unit)}</text>
          </g>
        )}
        {fills.map((f, k) => {
          const x = cx(f.index);
          if (x < PLOT.x0 + 36) return null; // 왼쪽 끝으로 밀려 나가면 표시도 함께 사라진다
          const buy = f.side === 'BUY';
          const color = buy ? UP : DOWN;
          const pop = spring({frame: g - f.at, fps, config: {damping: 14, stiffness: 200}});
          const labelY = buy ? Math.min(y(f.price) + 64, PLOT.y1 + 6) : Math.max(y(f.price) - 64, PLOT.y0 - 6);
          return (
            <g key={`f${k}`}>
              <line x1={x} x2={x} y1={y(f.price)} y2={labelY} stroke={color} strokeWidth={2} />
              <circle cx={x} cy={y(f.price)} r={6 * pop} fill="#fff" stroke={color} strokeWidth={3} />
              <g style={{transformOrigin: `${x}px ${labelY}px`, transform: `scale(${pop})`}}>
                <rect x={x - 36} y={labelY - 17} width={72} height={34} rx={6} fill={color} />
                <text x={x} y={labelY + 8} textAnchor="middle" fill="#fff" style={{font: `700 22px ${FONT}`}}>{buy ? '매수' : '매도'}</text>
              </g>
            </g>
          );
        })}
      </svg>

      {/* 체결 알림: 오른쪽 위에 거래소 알림처럼 잠깐 */}
      {recent && since < TOAST && !summaryOn && (() => {
        const enter = spring({frame: since, fps, config: {damping: 18, stiffness: 180}});
        const leave = interpolate(since, [TOAST - 8, TOAST], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
        return (
          <div style={{position: 'absolute', right: STAGE_W - PLOT.x1 + 12, top: PLOT.y0 - 30, display: 'flex', alignItems: 'center', gap: 10,
            background: theme.color.surface, boxShadow: theme.shadow.soft, border: `1px solid ${GRID}`, borderRadius: theme.radius.small,
            padding: '10px 18px', font: `600 22px ${FONT}`, color: SOFT, opacity: Math.min(enter, leave), translate: `${(1 - enter) * 40}px 0`}}>
            <div style={{width: 22, height: 22, borderRadius: 11, background: UP, color: '#fff', font: `800 15px ${FONT}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center'}}>✓</div>
            {d.toast}
          </div>
        );
      })()}
      {/* 체결 자막: 아래 가운데 작게. 진입 흰색 한 줄, 익절 초록·손절 빨강 두 줄 */}
      {recent && since < d.subtitleFrames && !summaryOn && (() => {
        const enter = interpolate(since, [0, 5], [0, 1], {extrapolateRight: 'clamp'});
        const leave = interpolate(since, [d.subtitleFrames - 6, d.subtitleFrames], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
        return (
          <div style={{position: 'absolute', left: CARD.x, width: CARD.w, top: CARD.y + CARD.h - 112, display: 'flex', justifyContent: 'center',
            opacity: Math.min(enter, leave)}}>
            <div style={{background: 'rgba(11,13,18,0.86)', borderRadius: 12, padding: '10px 26px', textAlign: 'center', color: TONES[recent.tone],
              font: `600 30px/1.35 ${FONT}`, whiteSpace: 'nowrap'}}>
              <div>{recent.text}</div>
              {recent.detail && <div style={{font: `600 24px/1.35 ${FONT}`}}>{recent.detail}</div>}
            </div>
          </div>
        );
      })()}
      {/* 결산: 차트가 멈추고 화면이 어둡게 눌린 뒤 가운데에 손익 합계 */}
      {summaryOn && d.summary && (() => {
        const t = g - d.summary.at;
        const dim = interpolate(t, [0, 0.6 * fps], [0, 1], {extrapolateRight: 'clamp'});
        const s = spring({frame: t - 0.25 * fps, fps, config: theme.motion.enter});
        return (
          <>
            <div style={{position: 'absolute', left: CARD.x, top: CARD.y, width: CARD.w, height: CARD.h, borderRadius: theme.radius.card,
              background: NIGHT, opacity: 0.86 * dim}} />
            <div style={{position: 'absolute', left: CARD.x, top: CARD.y, width: CARD.w, height: CARD.h, display: 'flex', flexDirection: 'column',
              alignItems: 'center', justifyContent: 'center', gap: 18, opacity: Math.min(1, s), translate: `0 ${(1 - s) * 24}px`}}>
              <div style={{font: `700 40px ${FONT}`, color: 'rgba(255,255,255,0.75)'}}>{d.summary.count}</div>
              <div style={{font: `900 120px ${FONT}`, color: d.summary.good ? TONES.win : TONES.loss, fontVariantNumeric: 'tabular-nums'}}>{d.summary.label}</div>
              <div style={{font: `600 32px ${FONT}`, color: 'rgba(255,255,255,0.6)'}}>{d.mode} · {d.summary.period}</div>
            </div>
          </>
        );
      })()}
      {/* 영상 첫머리: 제목 카드가 덮었다가 걷히며 흐르는 차트가 드러난다 */}
      {g < d.titleUntil && (
        <div style={{position: 'absolute', inset: 0, opacity: interpolate(g, [d.titleUntil - 6, d.titleUntil], [1, 0], {extrapolateLeft: 'clamp'})}}>
          <TitleCard d={d.title} frame={g} length={d.titleUntil} />
        </div>
      )}
    </AbsoluteFill>
  );
};

/** 영상 첫머리 제목 카드: 검은 바탕에 연기가 천천히 흐르고 빨간 이름표·흰 제목. length 프레임 동안 아래 막대가 찬다 */
const TitleCard: React.FC<{d: ReplayTitleData; frame: number; length: number}> = ({d, frame, length}) => {
  const {fps} = useVideoConfig();
  const t = frame / fps;
  const s = spring({frame, fps, config: theme.motion.enter});
  const line = interpolate(frame, [0.15 * fps, length], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  return (
    <AbsoluteFill>
      <div style={{position: 'absolute', left: CARD.x, top: CARD.y, width: CARD.w, height: CARD.h, borderRadius: theme.radius.card,
        overflow: 'hidden', background: NIGHT, boxShadow: theme.shadow.card}}>
        <svg width={CARD.w} height={CARD.h} style={{position: 'absolute', inset: 0}}>
          <defs>
            <filter id="smoke" x="0" y="0" width="100%" height="100%">
              <feTurbulence type="fractalNoise" baseFrequency="0.0028 0.0055" numOctaves={4} seed={7} />
              <feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 1.1 -0.42" />
              <feGaussianBlur stdDeviation={6} />
            </filter>
          </defs>
          <rect x={-CARD.w} y={0} width={CARD.w * 3} height={CARD.h} filter="url(#smoke)" opacity={0.42}
            transform={`translate(${-t * 26} ${Math.sin(t * 0.5) * 8})`} />
          <rect x={-CARD.w} y={0} width={CARD.w * 3} height={CARD.h} filter="url(#smoke)" opacity={0.22}
            transform={`translate(${t * 18 - 300} ${CARD.h * 0.35}) scale(1 0.7)`} />
        </svg>
        <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(ellipse at 50% 50%, rgba(11,13,18,0) 30%, rgba(11,13,18,0.85) 100%)'}} />
        <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          padding: '0 70px', textAlign: 'center', opacity: Math.min(1, s * 1.4), translate: `0 ${(1 - s) * 24}px`}}>
          <div style={{font: `800 40px ${FONT}`, color: '#ef4444', letterSpacing: '0.02em'}}>{d.label}</div>
          <div style={{font: `900 92px/1.18 ${FONT}`, color: '#ffffff', marginTop: 22, wordBreak: 'keep-all', letterSpacing: '-0.03em'}}>{d.title}</div>
          <div style={{width: 520, height: 6, borderRadius: 3, background: 'rgba(255,255,255,0.16)', marginTop: 44, overflow: 'hidden'}}>
            <div style={{width: `${line * 100}%`, height: '100%', background: '#ef4444'}} />
          </div>
          <div style={{font: `600 32px ${FONT}`, color: 'rgba(255,255,255,0.75)', marginTop: 30}}>{d.line}</div>
          <div style={{font: `600 30px ${FONT}`, color: 'rgba(255,255,255,0.5)', marginTop: 10}}>{d.date}</div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** 영상 맨 끝 고지 카드: 말 없이 1.3초 남짓 */
export const ReplayCard: React.FC<CustomSceneProps> = ({data}) => {
  const {mode} = data as {mode: string};
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const s = spring({frame, fps, config: theme.motion.enter});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', opacity: Math.min(1, s * 1.5), translate: `0 ${(1 - s) * 30}px`}}>
      <div style={{textAlign: 'center', padding: '44px 70px', ...card()}}>
        <div style={{font: `900 72px ${FONT}`, color: theme.color.ink}}>{mode} 기록</div>
        <div style={{font: `700 52px ${FONT}`, color: theme.color.sub, marginTop: 18}}>투자 권유가 아니에요</div>
      </div>
    </AbsoluteFill>
  );
};
