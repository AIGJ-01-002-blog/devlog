// 연기에 쓰는 소품. 모두 무대(1080×700) 위 절대 위치로 그린다.
import React from 'react';
import {FONT, MONO} from './fonts';

const INK = '#334155'; // 밝은 바탕에서 또렷한 진회색 테두리(theme.ts와 맞춤)
export const C = {
  red: '#ef4444', green: '#22c55e', blue: '#38bdf8', yellow: '#facc15', pink: '#f9a8d4',
  slate: '#1e293b', slateD: '#0f172a', muted: '#94a3b8', code: '#e2e8f0', comment: '#7dd3fc',
};

type Pos = {x: number; y: number; scale?: number; opacity?: number; rotate?: number};

const place = ({x, y, scale = 1, opacity = 1, rotate = 0}: Pos, w: number, h: number): React.CSSProperties => ({
  position: 'absolute', left: x - w / 2, top: y - h / 2, width: w, height: h, opacity,
  scale: String(scale), rotate: `${rotate}deg`,
});

export const Paper: React.FC<Pos & {label?: string}> = ({label, ...pos}) => (
  <div style={place(pos, 120, 150)}>
    <svg viewBox="0 0 120 150" width={120} height={150}>
      <path d="M 6 6 H 84 L 114 36 V 144 H 6 Z" fill="#fff" stroke={INK} strokeWidth={6} strokeLinejoin="round" />
      <path d="M 84 6 V 36 H 114" fill="#e2e8f0" stroke={INK} strokeWidth={6} strokeLinejoin="round" />
      <path d="M 24 64 H 96 M 24 88 H 96 M 24 112 H 76" stroke="#94a3b8" strokeWidth={9} strokeLinecap="round" />
    </svg>
    {label ? <div style={{position: 'absolute', top: -58, width: '100%', textAlign: 'center', font: `700 34px ${FONT}`, color: '#111827'}}>{label}</div> : null}
  </div>
);

export const Trash: React.FC<Pos & {lid: number}> = ({lid, ...pos}) => (
  <div style={place(pos, 200, 240)}>
    <svg viewBox="0 0 200 240" width={200} height={240} style={{overflow: 'visible'}}>
      <g transform={`rotate(${-35 * lid} 20 52) translate(0 ${-10 * lid})`}>
        <rect x={10} y={38} width={180} height={28} rx={12} fill="#94a3b8" stroke={INK} strokeWidth={7} />
        <rect x={76} y={22} width={48} height={20} rx={8} fill="#94a3b8" stroke={INK} strokeWidth={7} />
      </g>
      <path d="M 24 72 H 176 L 160 232 H 40 Z" fill="#64748b" stroke={INK} strokeWidth={7} strokeLinejoin="round" />
      <path d="M 70 100 L 76 206 M 100 100 V 206 M 130 100 L 124 206" stroke="#334155" strokeWidth={9} strokeLinecap="round" />
    </svg>
  </div>
);

export const Server: React.FC<Pos & {alert: number}> = ({alert, ...pos}) => (
  <div style={place(pos, 230, 300)}>
    <svg viewBox="0 0 230 300" width={230} height={300}>
      {[0, 1, 2].map((k) => (
        <g key={k} transform={`translate(0 ${k * 98})`}>
          <rect x={6} y={6} width={218} height={86} rx={18} fill={C.slate} stroke={INK} strokeWidth={7} />
          <path d="M 34 49 H 120" stroke="#475569" strokeWidth={10} strokeLinecap="round" />
          <circle cx={186} cy={49} r={13} fill={alert > 0.5 && k !== 1 ? C.red : C.green} />
        </g>
      ))}
    </svg>
  </div>
);

export const Database: React.FC<Pos & {fill: number; color?: string}> = ({fill, color = C.blue, ...pos}) => {
  const top = 50 + 190 * (1 - Math.max(0, Math.min(1, fill)));
  return (
    <div style={place(pos, 220, 290)}>
      <svg viewBox="0 0 220 290" width={220} height={290}>
        <defs>
          <clipPath id="db-body"><path d="M 10 40 V 240 A 100 34 0 0 0 210 240 V 40 Z" /></clipPath>
        </defs>
        <path d="M 10 40 V 240 A 100 34 0 0 0 210 240 V 40 Z" fill={C.slate} />
        <rect x={0} y={top} width={220} height={290} fill={color} clipPath="url(#db-body)" />
        <path d="M 10 40 V 240 A 100 34 0 0 0 210 240 V 40" fill="none" stroke={INK} strokeWidth={7} />
        <ellipse cx={110} cy={40} rx={100} ry={34} fill="#334155" stroke={INK} strokeWidth={7} />
      </svg>
    </div>
  );
};

export const Bulb: React.FC<Pos & {glow: number}> = ({glow, ...pos}) => (
  <div style={place(pos, 300, 300)}>
    <svg viewBox="-150 -150 300 300" width={300} height={300}>
      {Array.from({length: 10}).map((_, k) => {
        const a = (k / 10) * Math.PI * 2 + glow * 0.5;
        const r1 = 92 + 16 * Math.sin(glow * 4 + k);
        return (
          <path key={k} d={`M ${Math.cos(a) * 78} ${Math.sin(a) * 78 - 20} L ${Math.cos(a) * r1} ${Math.sin(a) * r1 - 20}`} stroke={C.yellow}
            strokeWidth={10} strokeLinecap="round" opacity={Math.min(1, glow)} />
        );
      })}
      <circle cx={0} cy={-20} r={58} fill="#fde047" stroke={INK} strokeWidth={7} />
      <path d="M -14 -4 Q 0 -30 14 -4" stroke="#ca8a04" strokeWidth={6} fill="none" />
      <rect x={-30} y={34} width={60} height={42} rx={10} fill="#94a3b8" stroke={INK} strokeWidth={7} />
    </svg>
  </div>
);

export const Shield: React.FC<Pos> = (pos) => (
  <div style={place(pos, 240, 280)}>
    <svg viewBox="0 0 240 280" width={240} height={280}>
      <path d="M 120 10 L 226 50 V 130 C 226 200 170 250 120 270 C 70 250 14 200 14 130 V 50 Z" fill={C.green} stroke={INK} strokeWidth={8}
        strokeLinejoin="round" />
      <path d="M 120 34 L 204 66 V 130 C 204 186 160 228 120 246 Z" fill="#16a34a" />
      <path d="M 70 140 L 106 176 L 172 104" stroke="#fff" strokeWidth={22} fill="none" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  </div>
);

export const Badge: React.FC<Pos & {good: boolean}> = ({good, ...pos}) => (
  <div style={place(pos, 150, 150)}>
    <svg viewBox="0 0 150 150" width={150} height={150}>
      <circle cx={75} cy={75} r={66} fill={good ? C.green : C.red} stroke={INK} strokeWidth={7} />
      {good ? (
        <path d="M 42 78 L 66 102 L 110 54" stroke="#fff" strokeWidth={16} fill="none" strokeLinecap="round" strokeLinejoin="round" />
      ) : (
        <path d="M 50 50 L 100 100 M 100 50 L 50 100" stroke="#fff" strokeWidth={16} strokeLinecap="round" />
      )}
    </svg>
  </div>
);

export const Star: React.FC<Pos & {color?: string}> = ({color = C.yellow, ...pos}) => (
  <div style={place(pos, 60, 60)}>
    <svg viewBox="-30 -30 60 60" width={60} height={60}>
      <path d="M 0 -26 Q 4 -4 26 0 Q 4 4 0 26 Q -4 4 -26 0 Q -4 -4 0 -26 Z" fill={color} />
    </svg>
  </div>
);

export const Pill: React.FC<Pos & {text: string; bg: string; color?: string; size?: number}> = ({text, bg, color = '#0f172a', size = 34, ...pos}) => (
  <div style={{...place(pos, 0, 0), width: 'auto', height: 'auto', translate: '-50% -50%', left: pos.x, top: pos.y}}>
    <div style={{
      background: bg, color, font: `800 ${size}px ${FONT}`, padding: `${size * 0.28}px ${size * 0.62}px`, borderRadius: 999,
      whiteSpace: 'nowrap', boxShadow: '0 8px 20px rgba(15,23,42,0.12)',
    }}>{text}</div>
  </div>
);

export const Mark: React.FC<Pos & {text: string; color: string; size: number}> = ({text, color, size, ...pos}) => (
  <div style={{position: 'absolute', left: pos.x, top: pos.y, translate: '-50% -50%', scale: String(pos.scale ?? 1), rotate: `${pos.rotate ?? 0}deg`,
    opacity: pos.opacity ?? 1, font: `900 ${size}px ${FONT}`, color, WebkitTextStroke: `6px ${INK}`, paintOrder: 'stroke'}}>
    {text}
  </div>
);

/** 문법 강조를 조금 한 코드 한 줄 */
const KEYWORDS = /\b(const|let|var|function|return|if|else|for|while|import|from|export|async|await|new|class|SELECT|FROM|WHERE|ORDER|BY|DESC|ASC|LIMIT|CREATE|INDEX|ON|EXPLAIN|ANALYZE|INSERT|UPDATE|DELETE|docker|compose|git|npm|true|false|null|undefined)\b/g;

export const CodeLine: React.FC<{line: string; size: number}> = ({line, size}) => {
  const commentAt = (() => {
    const t = line.trimStart();
    if (t.startsWith('#') || t.startsWith('//') || t.startsWith('--')) return 0;
    const m = line.search(/\s(\/\/|#|--\s)/);
    return m >= 0 ? m + 1 : -1;
  })();
  const code = commentAt >= 0 ? line.slice(0, commentAt) : line;
  const comment = commentAt >= 0 ? line.slice(commentAt) : '';
  const parts: React.ReactNode[] = [];
  let last = 0;
  const tokens = /("[^"]*"|'[^']*'|`[^`]*`|\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_]+\b)/g;
  let m: RegExpExecArray | null;
  while ((m = tokens.exec(code))) {
    if (m.index > last) parts.push(code.slice(last, m.index));
    const tok = m[0];
    const isKeyword = KEYWORDS.test(tok);
    KEYWORDS.lastIndex = 0;
    const color = m[1] ? '#fca5a5' : isKeyword ? '#c4b5fd' : /^[A-Za-z_]+$/.test(tok) && code[m.index + tok.length] === '(' ? '#7dd3fc' : C.code;
    parts.push(<span key={m.index} style={{color}}>{tok}</span>);
    last = m.index + tok.length;
  }
  if (last < code.length) parts.push(code.slice(last));
  return (
    <span style={{font: `500 ${size}px ${MONO}`, color: C.code, whiteSpace: 'pre'}}>
      {parts}
      {comment ? <span style={{color: '#86efac', fontFamily: MONO}}>{comment}</span> : null}
    </span>
  );
};
