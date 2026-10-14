// 매매 재현 장면 (#265): 캔들이 한 개씩 흘러가다 체결 시각에 매수·매도 표시가 찍힌다.
// 워커(devlog_render/replay.py의 chart_data)가 차트 전체 데이터와 이 장면이 영상에서 시작하는 프레임(startFrame)을 넘긴다.
// 캔들·체결 시각은 영상 전체 프레임이라, 장면 안 프레임 + startFrame으로 지금이 영상의 몇 번째 프레임인지 구한다.
// 색은 거래소 차트처럼 매수·이익 초록, 매도·손실 빨강.
import React from 'react';
import {AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {STAGE_H, STAGE_W} from './actions';
import type {CustomSceneProps} from './custom';
import {FONT} from './fonts';

const UP = '#26a69a';
const DOWN = '#ef5350';
const MUTED = '#94a3b8';
const GRID = 'rgba(148,163,184,0.18)';

export type ReplayCandle = {o: number; h: number; l: number; c: number; at: number; label: string};
export type ReplayFill = {index: number; at: number; side: 'BUY' | 'SELL'; price: number; banner: string; closeIndex: number | null; good: boolean};
export type ReplayData = {
  startFrame: number;
  header: string;
  mode: string;
  lo: number;
  hi: number;
  ticks: {price: number; label: string}[];
  timeStart: string;
  timeEnd: string;
  candles: ReplayCandle[];
  fills: ReplayFill[];
  result: {at: number; until: number | null; label: string; good: boolean} | null;
};

// 무대(1080×720) 안 차트 자리. 오른쪽 140px은 가격 눈금
const BOX = {x: 40, y: 20, w: STAGE_W - 80, h: STAGE_H - 40};
const PLOT = {x0: BOX.x + 24, x1: BOX.x + BOX.w - 150, y0: BOX.y + 110, y1: BOX.y + BOX.h - 56};

const FLASH = 6;
const RING = 18;
const BANNER = 54;
const SHAKE = 11;

export const ReplayScene: React.FC<CustomSceneProps> = ({data}) => {
  const d = data as ReplayData;
  const local = useCurrentFrame();
  const {fps} = useVideoConfig();
  const g = local + d.startFrame; // 영상 전체 프레임
  const n = d.candles.length;
  const slot = (PLOT.x1 - PLOT.x0) / Math.max(1, n);
  const cx = (i: number) => PLOT.x0 + slot * (i + 0.5);
  const y = (p: number) => PLOT.y1 - ((p - d.lo) / (d.hi - d.lo || 1)) * (PLOT.y1 - PLOT.y0);
  const shown = d.candles.filter((c) => c.at <= g).length;
  const last = shown > 0 ? d.candles[shown - 1] : null;
  const fills = d.fills.filter((f) => f.at <= g);
  // 체결 순간 화면이 짧게 흔들린다(가장 최근 체결 기준, 점점 잦아든다)
  const recent = fills.length > 0 ? fills[fills.length - 1] : null;
  const since = recent ? g - recent.at : Infinity;
  const amp = since < SHAKE ? 10 * (1 - since / SHAKE) : 0;
  const shake = `translate(${amp * Math.sin(since * 2.1)}px, ${amp * Math.cos(since * 1.7)}px)`;
  const resultOn = d.result && g >= d.result.at && (d.result.until === null || g < d.result.until);

  return (
    <AbsoluteFill style={{transform: shake}}>
      <div style={{position: 'absolute', left: BOX.x, top: BOX.y, width: BOX.w, height: BOX.h, borderRadius: 28,
        background: 'rgba(10,16,34,0.88)', border: '2px solid rgba(255,255,255,0.08)', boxShadow: '0 24px 60px rgba(0,0,0,0.45)'}} />
      <div style={{position: 'absolute', left: BOX.x + 30, top: BOX.y + 22, font: `800 34px ${FONT}`, color: '#f8fafc'}}>{d.header}</div>
      <div style={{position: 'absolute', right: STAGE_W - BOX.x - BOX.w + 30, top: BOX.y + 24, textAlign: 'right'}}>
        <div style={{font: `700 28px ${FONT}`, color: MUTED}}>{d.mode}</div>
        <div style={{font: `800 24px ${FONT}`, color: DOWN, marginTop: 6, opacity: (local % fps) < fps * 0.6 ? 1 : 0.3}}>● REPLAY</div>
      </div>

      <svg width={STAGE_W} height={STAGE_H} style={{position: 'absolute', inset: 0}}>
        {d.ticks.map((t) => (
          <g key={t.label}>
            <line x1={PLOT.x0} x2={PLOT.x1} y1={y(t.price)} y2={y(t.price)} stroke={GRID} strokeWidth={2} />
            <text x={PLOT.x1 + 14} y={y(t.price) + 9} fill={MUTED} style={{font: `600 24px ${FONT}`}}>{t.label}</text>
          </g>
        ))}
        {/* 매수 가격 선: 다음 매도까지 이어 그린다 */}
        {fills.filter((f) => f.side === 'BUY').map((f, k) => {
          const end = f.closeIndex !== null && d.candles[f.closeIndex].at <= g ? cx(f.closeIndex) : cx(Math.max(f.index, shown - 1));
          return <line key={`l${k}`} x1={cx(f.index)} x2={end} y1={y(f.price)} y2={y(f.price)} stroke={UP} strokeOpacity={0.75} strokeWidth={3} strokeDasharray="10 8" />;
        })}
        {d.candles.slice(0, shown).map((c, i) => {
          const color = c.c >= c.o ? UP : DOWN;
          const grow = interpolate(g - c.at, [0, 4], [0.2, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
          const top = y(Math.max(c.o, c.c));
          const h = Math.max(3, y(Math.min(c.o, c.c)) - top);
          return (
            <g key={i} style={{transformOrigin: `${cx(i)}px ${top + h}px`, transform: `scaleY(${grow})`}}>
              <rect x={cx(i) - Math.max(1, slot * 0.07)} y={y(c.h)} width={Math.max(2, slot * 0.14)} height={Math.max(2, y(c.l) - y(c.h))} fill={color} />
              <rect x={cx(i) - slot * 0.34} y={top} width={Math.max(2, slot * 0.68)} height={h} rx={Math.min(3, slot * 0.1)} fill={color} />
            </g>
          );
        })}
        {/* 지금 가격: 마지막으로 나온 캔들의 종가가 오른쪽 눈금에서 따라 움직인다 */}
        {last && (
          <g>
            <line x1={PLOT.x0} x2={PLOT.x1} y1={y(last.c)} y2={y(last.c)} stroke={last.c >= last.o ? UP : DOWN} strokeOpacity={0.5} strokeWidth={2} />
            <rect x={PLOT.x1 + 4} y={y(last.c) - 19} width={140} height={38} rx={8} fill={last.c >= last.o ? UP : DOWN} />
            <text x={PLOT.x1 + 12} y={y(last.c) + 9} fill="#fff" style={{font: `700 24px ${FONT}`}}>{last.label}</text>
          </g>
        )}
        {fills.map((f, k) => {
          const buy = f.side === 'BUY';
          const color = buy ? UP : DOWN;
          const age = g - f.at;
          const ring = interpolate(age, [0, RING], [20, 150], {extrapolateRight: 'clamp', easing: Easing.out(Easing.cubic)});
          const pop = spring({frame: age, fps, config: {damping: 10, stiffness: 220}});
          const c = d.candles[f.index];
          const labelY = buy ? Math.min(y(c.l) + 40, PLOT.y1 + 10) : Math.max(y(c.h) - 40, PLOT.y0 - 20);
          return (
            <g key={`f${k}`}>
              {age < RING && <circle cx={cx(f.index)} cy={y(f.price)} r={ring} fill="none" stroke={color} strokeWidth={6} strokeOpacity={1 - age / RING} />}
              <line x1={cx(f.index)} x2={cx(f.index)} y1={y(f.price)} y2={labelY} stroke={color} strokeWidth={4} />
              <circle cx={cx(f.index)} cy={y(f.price)} r={9 * pop} fill="#fff" stroke={color} strokeWidth={5} />
              <g style={{transformOrigin: `${cx(f.index)}px ${labelY}px`, transform: `scale(${pop})`}}>
                <rect x={cx(f.index) - 46} y={labelY - 22} width={92} height={44} rx={10} fill={color} />
                <text x={cx(f.index)} y={labelY + 10} textAnchor="middle" fill="#fff" style={{font: `800 28px ${FONT}`}}>{buy ? '매수' : '매도'}</text>
              </g>
            </g>
          );
        })}
        <text x={PLOT.x0} y={BOX.y + BOX.h - 18} fill={MUTED} style={{font: `600 24px ${FONT}`}}>{d.timeStart}</text>
        <text x={PLOT.x1} y={BOX.y + BOX.h - 18} textAnchor="end" fill={MUTED} style={{font: `600 24px ${FONT}`}}>{d.timeEnd}</text>
      </svg>

      {/* 체결 순간: 차트가 그 색으로 번쩍이고 가격 띠가 오른쪽에서 밀려 들어온다 */}
      {recent && since < FLASH && (
        <div style={{position: 'absolute', left: BOX.x, top: BOX.y, width: BOX.w, height: BOX.h, borderRadius: 28,
          background: recent.side === 'BUY' ? UP : DOWN, opacity: 0.32 * (1 - since / FLASH)}} />
      )}
      {recent && since < BANNER && (() => {
        const enter = spring({frame: since, fps, config: {damping: 16, stiffness: 160}});
        const leave = interpolate(since, [BANNER - 8, BANNER], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
        return (
          <div style={{position: 'absolute', top: BOX.y + 96, left: 0, right: 0, display: 'flex', justifyContent: 'center',
            transform: `translateX(${(1 - enter) * STAGE_W}px)`, opacity: leave}}>
            <div style={{font: `900 50px ${FONT}`, color: '#fff', background: recent.side === 'BUY' ? UP : DOWN, padding: '12px 30px',
              borderRadius: 18, boxShadow: '0 14px 40px rgba(0,0,0,0.45)'}}>{recent.banner}</div>
          </div>
        );
      })()}
      {/* 결과 장면: 손익 합계가 차트 가운데에 크게 튀어나온다 */}
      {resultOn && d.result && (() => {
        const s = spring({frame: g - d.result.at, fps, config: {damping: 9, stiffness: 180}});
        return (
          <>
            <div style={{position: 'absolute', left: BOX.x, top: BOX.y, width: BOX.w, height: BOX.h, borderRadius: 28,
              background: 'rgba(0,0,0,0.5)', opacity: Math.min(1, s)}} />
            <div style={{position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
              <div style={{font: `900 132px ${FONT}`, color: d.result.good ? UP : DOWN, scale: String(0.3 + 0.7 * s),
                WebkitTextStroke: '8px #0b1024', paintOrder: 'stroke', textShadow: '0 12px 0 rgba(0,0,0,0.35)'}}>{d.result.label}</div>
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
