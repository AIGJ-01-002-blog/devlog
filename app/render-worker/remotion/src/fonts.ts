// 글꼴은 npm 패키지(Pretendard, JetBrains Mono, 둘 다 SIL OFL)에서 번들로 넣는다. 렌더하는 컴퓨터에 글꼴이 없어도 같은 화면이 나온다.
import {loadFont} from '@remotion/fonts';
import pretendardMedium from 'pretendard/dist/web/static/woff2/Pretendard-Medium.woff2';
import pretendardBold from 'pretendard/dist/web/static/woff2/Pretendard-Bold.woff2';
import pretendardExtraBold from 'pretendard/dist/web/static/woff2/Pretendard-ExtraBold.woff2';
import pretendardBlack from 'pretendard/dist/web/static/woff2/Pretendard-Black.woff2';
import monoRegular from '@fontsource/jetbrains-mono/files/jetbrains-mono-latin-500-normal.woff2';

export const FONT = "'Pretendard', sans-serif";
// 한글 주석은 고정폭 글꼴에 없으므로 Pretendard로 이어 그린다
export const MONO = "'JetBrains Mono', 'Pretendard', monospace";

for (const [url, weight] of [[pretendardMedium, '500'], [pretendardBold, '700'], [pretendardExtraBold, '800'], [pretendardBlack, '900']] as const) {
  loadFont({family: 'Pretendard', url, weight});
}
loadFont({family: 'JetBrains Mono', url: monoRegular, weight: '500'});
