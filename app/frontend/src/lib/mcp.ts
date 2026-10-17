import { api } from './api'
import { t } from './i18n'

/** devlog MCP 서버 연결 안내 (051·052). 실제 주소는 지금 사이트 주소를 따라간다(로컬 개발이면 localhost). */
export const MCP_PATH = '/api/mcp'
export const TOKEN_PLACEHOLDER = t('<내 토큰>')

export function mcpEndpoint(origin: string): string {
  return new URL(MCP_PATH, origin).href
}

/** Claude Code 터미널에서 한 줄로 연결하는 명령 */
export function claudeCodeCommand(origin: string, token = TOKEN_PLACEHOLDER): string {
  return `claude mcp add --transport http devlog ${mcpEndpoint(origin)} --header "Authorization: Bearer ${token}"`
}

/** Claude 데스크톱·Cursor 같은 설정 파일(JSON)에 넣는 내용 */
export function mcpJsonConfig(origin: string, token = TOKEN_PLACEHOLDER): string {
  return JSON.stringify({ mcpServers: { devlog: { url: mcpEndpoint(origin), headers: { Authorization: `Bearer ${token}` } } } }, null, 2)
}

/**
 * AI가 쓸 수 있는 도구. 기본으로 발행은 사람이 devlog 화면에서 한다.
 * aiPublish 도구는 설정 › AI 연결에서 "AI가 발행·삭제하도록 허용"을 켠 회원에게만 열린다 (053).
 */
export const MCP_TOOLS: ReadonlyArray<{ name: string; does: string; scope: string; aiPublish?: boolean; diary?: boolean }> = [
  { name: 'write_devlog', does: t('오늘 대화·커밋을 정리한 개발 일지를 임시글로 올려요'), scope: t('쓰기') },
  { name: 'create_draft', does: t('새 임시글 만들기 (제목·본문·태그)'), scope: t('쓰기') },
  { name: 'update_draft', does: t('내 글 고치기. 발행한 글은 "고치는 중"으로만 저장되고 다시 발행해야 공개돼요'), scope: t('쓰기') },
  { name: 'request_publish', does: t('"발행 대기"로 올리기. 내가 화면에서 [발행]을 눌러야 공개돼요'), scope: t('쓰기') },
  { name: 'publish_post', does: t('임시글을 바로 발행하거나 고친 글을 다시 발행하기 (웹의 [발행하기]와 같아요)'), scope: t('쓰기'), aiPublish: true },
  { name: 'delete_post', does: t('내 글 삭제하기. 웹에서 지울 때처럼 휴지통으로 가고 30일 안에 복구할 수 있어요'), scope: t('쓰기'), aiPublish: true },
  { name: 'propose_post', does: t('한 주제가 끝나면 글로 남길지 제목·쓸 범위를 제안해요. 내 글 관리에서 임시글로 만들거나 넘겨요'), scope: t('쓰기') },
  { name: 'list_post_proposals', does: t('남긴 글 제안 보기 ("제안한 글 써 줘"라고 할 때 써요)'), scope: t('읽기') },
  { name: 'suggest_topics', does: t('메모·일기·글 제안·임시글에서 전공자용 글감 추천 받기 (이미 쓴 글과 겹치는지도 알려 줘요)'), scope: t('읽기') },
  { name: 'add_note', does: t('작업 단위마다 한두 문장 메모 남기기. 매일 고른 시각에 메모가 일기가 돼요'), scope: t('쓰기'), diary: true },
  { name: 'upload_image', does: t('스크린샷·그림을 올리고 본문에 넣을 이미지 문법 받기 (2MB까지)'), scope: t('쓰기') },
  { name: 'create_image_upload_link', does: t('큰 사진(10MB까지)을 명령줄로 올리는 1회용 주소 만들기'), scope: t('쓰기') },
  { name: 'search_posts', does: t('공개 글 검색. 내 글에서 찾으면 임시글·비공개 글까지 찾아요'), scope: t('읽기') },
  { name: 'get_post', does: t('글 본문(Markdown) 읽기. 웹과 같은 공개 범위를 따라요'), scope: t('읽기') },
  { name: 'list_my_posts', does: t('내 글·임시글 목록'), scope: t('읽기') },
  { name: 'list_tags', does: t('내가 자주 쓴 태그·인기 태그 보기 (태그 제안에 써요)'), scope: t('읽기') },
  { name: 'report_bug', does: t('devlog가 잘못 동작하면 운영자에게 버그 신고하기. 처리 상태와 답변은 [문의·신고]에서 봐요'), scope: t('모든 토큰') },
]

// 개인 접근 토큰 (052). 원문(secret)은 만든 직후 한 번만 받는다.

export type TokenScope = 'READ' | 'WRITE'

export interface AccessToken {
  id: number
  name: string
  prefix: string
  scope: TokenScope
  createdAt: string
  expiresAt: string | null
  lastUsedAt: string | null
  expired: boolean
  /** ChatGPT처럼 OAuth(로그인)로 연결한 앱. expiresAt은 연결 만료 */
  oauth: boolean
  /** 관리자가 이 토큰에만 따로 켠 문의 관리 도구 (#378) */
  adminTools: boolean
}

export interface IssuedToken { token: AccessToken; secret: string }

export const TOKEN_EXPIRY_DAYS = [30, 90, 365] as const
export const TOKEN_NAME_MAX = 40

export const tokensApi = {
  list: () => api<AccessToken[]>('/api/me/tokens'),
  create: (name: string, scope: TokenScope, expiresInDays: number, adminTools = false) =>
    api<IssuedToken>('/api/me/tokens', { method: 'POST', body: { name, scope, expiresInDays, adminTools } }),
  revoke: (id: number) => api<void>(`/api/me/tokens/${id}`, { method: 'DELETE' }),
}

/** "AI가 발행·삭제하도록 허용" (053). 로그인한 웹 화면에서만 바꾼다. 접근 토큰으로는 바꿀 수 없다 */
export interface AiPublishSetting { allowed: boolean }

export const aiPublishApi = {
  get: () => api<AiPublishSetting>('/api/me/ai-publish'),
  set: (allowed: boolean) => api<AiPublishSetting>('/api/me/ai-publish', { method: 'PUT', body: { allowed } }),
}

/**
 * "AI 일기 쓰기" (061·071). 켜면 AI가 add_note로 메모를 남기고 매일 hour시(KST, 0~23)에 일기로 묶여요.
 * AI 발행을 허용한 회원은 일기가 바로 발행돼요.
 */
export interface AiDiarySetting { enabled: boolean; hour: number }

export const aiDiaryApi = {
  get: () => api<AiDiarySetting>('/api/me/ai-diary'),
  /** hour를 빼면 지금 시각을 그대로 둬요 */
  set: (enabled: boolean, hour?: number) =>
    api<AiDiarySetting>('/api/me/ai-diary', { method: 'PUT', body: hour === undefined ? { enabled } : { enabled, hour } }),
}

/** 0 → "자정", 12 → "정오", 22 → "오후 10시" */
export function diaryHourLabel(hour: number): string {
  if (hour === 0) return t('자정')
  if (hour === 12) return t('정오')
  return hour < 12 ? t('오전 {0}시', { 0: hour }) : t('오후 {0}시', { 0: hour - 12 })
}

/** AI가 남긴 글 제안 (061). 아직 정하지 않은 것만 */
export interface AiProposal { id: number; title: string; scope: string; tags: string[]; createdAt: string }

export const aiProposalsApi = {
  list: () => api<AiProposal[]>('/api/me/ai-proposals'),
  draft: (id: number) => api<{ postId: number }>(`/api/me/ai-proposals/${id}/draft`, { method: 'POST' }),
  dismiss: (id: number) => api<void>(`/api/me/ai-proposals/${id}/dismiss`, { method: 'POST' }),
}

/** AI가 만든 임시글의 태그 제안·발행 요청. 없으면 null(204) */
export interface AiHint { tags: string[]; publishRequestedAt: string | null }

export const aiHint = (postId: number) => api<AiHint | undefined>(`/api/posts/${postId}/ai-hint`).then((h) => h ?? null)

// OAuth 동의 화면 (052). ChatGPT처럼 토큰을 붙여 넣을 수 없는 앱이 쓴다.

export interface OAuthView { clientName: string; redirectHost: string; scope: TokenScope }

/** 주소창의 OAuth 매개변수를 동의 API 본문 모양으로 */
export function oauthParams(search: string) {
  const q = new URLSearchParams(search)
  const get = (k: string) => q.get(k) ?? undefined
  return {
    clientId: get('client_id'), redirectUri: get('redirect_uri'), responseType: get('response_type'),
    codeChallenge: get('code_challenge'), codeChallengeMethod: get('code_challenge_method'),
    scope: get('scope'), state: get('state'), resource: get('resource'),
  }
}

export const oauthApi = {
  view: (search: string) => api<OAuthView>(`/api/oauth/authorize${search}`),
}

/** Codex CLI 설정 (~/.codex/config.toml). 토큰은 환경 변수로 넘긴다 */
export function codexConfig(origin: string): string {
  return `[mcp_servers.devlog]\nurl = "${mcpEndpoint(origin)}"\nbearer_token_env_var = "DEVLOG_TOKEN"`
}
