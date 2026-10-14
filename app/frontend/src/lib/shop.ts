import type { CSSProperties } from 'react'
import { api } from './api'
import { getLocale, t } from './i18n'

/** 크레딧 상점과 블로그 꾸미기 (#268). 서버 ShopService와 같은 모양 */
export type ItemKind = 'THEME' | 'FRAME' | 'BANNER' | 'EFFECT'
export const ITEM_KINDS: ItemKind[] = ['THEME', 'FRAME', 'BANNER', 'EFFECT']

/** 티어 (1 아이언 ~ 9 챌린저). key는 서버 ShopService.TIER_PRESETS와 같은 모양 이름이다. 늘리면 둘 다 고친다 */
export const TIERS = [
  { tier: 1, key: 'iron', name: '아이언' },
  { tier: 2, key: 'bronze', name: '브론즈' },
  { tier: 3, key: 'silver', name: '실버' },
  { tier: 4, key: 'gold', name: '골드' },
  { tier: 5, key: 'platinum', name: '플래티넘' },
  { tier: 6, key: 'diamond', name: '다이아' },
  { tier: 7, key: 'master', name: '마스터' },
  { tier: 8, key: 'grandmaster', name: '그랜드마스터' },
  { tier: 9, key: 'challenger', name: '챌린저' },
] as const
export const TIER_PRESETS: readonly string[] = TIERS.map((x) => x.key)
/** 서버 ShopService.EFFECT_PRESETS와 같은 이름 */
export const EFFECT_PRESETS = ['glow', 'stardust', 'radiance'] as const

export interface ShopItem {
  id: number
  kind: ItemKind
  tier: number
  name: string
  description: string
  price: number
  value: string
  imageUrl: string | null
  active: boolean
  sort: number
  owned: boolean
  applied: boolean
  /** 아래 티어가 없어서 아직 못 사요 */
  locked: boolean
  /** 사려면 가져야 하는 티어와 종류(null이면 어느 종류든) */
  needTier: number | null
  needKind: ItemKind | null
}

export interface Shop {
  shopBalance: number
  investBalance: number
  items: ShopItem[]
}

export interface Look {
  itemId: number
  name: string
  tier: number
  value: string
  imageUrl: string | null
}

export interface Decorations {
  theme: Look | null
  frame: Look | null
  banner: Look | null
  effect: Look | null
}

export interface ItemInput {
  kind?: ItemKind
  tier?: number
  name?: string
  description?: string
  price?: number
  value?: string
  imageUrl?: string
  active?: boolean
  sort?: number
}

export const loadShop = () => api<Shop>('/api/shop')
export const buyItem = (id: number) => api<Shop>(`/api/shop/items/${id}/purchase`, { method: 'POST' })
export const applyItem = (id: number) => api<Shop>(`/api/me/decorations/${id}`, { method: 'PUT' })
export const removeDecoration = (kind: ItemKind) => api<Shop>(`/api/me/decorations/${kind}`, { method: 'DELETE' })
export const loadDecorations = (handle: string) => api<Decorations>(`/api/members/${encodeURIComponent(handle)}/decorations`)
export const adminShopItems = () => api<ShopItem[]>('/api/admin/shop/items')
export const createShopItem = (body: ItemInput) => api<ShopItem>('/api/admin/shop/items', { method: 'POST', body })
export const updateShopItem = (id: number, body: ItemInput) => api<ShopItem>(`/api/admin/shop/items/${id}`, { method: 'PUT', body })

export function kindLabel(kind: ItemKind): string {
  return kind === 'THEME' ? t('테마 색') : kind === 'FRAME' ? t('프로필 테두리') : kind === 'BANNER' ? t('블로그 표지') : t('빛·반짝임 효과')
}

/** 탭·안내에 쓰는 짧은 이름 */
export function kindShort(kind: ItemKind): string {
  return kind === 'THEME' ? t('테마 색') : kind === 'FRAME' ? t('테두리') : kind === 'BANNER' ? t('표지') : t('효과')
}

/** 티어 이름. 범위 밖이면 빈 글자 */
export function tierName(tier: number): string {
  const x = TIERS[tier - 1]
  return x ? t(x.name) : ''
}

export function tierKey(tier: number): string {
  return TIERS[tier - 1]?.key ?? 'iron'
}

/** 티어 기본 가격: 100 × 10^(티어-1) 크레딧. 서버 ShopService.defaultPrice와 같다 */
export function tierPrice(tier: number): number {
  return 100 * 10 ** (tier - 1)
}

/** 큰 가격을 짧게: 100억·1,000만 (한국어 1만 아래는 그대로 쉼표) */
export function shortCredits(n: number): string {
  const locale = getLocale()
  if (locale === 'ko' && n < 10_000) return n.toLocaleString('ko-KR')
  return new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(n)
}

/** 잠긴 아이템의 안내: "아이언 테두리부터 사야 열려요" */
export function needText(item: Pick<ShopItem, 'needTier' | 'needKind'>): string {
  if (item.needTier == null) return ''
  const what = item.needKind ? t('{0} {1}', { 0: tierName(item.needTier), 1: kindShort(item.needKind) }) : t('{0} 티어 아이템', { 0: tierName(item.needTier) })
  return t('{0}부터 사야 열려요', { 0: what })
}

const HEX = /^#[0-9a-fA-F]{6}$/

/** 테마 색 꾸미기를 블로그 화면에 입힌다. 서버가 #RRGGBB만 받지만 화면에서도 한 번 더 거른다 */
export function themeStyle(look: Look | null | undefined): CSSProperties | undefined {
  if (!look || !HEX.test(look.value)) return undefined
  return { '--blog-theme': look.value } as CSSProperties
}

/** 프로필 테두리: data-frame 값(티어 모양 이름 또는 color)과 색 */
export function frameProps(look: Look | null | undefined): { 'data-frame'?: string; style?: CSSProperties } {
  if (!look) return {}
  if (HEX.test(look.value)) return { 'data-frame': 'color', style: { '--frame-color': look.value } as CSSProperties }
  return TIER_PRESETS.includes(look.value) ? { 'data-frame': look.value } : {}
}

/** 블로그 표지: 티어 표지 이름, 사진이면 배경 사진 */
export function bannerProps(look: Look | null | undefined): { 'data-banner'?: string; style?: CSSProperties; image: boolean } {
  if (!look) return { image: false }
  const preset = TIER_PRESETS.includes(look.value) ? look.value : undefined
  if (look.imageUrl && /^(https?:\/\/|\/)[^\s"'()\\]+$/.test(look.imageUrl)) {
    return { 'data-banner': preset, style: { backgroundImage: `url("${look.imageUrl}")` }, image: true }
  }
  return preset ? { 'data-banner': preset, image: false } : { image: false }
}

/** 빛·반짝임 효과: 블로그 머리말에 data-effect로 건다 */
export function effectName(look: Look | null | undefined): string | undefined {
  return look && (EFFECT_PRESETS as readonly string[]).includes(look.value) ? look.value : undefined
}
