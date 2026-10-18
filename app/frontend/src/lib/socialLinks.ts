import { t } from './i18n'
/**
 * 블로그 소셜 정보 (spec 043). 서버는 정리한 값만 보낸다: 이메일 주소, GitHub·X·Facebook 아이디, http(s) 홈페이지 주소.
 * 쇼츠 채널(spec 103): YouTube는 youtube.com/ 뒤 경로(@핸들 또는 channel/UC…), Instagram·TikTok은 아이디.
 */
export interface SocialLinks {
  email?: string
  github?: string
  x?: string
  facebook?: string
  homepage?: string
  youtube?: string
  instagram?: string
  tiktok?: string
}

export type SocialKind = keyof SocialLinks

/** 블로그 머리와 설정 화면에 보이는 순서 */
export const SOCIAL_FIELDS: { kind: SocialKind; label: string; placeholder: string; href: (v: string) => string; tip?: (v: string) => string }[] = [
  { kind: 'email', label: t('이메일'), placeholder: 'me@example.com', href: (v) => `mailto:${v}` },
  { kind: 'github', label: 'GitHub', placeholder: t('아이디 또는 github.com 주소'), href: (v) => `https://github.com/${v}` },
  { kind: 'x', label: 'X', placeholder: t('아이디 또는 x.com 주소'), href: (v) => `https://x.com/${v}` },
  { kind: 'facebook', label: 'Facebook', placeholder: t('아이디 또는 facebook.com 주소'), href: (v) => `https://www.facebook.com/${v}` },
  { kind: 'homepage', label: t('홈페이지'), placeholder: 'https://', href: (v) => v },
  { kind: 'youtube', label: 'YouTube', placeholder: t('@핸들 또는 youtube.com 채널 주소'), href: youtubeHref, tip: (v) => t('YouTube 채널 {0}', { 0: v.startsWith('@') ? v : t('바로 가기') }) },
  { kind: 'instagram', label: 'Instagram', placeholder: t('아이디 또는 instagram.com 주소'), href: (v) => `https://www.instagram.com/${v}/`, tip: (v) => t('Instagram 채널 @{0}', { 0: v }) },
  { kind: 'tiktok', label: 'TikTok', placeholder: t('아이디 또는 tiktok.com 주소'), href: (v) => `https://www.tiktok.com/@${v}`, tip: (v) => t('TikTok 채널 @{0}', { 0: v }) },
]

/** 한글 핸들은 주소에 그대로 넣지 않고 인코딩한다 */
function youtubeHref(v: string): string {
  return v.startsWith('@') ? `https://www.youtube.com/@${encodeURIComponent(v.slice(1))}` : `https://www.youtube.com/${v}`
}

/** 쇼츠를 올리는 플랫폼마다 블로그 소셜 정보의 채널 칸 (spec 103) */
export const CHANNEL_KIND = { YOUTUBE: 'youtube', INSTAGRAM: 'instagram', TIKTOK: 'tiktok' } as const satisfies Record<string, SocialKind>

/** 소셜 정보 값으로 그 칸의 링크 주소 */
export function socialHref(kind: SocialKind, value: string): string {
  return SOCIAL_FIELDS.find((f) => f.kind === kind)!.href(value)
}
