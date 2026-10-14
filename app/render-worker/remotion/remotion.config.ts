// 렌더 워커가 CLI 옵션으로 화질·코덱을 넘긴다. 여기서는 기본값만 둔다.
import {Config} from '@remotion/cli/config';

Config.setVideoImageFormat('jpeg');
Config.setJpegQuality(92);
Config.setOverwriteOutput(true);
