import type { CSSProperties } from 'react'
import { api } from './api'
import { t } from './i18n'

/** 포인트 상점과 블로그 꾸미기 (#268). 서버 ShopService와 같은 모양 */
export type ItemKind = 'THEME' | 'FRAME' | 'BANNER'
export const ITEM_KINDS: ItemKind[] = ['THEME', 'FRAME', 'BANNER']
/** 서버 ShopService.FRAME_PRESETS·BANNER_PRESETS와 같은 이름. 늘리면 둘 다 고친다 */
export const FRAME_PRESETS = ['gold', 'rainbow', 'aurora'] as const
export const BANNER_PRESETS = ['sunset', 'ocean', 'night', 'forest'] as const

export interface ShopItem {
  id: number
  kind: ItemKind
  name: string
  description: string
  price: number
  value: string
  imageUrl: string | null
  active: boolean
  sort: number
  owned: boolean
  applied: boolean
}

export interface Shop {
  shopBalance: number
  investBalance: number
  items: ShopItem[]
}

export interface Look {
  itemId: number
  name: string
  value: string
  imageUrl: string | null
}

export interface Decorations {
  theme: Look | null
  frame: Look | null
  banner: Look | null
}

export interface ItemInput {
  kind?: ItemKind
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
  return kind === 'THEME' ? t('테마 색') : kind === 'FRAME' ? t('프로필 테두리') : t('블로그 표지')
}

const HEX = /^#[0-9a-fA-F]{6}$/

/** 테마 색 꾸미기를 블로그 화면에 입힌다. 서버가 #RRGGBB만 받지만 화면에서도 한 번 더 거른다 */
export function themeStyle(look: Look | null | undefined): CSSProperties | undefined {
  if (!look || !HEX.test(look.value)) return undefined
  return { '--blog-theme': look.value } as CSSProperties
}

/** 프로필 테두리: data-frame 값과 색 */
export function frameProps(look: Look | null | undefined): { 'data-frame'?: string; style?: CSSProperties } {
  if (!look) return {}
  if (HEX.test(look.value)) return { 'data-frame': 'color', style: { '--frame-color': look.value } as CSSProperties }
  return (FRAME_PRESETS as readonly string[]).includes(look.value) ? { 'data-frame': look.value } : {}
}

/** 블로그 표지: 정해 둔 그러데이션 이름, 사진이면 배경 사진 */
export function bannerProps(look: Look | null | undefined): { 'data-banner'?: string; style?: CSSProperties; image: boolean } {
  if (!look) return { image: false }
  const preset = (BANNER_PRESETS as readonly string[]).includes(look.value) ? look.value : undefined
  if (look.imageUrl && /^(https?:\/\/|\/)[^\s"'()\\]+$/.test(look.imageUrl)) {
    return { 'data-banner': preset, style: { backgroundImage: `url("${look.imageUrl}")` }, image: true }
  }
  return preset ? { 'data-banner': preset, image: false } : { image: false }
}
