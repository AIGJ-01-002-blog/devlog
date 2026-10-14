// 렌더 워커(파이썬)가 넘기는 props. 모양을 바꾸면 devlog_render/remotion.py도 같이 바꾼다.

export type Action =
  | 'explain' | 'shock' | 'typing' | 'panic' | 'restore' | 'wait'
  | 'fast' | 'idea' | 'protect' | 'think' | 'celebrate';

export type Word = {text: string; startMs: number; endMs: number};

/** 화면에 한 번에 보이는 자막 묶음(1~2줄). 시각은 그 장면 목소리가 시작한 때 기준 */
export type Page = {startMs: number; endMs: number; words: Word[]};

export type ActionParams = {
  focus: number | null; // 강조할 코드 줄
  good: boolean; // 고친 줄(초록)인지 문제 줄(빨강)인지
  count: number | null;
  before: string | null;
  after: string | null;
  label: string;
  shell: boolean;
};

export type SceneData = {
  caption: string;
  code: string[];
  action: Action;
  params: ActionParams;
  durationInFrames: number;
  leadFrames: number; // 장면 시작부터 목소리가 나오기까지
  speechFrames: number;
  pages: Page[];
  mouth: number[]; // 목소리 프레임마다 입 벌림 0~1
  image: string | null; // public 폴더(작업 폴더) 안 파일 이름
  /** 캐릭터 대신 다른 장면 컴포넌트를 쓸 때(예: 매매 재현 #265). custom.tsx의 CUSTOM_SCENES[kind]가 data를 받아 무대를 그린다 */
  custom: {kind: string; data: unknown} | null;
};

export type ShortsProps = {
  title: string;
  footer: string;
  transitionFrames: number;
  totalFrames: number;
  scenes: SceneData[];
};
