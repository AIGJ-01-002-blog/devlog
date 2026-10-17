// 렌더 워커(파이썬)가 넘기는 props. 모양을 바꾸면 devlog_render/render.py의 _props()도 같이 바꾼다.
import type {Caption} from '@remotion/captions';

export type Action =
  | 'explain' | 'shock' | 'typing' | 'panic' | 'restore' | 'wait'
  | 'fast' | 'idea' | 'protect' | 'think' | 'celebrate';

/** 낱말 하나: @remotion/captions의 Caption. 시각은 그 장면 목소리가 시작한 때 기준 ms */
export type Word = Caption & {
  /** 빠른 견본 스타일에서 색으로 강조할 낱말(영상 한 편에 2~3번, 워커가 고른다) */
  emph?: 'red' | 'yellow';
};

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

/** 도식 칸 하나. atMs: 목소리 시작 기준으로 이 칸이 나타나는 때(워커가 내레이션에서 이름표가 나오는 때로 정한다) */
export type DiagramItem = {label: string; icon: string | null; note: string | null; atMs: number};

/** CS 설명용 도식(#277). flow: 왼쪽→오른쪽 흐름, stack: 위→아래 층, steps: 순서, compare: 왼쪽(전·나쁨) 대 오른쪽(후·좋음) */
export type Diagram =
  | {type: 'flow'; items: DiagramItem[]}
  | {type: 'stack'; items: DiagramItem[]; highlight: number | null}
  | {type: 'steps'; items: DiagramItem[]}
  | {type: 'compare'; left: {title: string; atMs: number; items: DiagramItem[]}; right: {title: string; atMs: number; items: DiagramItem[]}};

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
  /** 앞 장면에서 전환 없이 바로 끊어 넘어온다(점프 컷). 매매 재현 차트 장면 */
  cut?: boolean;
  /** 있으면 고양이 연기 대신 도식을 그리고, 고양이는 왼쪽 아래에서 도식을 가리키며 설명한다 */
  diagram: Diagram | null;
  /** 화면 배치(워커 look.py). stage: 위 요점·가운데 무대(Scene), split: 위 그림·아래 캐릭터(FastScene). 없으면 style을 따른다 */
  layout?: 'stage' | 'split';
  /** 무대 카메라 움직임(looks.ts camera()). 없으면 시작할 때 살짝 당겨 들어온다 */
  camera?: string | null;
  /** 앞 장면에서 이 장면으로 넘어오는 전환(looks.ts transition()). 0번 장면은 null */
  transition?: string | null;
  /** 목소리가 시작할 때 한 번 일어나는 사건(looks.ts EventLayer): shake 흔들림, flash 섬광, stamp 도장. 영상 한 편에 한두 장면 */
  event?: 'shake' | 'flash' | 'stamp' | null;
};

export type ShortsProps = {
  title: string;
  footer: string;
  transitionFrames: number;
  totalFrames: number;
  scenes: SceneData[];
  /** clean: 승인된 깔끔한 스타일(기본). fast: 참고 영상 분석 견본(FastScene.tsx) */
  style?: 'clean' | 'fast';
  /** 고양이 대신 연기할 캐릭터(remotion/src/character/<이름>/). 없으면 기본 고양이 */
  character?: string | null;
  /** 이 영상의 룩 이름(looks.ts LOOKS). 없으면 paper */
  look?: string | null;
};
