import { Link } from '../lib/router'
import { mcpEndpoint } from '../lib/mcp'
import { CopyCode } from './CopyCode'
import { t, tNodes } from '../lib/i18n'

/**
 * 설정 › AI 연결 위의 연결 안내 (087).
 * 처음 쓰는 회원이 "토큰을 넣는 연결"과 "로그인으로 허용하는 연결"을 헷갈리지 않게,
 * 앱별 순서와 자주 막히는 곳(동의 화면 경고, 목록에 안 보임)을 접었다 펴는 칸으로 보여 준다.
 * 쓰는 앱 하나만 펴 보면 되게 <details>로 나눴다. 휴대폰에서도 길게 늘어지지 않는다.
 */
export function AiConnectGuide() {
  const endpoint = mcpEndpoint(window.location.origin)
  return (
    <div className="ai-guide">
      <h3>{t('어떻게 연결할까요?')}</h3>
      <p className="muted small">{t('AI 앱에 따라 연결 방식이 두 가지예요. 처음이라면 쓰는 앱을 눌러 순서대로 따라 해 보세요.')}</p>

      <div className="ai-guide-kinds">
        <div className="ai-guide-kind">
          <b>{t('로그인 연결')}</b>
          <span className="muted small">{t('ChatGPT·Claude 앱(claude.ai·데스크톱). 토큰 없이 devlog에 로그인하고 동의 화면에서 [허용]을 누르면 돼요.')}</span>
        </div>
        <div className="ai-guide-kind">
          <b>{t('토큰 연결')}</b>
          <span className="muted small">{t('Claude Code·Cursor·Codex처럼 설정 파일이나 명령을 쓰는 도구. 아래에서 토큰을 만들어 붙여 넣어요.')}</span>
        </div>
      </div>

      <details className="ai-guide-step">
        <summary title={t('ChatGPT에 devlog를 연결하는 순서를 펼쳐 봐요')}>{t('ChatGPT에 연결하기')}</summary>
        <ol>
          <li>{tNodes('chatgpt.com(웹)에서 설정 › 플러그인을 열고, 오른쪽 위 [추가] › {0}를 눌러요.', { 0: <b>{t('사용자 지정 MCP 서버 추가')}</b> })}</li>
          <li>{tNodes('이름은 devlog, 연결은 {0}를 고르고 아래 주소를 넣어요. 인증은 {1} 그대로 두고, 고급 OAuth 설정은 건드리지 않아요.', { 0: <b>{t('서버 URL')}</b>, 1: <b>OAuth</b> })}</li>
          <li>{t('"이해했으며 계속 진행하겠습니다"에 체크하고 [플러그인으로 생성]을 눌러요.')}</li>
          <li>{t('만든 플러그인 화면에서 [연결]을 누르면 devlog 동의 화면이 떠요. 돌아갈 주소가 chatgpt.com인지 보고 [허용]을 눌러요.')}</li>
          <li>{t('"연결이 완료되었습니다"가 보이면 끝이에요. 이 화면 목록에 "ChatGPT · 로그인 연결"이 생겨요.')}</li>
        </ol>
        <CopyCode label={t('MCP 서버 주소')} code={endpoint} />
        <p className="muted small">{t('[추가] 메뉴가 안 보이면 설정 › 보안 및 로그인이나 플러그인 아래쪽 고급 설정에서 개발자 모드를 먼저 켜요. ChatGPT 메뉴 이름은 자주 바뀌어요. 개인 요금제(Plus·Pro)는 OpenAI 안내상 읽기·검색까지 지원하고, 임시글 쓰기 같은 쓰기 도구는 Business·Enterprise·Edu에서 정식 지원돼요. 2026년 10월 11일 Plus에서 임시글 만들기·발행·삭제까지 실제로 됐지만, 정책이 바뀌면 막힐 수 있어요. 회사·학교 워크스페이스는 관리자가 개발자 모드를 허용해야 해요(2026년 10월 기준).')}</p>
      </details>

      <details className="ai-guide-step">
        <summary title={t('Claude 앱에 devlog를 연결하는 순서를 펼쳐 봐요')}>{t('Claude 앱(claude.ai·데스크톱)에 연결하기')}</summary>
        <ol>
          <li>{tNodes('claude.ai나 Claude 데스크톱에서 설정 › 커넥터를 열고 {0}를 눌러요.', { 0: <b>{t('사용자 지정 커넥터 추가')}</b> })}</li>
          <li>{t('이름은 devlog, 서버 주소는 아래 주소를 넣고 [추가]를 눌러요.')}</li>
          <li>{t('[연결]을 누르면 devlog 동의 화면이 떠요. 돌아갈 주소가 claude.ai인지 보고 [허용]을 눌러요.')}</li>
          <li>{t('이 화면 목록에 "Claude · 로그인 연결"이 생기면 끝이에요. 모바일 앱은 여기서 추가한 커넥터를 그대로 써요.')}</li>
        </ol>
        <CopyCode label={t('MCP 서버 주소')} code={endpoint} />
      </details>

      <details className="ai-guide-step">
        <summary title={t('토큰으로 devlog를 연결하는 순서를 펼쳐 봐요')}>{t('Claude Code·Cursor·Codex에 연결하기')}</summary>
        <ol>
          <li>{t('아래 토큰 만들기에서 이름(예: 회사 노트북 Claude Code)과 권한, 만료일을 고르고 [토큰 만들기]를 눌러요.')}</li>
          <li>{t('만든 토큰은 이 화면에서 한 번만 보여요. 바로 복사해 두세요.')}</li>
          <li>{tNodes('Claude Code는 함께 나오는 연결 명령을 터미널에 붙여 넣으면 끝이에요. Cursor·Codex 설정은 {0}에 있어요.', { 0: <Link to="/mcp">{t('연결 방법 자세히 보기')}</Link> })}</li>
          <li>{t('토큰은 비밀번호처럼 다뤄요. 저장소·글·채팅에 붙여 넣지 말고, 새면 바로 폐기해요.')}</li>
        </ol>
      </details>

      <details className="ai-guide-step">
        <summary title={t('연결하다 자주 막히는 곳을 펼쳐 봐요')}>{t('자주 묻는 질문')}</summary>
        <dl className="ai-guide-faq">
          <dt>{t('동의 화면에 빨간 경고가 떠요.')}</dt>
          <dd>{t('ChatGPT·Claude 앱이 연결할 때 스스로 이름을 등록해서 나오는 안내예요. 내가 직접 연결을 시작했고 돌아갈 주소가 그 앱(chatgpt.com·claude.ai)이면 [허용]해도 돼요. 아니면 [거부]하세요.')}</dd>
          <dt>{t('연결했는데 이 목록에 안 보여요.')}</dt>
          <dd>{t('동의 화면에서 [허용]을 누르고 앱 화면으로 돌아올 때까지 기다려야 연결이 끝나요. 앱의 플러그인·커넥터 화면에서 [연결]을 다시 누르고, 이 화면을 새로고침해 보세요.')}</dd>
          <dt>{t('토큰 연결과 로그인 연결은 무엇이 달라요?')}</dt>
          <dd>{t('토큰 연결은 내가 만든 토큰을 도구에 직접 넣어서 동의 화면 없이 연결돼요. 로그인 연결은 앱이 devlog 동의 화면을 열고 내가 [허용]해야 연결돼요. 둘 다 이 목록에서 언제든 끊을 수 있어요.')}</dd>
        </dl>
      </details>
    </div>
  )
}
