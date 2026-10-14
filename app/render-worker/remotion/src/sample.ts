// 스튜디오(npm run studio)에서 볼 기본 장면. 실제 렌더는 워커가 --props로 넘긴다.
import type {SceneData, ShortsProps} from './types';

const w = (text: string, startMs: number, endMs: number) => ({text, startMs, endMs, timestampMs: (startMs + endMs) / 2, confidence: null});

const scene = (caption: string, action: SceneData['action'], code: string[] = [], extra: Partial<SceneData['params']> = {}): SceneData => ({
  caption, code, action, durationInFrames: 120, leadFrames: 6, speechFrames: 96, image: null, custom: null, diagram: null,
  params: {focus: null, good: false, count: null, before: null, after: null, label: '데이터', shell: false, ...extra},
  pages: [{startMs: 0, endMs: 3300, words: [w('자막이', 0, 600), w('여기에', 600, 1300), w('나와요', 1300, 2200)]}],
  mouth: Array.from({length: 96}, (_, k) => Math.abs(Math.sin(k / 2.3))),
});

export const sample: ShortsProps = {
  title: '도커 볼륨 날린 날: 100% 복구한 방법',
  footer: 'devlog.life/@minseo',
  transitionFrames: 9,
  totalFrames: 120 * 4 - 27,
  scenes: [
    scene('글이 전부 사라졌어요', 'shock', [], {label: '볼륨'}),
    scene('원인은 -v 한 글자', 'typing', ['docker compose down -v', '# -v: 볼륨까지 삭제'], {focus: 1, shell: true}),
    scene('빨라졌어요', 'fast', [], {before: '4초', after: '100ms', label: '40배 빨라짐'}),
    {...scene('요청이 가는 길', 'explain'), diagram: {type: 'flow', items: [
      {label: '브라우저', icon: null, note: null, atMs: 0}, {label: '서버', icon: null, note: null, atMs: 700}, {label: 'DB', icon: null, note: null, atMs: 1400}]}},
  ],
};
