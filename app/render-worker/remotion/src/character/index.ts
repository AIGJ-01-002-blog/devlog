// 쇼츠 캐릭터(Lottie)를 이 폴더에서 모은다. 하위 폴더 이름이 캐릭터 이름, 파일 이름(확장자 뺀 것)이 동작 이름이다(README.md).
// 대본의 character가 없거나 그 폴더에 idle.json이 없으면 기본 고양이가 연기한다.
import type {LottieAnimationData} from '@remotion/lottie';
import {createContext, useContext} from 'react';

export type Clip = 'idle' | 'talk' | 'point' | 'happy' | 'shock' | 'sad' | 'think' | 'typing' | 'idea' | 'sleepy' | 'panic' | 'walk';
export type Clips = Partial<Record<Clip, LottieAnimationData>>;

declare const require: {context: (dir: string, deep: boolean, re: RegExp) => {keys: () => string[]; (key: string): unknown}};

const files = require.context('./', true, /^\.\/[a-z0-9-]+\/[a-z]+\.json$/);
export const CHARACTERS: Record<string, Clips> = {};
for (const k of files.keys()) {
  const [, name, clip] = k.match(/^\.\/([a-z0-9-]+)\/([a-z]+)\.json$/)!;
  (CHARACTERS[name] ??= {})[clip as Clip] = files(k) as LottieAnimationData;
}

/** 한 번만 재생하고 마지막 장면에 멈추는 동작(나머지는 반복) */
export const ONCE: Clip[] = ['shock', 'idea'];
/** talk 동작의 입 모양 프레임(캐릭터 manifest의 mouthFrames) */
export const MOUTH = {closed: 0, small: 4, open: 8} as const;

/** 이번 영상의 캐릭터 이름(ShortsProps.character). Shorts가 채운다 */
export const CharacterName = createContext<string | null>(null);

/** 이번 영상 캐릭터의 동작들. 없으면 null(기본 고양이) */
export const useCharacter = (): Clips | null => {
  const name = useContext(CharacterName);
  const clips = name ? CHARACTERS[name] : undefined;
  return clips?.idle ? clips : null;
};
