// 도식 아이콘: Lucide(ISC). 대본이 아이콘 이름을 주지 않으면 이름표 글에서 짐작한다.
import React from 'react';
import {
  Archive, ArrowRightLeft, Box, Bug, Check, Cloud, Code, Cpu, Database, FileText, Folder, Gauge, GitBranch, Globe, HardDrive, Hash, KeyRound,
  Layers, List, Lock, Mail, MemoryStick, Monitor, Network, Package, RefreshCw, Router, ScrollText, Search, Server, Settings, Shield,
  Smartphone, Table, Terminal, Timer, Trash2, User, Workflow, X, Zap, type LucideIcon,
} from 'lucide-react';

export const ICONS: Record<string, LucideIcon> = {
  archive: Archive, 'arrow-right-left': ArrowRightLeft, box: Box, bug: Bug, check: Check, cloud: Cloud, code: Code, cpu: Cpu,
  database: Database, 'file-text': FileText, folder: Folder, gauge: Gauge, 'git-branch': GitBranch, globe: Globe, 'hard-drive': HardDrive,
  hash: Hash, key: KeyRound, layers: Layers, list: List, lock: Lock, mail: Mail, memory: MemoryStick, monitor: Monitor, network: Network,
  package: Package, refresh: RefreshCw, router: Router, log: ScrollText, search: Search, server: Server, settings: Settings, shield: Shield,
  phone: Smartphone, table: Table, terminal: Terminal, timer: Timer, trash: Trash2, user: User, workflow: Workflow, x: X, zap: Zap,
};

/** 이름표 글 → 아이콘. 앞에 있는 규칙이 이긴다 */
const GUESS: [RegExp, string][] = [
  [/브라우저|browser|크롬|웹 ?페이지/i, 'globe'],
  [/사용자|유저|user|고객|클라이언트|client/i, 'user'],
  [/캐시|cache|redis/i, 'zap'],
  [/인덱스|index|색인/i, 'search'],
  [/DB|데이터베이스|database|postgres|mysql|sql/i, 'database'],
  [/테이블|table/i, 'table'],
  [/백업|backup|덤프|dump/i, 'archive'],
  [/디스크|disk|볼륨|volume|저장소|storage|SSD/i, 'hard-drive'],
  [/컨테이너|container|도커|docker/i, 'box'],
  [/서버|server|백엔드|backend|API/i, 'server'],
  [/메모리|memory|RAM|힙|heap|스택|stack/i, 'memory'],
  [/CPU|프로세서|코어/i, 'cpu'],
  [/큐|queue|대기열/i, 'list'],
  [/스레드|thread|프로세스|process/i, 'layers'],
  [/네트워크|network|인터넷|DNS|HTTP/i, 'network'],
  [/라우터|router|게이트웨이|gateway|로드 ?밸런서/i, 'router'],
  [/클라우드|cloud|S3/i, 'cloud'],
  [/보안|암호|인증|로그인|권한|auth/i, 'lock'],
  [/토큰|token|API ?키|비밀 ?키|key/i, 'key'],
  [/해시|hash/i, 'hash'],
  [/깃|git|브랜치|branch|커밋|commit/i, 'git-branch'],
  [/버그|bug|에러|오류|예외/i, 'bug'],
  [/로그|log/i, 'log'],
  [/터미널|명령|terminal|shell|CLI/i, 'terminal'],
  [/코드|code|함수|function|컴포넌트/i, 'code'],
  [/설정|config|환경 ?변수/i, 'settings'],
  [/삭제|지우|delete|휴지통/i, 'trash'],
  [/검색|search|조회|쿼리|query/i, 'search'],
  [/메일|mail/i, 'mail'],
  [/폴더|folder|디렉터리/i, 'folder'],
  [/파일|file|문서|글/i, 'file-text'],
  [/패키지|package|npm|라이브러리/i, 'package'],
  [/폰|모바일|phone|mobile/i, 'phone'],
  [/화면|모니터|UI|프론트|front/i, 'monitor'],
  [/시간|지연|latency|타이머|대기/i, 'timer'],
  [/성능|속도|빠르|performance/i, 'gauge'],
  [/요청|request|응답|response/i, 'arrow-right-left'],
  [/다시|재시도|retry|갱신|동기화|sync/i, 'refresh'],
  [/보호|방어|막아|막았/i, 'shield'],
];

export const iconFor = (label: string, name: string | null): LucideIcon | null => {
  if (name && ICONS[name]) return ICONS[name];
  const hit = GUESS.find(([re]) => re.test(label));
  return hit ? ICONS[hit[1]] : null;
};

export const IconBadge: React.FC<{Icon: LucideIcon | null; color: string; size: number; on: number}> = ({Icon, color, size, on}) => (
  <div style={{width: size, height: size, borderRadius: size * 0.32, display: 'grid', placeItems: 'center', flex: 'none',
    background: on > 0.5 ? color : `${color}1a`, transition: 'none'}}>
    {Icon ? <Icon size={size * 0.54} color={on > 0.5 ? '#ffffff' : color} strokeWidth={2.2} /> : null}
  </div>
);
