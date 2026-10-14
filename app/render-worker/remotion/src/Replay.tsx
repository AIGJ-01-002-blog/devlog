// 매매 재현 장면 (#265): 실시간 분봉 차트를 빨리감기하듯 캔들이 계속 생겨나며 왼쪽으로 흘러가다 체결 시각에 매수·매도 표시가 찍힌다.
// 워커(devlog_render/replay.py의 chart_data)가 차트 전체 데이터와 이 장면이 영상에서 시작하는 프레임(startFrame)을 넘긴다.
// head[영상 프레임]은 그 순간의 차트 위치(캔들 단위 실수)다. i번 캔들은 head가 i일 때 생기기 시작해 i+1일 때 다 그려진다.
// 그리는 중인 캔들은 시가 → 저가·고가 → 종가로 값이 움직여 실시간처럼 꼬리가 늘어난다. 가격 눈금은 보이는 캔들에 맞춰 부드럽게 따라간다.
// 색은 거래소 차트처럼 매수·이익 초록, 매도·손실 빨강.
import React from 'react';
import {AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {STAGE_H, STAGE_W} from './actions';
import type {CustomSceneProps} from './custom';
import {FONT} from './fonts';

const UP = '#26a69a';
const DOWN = '#ef5350';
const MUTED = '#94a3b8';
const GRID = 'rgba(148,163,184,0.16)';
const SOFT = '#e2e8f0';

export type ReplayCandle = {o: number; h: number; l: number; c: number; time: string};
export type ReplayFill = {index: number; at: number; side: 'BUY' | 'SELL'; price: number; banner: string; closeIndex: number | null; good: boolean};
export type ReplayData = {
  startFrame: number;
  header: string;
  mode: string;
  seconds: number;
  digits: number;
  unit: string;
  head: number[];
  candles: ReplayCandle[];
  fills: ReplayFill[];
  result: {at: number; until: number | null; label: string; good: boolean} | null;
};

// 무대(1080×720) 안 차트 자리. 오른쪽 150px은 가격 눈금, 아래는 진행 막대
const BOX = {x: 40, y: 20, w: STAGE_W - 80, h: STAGE_H - 40};
const PLOT = {x0: BOX.x + 20, x1: BOX.x + BOX.w - 160, y0: BOX.y + 120, y1: BOX.y + BOX.h - 96};
/** 한 화면에 보이는 캔들 수와, 지금 캔들 오른쪽에 비워 두는 칸 */
const VISIBLE = 46;
const AHEAD = 5;
/** 가격 눈금이 따라가는 부드러움(프레임) */
const SMOOTH = 10;

const FLASH = 6;
const RING = 18;
const BANNER = 60;

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
  price += (c.h - c.l) * 0.08 * Math.sin(f * 41 + seed * 1.7) * (1 - f);
  price = Math.min(c.h, Math.max(c.l, price));
  const seen = [...pts.slice(0, k + 1), price];
  return {o: c.o, c: price, h: Math.max(...seen), l: Math.min(...seen)};
};

/** head 위치에서 보이는 캔들(다 그려진 것 + 그리는 중인 것) */
const visibleCandles = (d: ReplayData, head: number) => {
  const n = d.candles.length;
  const offset = Math.max(0, head - (VISIBLE - AHEAD));
  const cur = Math.min(n, Math.floor(head));
  const out: {i: number; o: number; h: number; l: number; c: number}[] = [];
  for (let i = Math.max(0, Math.floor(offset) - 1); i < cur; i++) out.push({i, ...d.candles[i]});
  if (cur < n) out.push({i: cur, ...forming(d.candles[cur], head - cur, cur)});
  return {offset, out};
};

const rangeAt = (d: ReplayData, g: number) => {
  const {out} = visibleCandles(d, headAt(d, g));
  let lo = Infinity;
  let hi = -Infinity;
  for (const c of out) {
    lo = Math.min(lo, c.l);
    hi = Math.max(hi, c.h);
  }
  for (const f of d.fills) {
    if (f.at <= g && out.some((c) => c.i === f.index)) {
      lo = Math.min(lo, f.price);
      hi = Math.max(hi, f.price);
    }
  }
  return lo <= hi ? [lo, hi] : [0, 1];
};

const fmt = (v: number, digits: number, unit: string) =>
  v.toLocaleString('en-US', {minimumFractionDigits: digits, maximumFractionDigits: digits}) + unit;

export const ReplayScene: React.FC<CustomSceneProps> = ({data}) => {
  const d = data as ReplayData;
  const local = useCurrentFrame();
  const {fps} = useVideoConfig();
  const g = local + d.startFrame; // 영상 전체 프레임
  const n = d.candles.length;
  const head = headAt(d, g);
  const {offset, out: candles} = visibleCandles(d, head);
  const slot = (PLOT.x1 - PLOT.x0) / VISIBLE;
  const cx = (i: number) => PLOT.x0 + (i + 0.5 - offset) * slot;

  // 가격 눈금: 최근 몇 프레임의 범위를 평균 내 부드럽게 따라간다
  let lo = 0;
  let hi = 0;
  for (let k = 0; k < SMOOTH; k++) {
    const [a, b] = rangeAt(d, g - k);
    lo += a / SMOOTH;
    hi += b / SMOOTH;
  }
  const pad = (hi - lo) * 0.1 || Math.max(Math.abs(hi) * 0.002, 1e-6);
  lo -= pad;
  hi += pad;
  const y = (p: number) => PLOT.y1 - ((p - lo) / (hi - lo || 1)) * (PLOT.y1 - PLOT.y0);

  const live = candles.length > 0 ? candles[candles.length - 1] : null;
  const liveUp = live ? live.c >= live.o : true;
  const clock = d.candles[Math.min(n - 1, Math.floor(head))]?.time ?? '';
  // 빨리감기 배속: 1초 동안 흘러가는 실제 시간
  const span = 6;
  const rate = ((headAt(d, g + span) - headAt(d, g - span)) / ((2 * span) / fps)) * d.seconds;
  const speed = rate >= 100 ? Math.round(rate / 10) * 10 : Math.round(rate);
  const playing = speed >= 2 && head < n;

  const fills = d.fills.filter((f) => f.at <= g);
  const recent = fills.length > 0 ? fills[fills.length - 1] : null;
  const since = recent ? g - recent.at : Infinity;
  const resultOn = d.result && g >= d.result.at && (d.result.until === null || g < d.result.until);
  const ticks = [0, 1, 2, 3].map((k) => lo + ((hi - lo) * (k + 0.5)) / 4);
  const bar = {x0: PLOT.x0, x1: PLOT.x1 + 140, y: BOX.y + BOX.h - 40};
  const barX = (pos: number) => bar.x0 + (bar.x1 - bar.x0) * Math.min(1, Math.max(0, pos / Math.max(1, n)));

  return (
    <AbsoluteFill>
      <div style={{position: 'absolute', left: BOX.x, top: BOX.y, width: BOX.w, height: BOX.h, borderRadius: 28,
        background: 'rgba(10,16,34,0.92)', border: '1px solid rgba(255,255,255,0.06)'}} />
      <div style={{position: 'absolute', left: BOX.x + 30, top: BOX.y + 22, font: `700 30px ${FONT}`, color: SOFT}}>{d.header}</div>
      <div style={{position: 'absolute', left: BOX.x + 30, top: BOX.y + 66, display: 'flex', gap: 14, alignItems: 'center'}}>
        <div style={{font: `700 26px ${FONT}`, color: MUTED}}>{d.mode}</div>
        {playing && (
          <div style={{font: `700 24px ${FONT}`, color: MUTED, border: `1px solid ${GRID}`, padding: '1px 10px', borderRadius: 6}}>▶▶ ×{speed}</div>
        )}
      </div>
      {/* 시계: 지금 그리는 캔들의 한국 시간 */}
      <div style={{position: 'absolute', right: STAGE_W - BOX.x - BOX.w + 30, top: BOX.y + 18, textAlign: 'right',
        font: `700 60px ${FONT}`, color: SOFT, fontVariantNumeric: 'tabular-nums', letterSpacing: 1}}>{clock}</div>

      <svg width={STAGE_W} height={STAGE_H} style={{position: 'absolute', inset: 0}}>
        <defs>
          <clipPath id="plot">
            <rect x={PLOT.x0} y={PLOT.y0 - 40} width={PLOT.x1 - PLOT.x0} height={PLOT.y1 - PLOT.y0 + 80} />
          </clipPath>
          <linearGradient id="fade" x1="0" x2="1">
            <stop offset="0" stopColor="#0a1022" stopOpacity={offset > 0 ? 1 : 0} />
            <stop offset="0.08" stopColor="#0a1022" stopOpacity={0} />
          </linearGradient>
        </defs>
        {ticks.map((p, k) => (
          <g key={k}>
            <line x1={PLOT.x0} x2={PLOT.x1} y1={y(p)} y2={y(p)} stroke={GRID} strokeWidth={2} />
            <text x={PLOT.x1 + 14} y={y(p) + 9} fill={MUTED} style={{font: `600 22px ${FONT}`}}>{fmt(p, d.digits, d.unit)}</text>
          </g>
        ))}
        <g clipPath="url(#plot)">
          {/* 매수 가격 선: 다음 매도까지(아직이면 지금 캔들까지) 이어 그린다 */}
          {fills.filter((f) => f.side === 'BUY').map((f, k) => {
            const closed = f.closeIndex !== null && fills.some((o) => o.side === 'SELL' && o.index === f.closeIndex);
            const end = closed ? cx(f.closeIndex as number) : cx(Math.max(f.index, Math.floor(head)));
            return <line key={`l${k}`} x1={cx(f.index)} x2={end} y1={y(f.price)} y2={y(f.price)} stroke={UP} strokeOpacity={0.75} strokeWidth={3} strokeDasharray="10 8" />;
          })}
          {candles.map((c) => {
            const color = c.c >= c.o ? UP : DOWN;
            const top = y(Math.max(c.o, c.c));
            const h = Math.max(3, y(Math.min(c.o, c.c)) - top);
            return (
              <g key={c.i}>
                <rect x={cx(c.i) - 1.5} y={y(c.h)} width={3} height={Math.max(2, y(c.l) - y(c.h))} fill={color} />
                <rect x={cx(c.i) - slot * 0.36} y={top} width={slot * 0.72} height={h} rx={2} fill={color} />
              </g>
            );
          })}
          <rect x={PLOT.x0} y={PLOT.y0 - 40} width={(PLOT.x1 - PLOT.x0)} height={PLOT.y1 - PLOT.y0 + 80} fill="url(#fade)" />
        </g>
        {/* 지금 가격: 그리는 중인 캔들의 값이 오른쪽 눈금에서 실시간으로 따라 움직인다 */}
        {live && (
          <g>
            <line x1={PLOT.x0} x2={PLOT.x1} y1={y(live.c)} y2={y(live.c)} stroke={liveUp ? UP : DOWN} strokeOpacity={0.55} strokeWidth={2} strokeDasharray="4 6" />
            <rect x={PLOT.x1 + 4} y={y(live.c) - 19} width={150} height={38} rx={8} fill={liveUp ? UP : DOWN} />
            <text x={PLOT.x1 + 12} y={y(live.c) + 9} fill="#fff" style={{font: `700 22px ${FONT}`}}>{fmt(live.c, d.digits, d.unit)}</text>
          </g>
        )}
        {fills.map((f, k) => {
          const x = cx(f.index);
          if (x < PLOT.x0 - 60) return null;
          const buy = f.side === 'BUY';
          const color = buy ? UP : DOWN;
          const age = g - f.at;
          const ring = interpolate(age, [0, RING], [12, 70], {extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
          const pop = spring({frame: age, fps, config: {damping: 10, stiffness: 220}});
          const labelY = buy ? Math.min(y(f.price) + 70, PLOT.y1 + 20) : Math.max(y(f.price) - 70, PLOT.y0 - 20);
          return (
            <g key={`f${k}`} opacity={x < PLOT.x0 ? 0.3 : 1}>
              {age < RING && <circle cx={x} cy={y(f.price)} r={ring} fill="none" stroke={color} strokeWidth={2} strokeOpacity={0.8 * (1 - age / RING)} />}
              <line x1={x} x2={x} y1={y(f.price)} y2={labelY} stroke={color} strokeWidth={2} />
              <circle cx={x} cy={y(f.price)} r={7 * pop} fill="#fff" stroke={color} strokeWidth={3} />
              <g style={{transformOrigin: `${x}px ${labelY}px`, transform: `scale(${pop})`}}>
                <rect x={x - 38} y={labelY - 18} width={76} height={36} rx={6} fill={color} />
                <text x={x} y={labelY + 8} textAnchor="middle" fill="#fff" style={{font: `700 24px ${FONT}`}}>{buy ? '매수' : '매도'}</text>
              </g>
            </g>
          );
        })}
        {/* 진행 막대: 차트 전체 시간 중 어디까지 왔는지와 체결 자리 */}
        <line x1={bar.x0} x2={bar.x1} y1={bar.y} y2={bar.y} stroke="rgba(148,163,184,0.2)" strokeWidth={4} strokeLinecap="round" />
        <line x1={bar.x0} x2={barX(head)} y1={bar.y} y2={bar.y} stroke={SOFT} strokeOpacity={0.8} strokeWidth={4} strokeLinecap="round" />
        {d.fills.map((f, k) => (
          <circle key={`p${k}`} cx={barX(f.index + 0.5)} cy={bar.y} r={f.at <= g ? 7 : 5}
            fill={f.at <= g ? (f.side === 'BUY' ? UP : DOWN) : '#0a1022'} stroke={f.side === 'BUY' ? UP : DOWN} strokeWidth={2} />
        ))}
        <text x={bar.x0} y={bar.y - 16} fill={MUTED} style={{font: `600 20px ${FONT}`}}>{d.candles[0]?.time}</text>
        <text x={bar.x1} y={bar.y - 16} textAnchor="end" fill={MUTED} style={{font: `600 20px ${FONT}`}}>{d.candles[n - 1]?.time}</text>
      </svg>

      {/* 체결 순간: 차트가 그 색으로 번쩍이고 가격 띠가 오른쪽에서 밀려 들어온다 */}
      {recent && since < FLASH && (
        <div style={{position: 'absolute', left: BOX.x, top: BOX.y, width: BOX.w, height: BOX.h, borderRadius: 28,
          background: recent.side === 'BUY' ? UP : DOWN, opacity: 0.08 * (1 - since / FLASH)}} />
      )}
      {recent && since < BANNER && (() => {
        const enter = spring({frame: since, fps, config: {damping: 16, stiffness: 160}});
        const leave = interpolate(since, [BANNER - 8, BANNER], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
        return (
          <div style={{position: 'absolute', top: BOX.y + 128, left: 0, right: 0, display: 'flex', justifyContent: 'center',
            transform: `translateY(${(1 - enter) * 16}px)`, opacity: Math.min(enter, leave)}}>
            <div style={{font: `700 40px ${FONT}`, color: recent.side === 'BUY' ? UP : DOWN, background: 'rgba(10,16,34,0.92)',
              border: `1px solid ${recent.side === 'BUY' ? UP : DOWN}`, padding: '8px 24px', borderRadius: 10}}>{recent.banner}</div>
          </div>
        );
      })()}
      {/* 결과 장면: 손익 합계가 차트 가운데에 크게 튀어나온다 */}
      {resultOn && d.result && (() => {
        const s = spring({frame: g - d.result.at, fps, config: {damping: 20, stiffness: 120}});
        return (
          <>
            <div style={{position: 'absolute', left: BOX.x, top: BOX.y, width: BOX.w, height: BOX.h, borderRadius: 28,
              background: 'rgba(10,16,34,0.7)', opacity: Math.min(1, s)}} />
            <div style={{position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
              <div style={{font: `800 112px ${FONT}`, color: d.result.good ? UP : DOWN, scale: String(0.85 + 0.15 * s), opacity: Math.min(1, s)}}>{d.result.label}</div>
            </div>
          </>
        );
      })()}
    </AbsoluteFill>
  );
};

/** 영상 맨 끝 고지 카드: 말 없이 1.3초 남짓 */
export const ReplayCard: React.FC<CustomSceneProps> = ({data}) => {
  const {mode} = data as {mode: string};
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const s = spring({frame, fps, config: {damping: 14, stiffness: 200}});
  return (
    <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', opacity: Math.min(1, s * 1.5), translate: `0 ${(1 - s) * 30}px`}}>
      <div style={{textAlign: 'center', background: 'rgba(10,16,34,0.8)', padding: '44px 70px', borderRadius: 32, border: '2px solid rgba(255,255,255,0.1)'}}>
        <div style={{font: `900 72px ${FONT}`, color: '#fff'}}>{mode} 기록</div>
        <div style={{font: `700 52px ${FONT}`, color: MUTED, marginTop: 18}}>투자 권유가 아니에요</div>
      </div>
    </AbsoluteFill>
  );
};
