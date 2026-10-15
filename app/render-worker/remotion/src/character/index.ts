// 블로그 주인이 만든 캐릭터(Lottie)를 이 폴더에서 모은다. 파일 이름(확장자 뺀 것)이 동작 이름이다(README.md).
// idle.json이 없으면 캐릭터를 쓰지 않고 기본 고양이가 연기한다.
import type {LottieAnimationData} from '@remotion/lottie';

export type Clip = 'idle' | 'talk' | 'point' | 'happy' | 'shock' | 'sad' | 'think' | 'typing' | 'idea' | 'sleepy' | 'panic' | 'walk';

declare const require: {context: (dir: string, deep: boolean, re: RegExp) => {keys: () => string[]; (key: string): unknown}};

const files = require.context('./', false, /\.json$/);
export const CLIPS: Partial<Record<Clip, LottieAnimationData>> = Object.fromEntries(
  files.keys().map((k) => [k.replace(/^\.\//, '').replace(/\.json$/, ''), files(k) as LottieAnimationData]),
);

export const hasCharacter = (): boolean => CLIPS.idle !== undefined;
