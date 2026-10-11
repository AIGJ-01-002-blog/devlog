import { useEffect } from 'react'
import { CopyCode } from '../components/CopyCode'
import { loginPath, useAuth } from '../lib/auth'
import { claudeCodeCommand, codexConfig, MCP_TOOLS, mcpEndpoint, mcpJsonConfig } from '../lib/mcp'
import { Link } from '../lib/router'
import { t, tNodes } from '../lib/i18n'

/** AI에 devlog 연결하기 (051): MCP가 무엇인지, 무엇을 할 수 있는지, 어떻게 연결하는지. */
export function McpPage() {
  const { me } = useAuth()
  const origin = window.location.origin
  useEffect(() => { document.title = t('AI에 devlog 연결하기 - devlog') }, [])
  return (
    <main className="container narrow mcp-page">
      <p className="home-hero-eyebrow"><span className="home-hero-dot" aria-hidden="true" />devlog MCP</p>
      <h1 className="mcp-title">{t('당신의 AI가 개발 일지를 씁니다')}</h1>
      <p className="mcp-lead">
        
        {tNodes('MCP는 Claude·ChatGPT·Cursor·Codex 같은 AI 도구에 바깥 서비스를 "도구"로 꽂는 표준이에요. devlog를 연결해 두면 코딩을 마친 뒤{0} 한마디로, AI가 그날 대화와 커밋을 정리해 내 devlog에 임시글로 올려 줘요. 나는 읽어 보고 {1}만 누르면 돼요.', { 0: <b>  {t('"오늘 개발 일지 써 줘"')}</b>, 1: <b>{t('[발행]')}</b> })}
      </p>

      <ol className="mcp-steps">
        <li><b>{t('토큰 만들기')}</b><span>{t('설정 › AI 연결에서 개인 접근 토큰을 만들어요. 읽기·쓰기 범위와 만료일을 고르고, 언제든 폐기할 수 있어요. Claude 앱 커넥터와 ChatGPT는 토큰 없이 로그인으로 연결해요.')}</span></li>
        <li><b>{t('AI에 연결하기')}</b><span>{t('아래 명령이나 설정을 내 AI 도구에 붙여 넣어요.')}</span></li>
        <li><b>{t('"개발 일지 써 줘"')}</b><span>{t('AI가 임시글을 만들고 링크를 알려 줘요. 확인하고 발행하면 끝.')}</span></li>
      </ol>

      <p className="mcp-status">
        {me?.authenticated
          ? <Link to="/settings#ai" className="btn btn-primary">{t('설정에서 토큰 만들기')}</Link>
          : <>{tNodes('로그인한 회원 누구나 쓸 수 있어요. {0}', { 0: <Link to={loginPath('/mcp')}>{t('로그인하고 토큰 만들기')}</Link> })}</>}
      </p>

      <h2>{t('연결하기')}</h2>
      <h3>{t('Claude Code (터미널)')}</h3>
      <CopyCode label={t('Claude Code 연결 명령')} code={claudeCodeCommand(origin)} />
      <h3>{t('Claude 데스크톱 · Cursor (설정 파일)')}</h3>
      <CopyCode label={t('MCP 설정')} code={mcpJsonConfig(origin)} />
      <h3>{t('Codex (터미널)')}</h3>
      <CopyCode label={t('Codex 설정 (~/.codex/config.toml)')} code={codexConfig(origin)} />
      <p className="muted small">{tNodes('토큰은 설정 파일 대신 환경 변수 {0}에 넣어 두세요.', { 0: <code>DEVLOG_TOKEN</code> })}</p>
      <h3>{t('Claude 앱 (커넥터)')}</h3>
      <p className="mcp-chatgpt">
        
        {tNodes('Claude 앱은 토큰 대신 {0}으로 연결해요. claude.ai나 Claude 데스크톱의 설정 › 커넥터에서 {1}를 누르고 아래 서버 주소를 넣은 뒤 [연결]을 누르세요. devlog 동의 화면에서 [허용]을 누르면 끝이에요. 연결한 커넥터는 설정 › AI 연결에 "로그인 연결"로 보여요.', { 0: <b>{t('devlog 로그인')}</b>, 1: <b>{t('사용자 지정 커넥터 추가')}</b> })}
      </p>
      <p className="muted small">
        
        {t('모바일 앱에서는 새 커넥터를 추가할 수 없고, claude.ai나 데스크톱에서 추가한 커넥터를 그대로 쓸 수 있어요. 사용자 지정 커넥터를 쓸 수 있는 요금제는 Claude 쪽 정책을 따르고, Team·Enterprise는 조직 관리자가 먼저 켜 두어야 해요.')}
      </p>
      <h3>{t('ChatGPT (커넥터)')}</h3>
      <p className="mcp-chatgpt">
        
        {tNodes('ChatGPT는 토큰 대신 {0}으로 연결해요. chatgpt.com의 설정 › 플러그인에서 [추가] › 사용자 지정 MCP 서버 추가를 누르고, 아래 주소를 넣고 인증 방식은 {1}를 고르세요. 만든 플러그인에서 [연결]을 누르고 devlog 동의 화면에서 [허용]을 누르면 끝이에요.', { 0: <b>{t('devlog 로그인')}</b>, 1: <b>OAuth</b> })}
      </p>
      <CopyCode label={t('MCP 서버 주소')} code={mcpEndpoint(origin)} />
      <p className="muted small">{t('ChatGPT에서 쓰기 도구까지 되는지는 요금제마다 달라요(2026년 10월 기준 일부 요금제는 읽기만). 연결은 설정 › AI 연결에서 언제든 끊을 수 있어요.')}</p>
      <p className="muted small">{t('토큰은 비밀번호처럼 다뤄 주세요. 저장소나 글에 붙여 넣지 말고, 새면 설정에서 바로 폐기하세요.')}</p>

      <h2>{t('AI가 쓸 수 있는 도구')}</h2>
      <table className="mcp-tools">
        <thead><tr><th scope="col">{t('도구')}</th><th scope="col">{t('하는 일')}</th><th scope="col">{t('권한')}</th></tr></thead>
        <tbody>
          {MCP_TOOLS.map((tool) => (
            <tr key={tool.name}><td><code>{tool.name}</code></td><td>{tool.does}</td><td>{tool.aiPublish ? t('{0} · 허용했을 때만', { 0: tool.scope }) : tool.diary ? t('{0} · 일기를 켰을 때만', { 0: tool.scope }) : tool.scope}</td></tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">
        {tNodes('{0}·{1}는 설정 › AI 연결에서 {2}을 켰을 때만 AI에게 보이고 쓸 수 있어요. 기본은 꺼져 있어요.', { 0: <code>publish_post</code>, 1: <code>delete_post</code>, 2: <b>{t('AI가 발행·삭제하도록 허용')}</b> })}
      </p>

      <h2>{t('안심하고 쓰도록')}</h2>
      <ul className="mcp-safety">
        <li><b>{t('발행은 기본으로 내가 해요.')}</b> {t('AI는 임시글과 "발행 대기"까지만 만들 수 있어요.')}</li>
        <li>{tNodes('설정 › AI 연결에서 {0}을 켠 경우에만 AI가 글을 바로 발행하거나 삭제할 수 있어요. 삭제는 웹에서 지울 때와 같아 휴지통에서 30일 안에 복구할 수 있어요. 이 설정은 로그인한 웹 화면에서만 바꿀 수 있어 AI가 스스로 켤 수 없어요.', { 0: <b>{t('AI가 발행·삭제하도록 허용')}</b> })}</li>
        <li>{tNodes('AI가 {0} "고치는 중"으로만 저장돼요. 내가 편집 화면에서 [다시 발행]을 눌러야(또는 발행을 허용했다면 AI에게 말해야) 독자에게 보여요.', { 0: <b>{t('발행한 글을 고치면')}</b> })}</li>
        <li>{tNodes('AI는 한 주제가 끝났다고 보면 {0}해요. AI 제안 알림을 켜 두면(기본 켜짐) 제안이 올 때 알려 드려요. 쓰라고 하기 전에는 글을 만들지 않고, 제안은 내 글 관리에서 임시글로 만들거나 넘길 수 있어요.', { 0: <b>{t('글을 쓰기 전에 제목과 범위를 제안')}</b> })}</li>
        <li>{tNodes('개발 일지에 주제가 여럿이면 AI가 {0} 써요. 한 번에 3편까지이고, 나머지는 제안으로 남겨요. 나눠 쓴 글을 시리즈로 묶지는 않아요.', { 0: <b>{t('주제마다 임시글을 나눠')}</b> })}</li>
        <li>{tNodes('구조도·흐름도는 AI가 {0}으로 넣으면 글 화면이 그림으로 그려 줘요. 어느 AI 앱에서 쓰든 같은 그림이 나와요.', { 0: <b>{t('Mermaid 코드 블록')}</b> })}</li>
        <li>{tNodes('"포트폴리오용으로 써 줘"라고 하면 AI가 기술 소개·프로젝트 설명·사진 위주로 쓰고, {0}을 먼저 보여 주고 확인받은 뒤에 써요.', { 0: <b>{t('우리 팀이 한 일과 내 역할')}</b> })}</li>
        <li>{tNodes('설정 › AI 연결에서 {0}를 켜면 AI가 작업 메모를 남기고, 매일 고른 시각(한국 시간, 기본 자정)에 메모가 {1}로 묶여요. 메모는 내가 쓰는 일기처럼 "~했어요" 말투로 남기게 해서 주제마다 한 문단으로 이어 붙여요. 일기는 임시글로 만들고, AI 발행을 허용했으면 바로 발행해요. AI를 쓰지 않은 날은 만들지 않아요. 끄면 아직 묶지 않은 메모도 지워요.', { 0: <b>{t('AI 일기 쓰기')}</b>, 1: <b>{t('일기')}</b> })}</li>
        <li>{tNodes('AI가 올리는 사진도 웹과 같아요. 긴 변 1920px로 줄이고 {0} 내 사진 저장 공간(1GB)에 올려요.', { 0: <b>{t('위치 같은 사진 정보를 지운 뒤')}</b> })}</li>
        <li>{tNodes('AI가 읽는 글도 {0}를 따라요. 남의 비공개·친구 공개 글은 읽을 수 없어요.', { 0: <b>{t('웹과 같은 공개 범위')}</b> })}</li>
        <li>{tNodes('토큰마다 {0}이 있고, 회원마다 {1}(1분에 60번, 글쓰기는 한 시간에 30번)이 있어요.', { 0: <b>{t('범위·만료일')}</b>, 1: <b>{t('요청 수 제한')}</b> })}</li>
        <li>{tNodes('글쓰기 도구는 {0}만 쓸 수 있어요.', { 0: <b>{t('이메일 인증을 마친 회원')}</b> })}</li>
        <li>{tNodes('AI가 {0}로 보낸 버그 신고는 {1} 읽어요. 고치면 답변과 고친 버전이 {2}에 보여요.', { 0: <code>report_bug</code>, 1: <b>{t('운영자만')}</b>, 2: <Link to="/support?tab=mine">{t('문의·신고')}</Link> })}</li>
        <li>{t('글을 만드는 AI 비용은 내 AI 도구가 써요. devlog는 받은 글을 저장할 뿐이에요.')}</li>
      </ul>

      <div className="mcp-cta">
        {me?.authenticated
          ? <Link to="/settings#ai" className="btn btn-primary btn-lg">{t('토큰 만들고 연결하기')}</Link>
          : <Link to={loginPath('/mcp')} className="btn btn-primary btn-lg">{t('가입하고 연결하기')}</Link>}
        <Link to="/" className="btn btn-outline btn-lg">{t('글 둘러보기')}</Link>
      </div>
    </main>
  )
}
