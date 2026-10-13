import { api, apiFile } from './api'
import { t } from './i18n'

/** 프로필 사진 만들기 (005 FR-011·FR-018): 브라우저에서 정사각형으로 잘라 256×256 한 가지 크기로 다시 그린다. */
export const PROFILE_SIZE = 256
export const MAX_SOURCE_BYTES = 10 * 1024 * 1024
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']

/** 원본 사진 좌표의 정사각형 자르기 영역. */
export interface Crop { x: number; y: number; size: number }

export interface Uploaded { id: number; url: string }

/** 고를 수 있는 파일인지. 문제가 있으면 안내 문구. */
export function checkSourceFile(file: { type: string; size: number }): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) return t('jpg·png·gif·webp 사진만 올릴 수 있어요.')
  if (file.size > MAX_SOURCE_BYTES) return t('사진은 10MB까지 고를 수 있어요.')
  return null
}

/** 가운데 정사각형, 확대 1배. */
export function centerCrop(width: number, height: number): Crop {
  const size = Math.min(width, height)
  return { x: (width - size) / 2, y: (height - size) / 2, size }
}

/**
 * 가운데(cx, cy)와 확대(zoom ≥ 1)로 자르기 영역을 만든다. 영역이 사진 밖으로 나가지 않게 가운데를 당긴다.
 */
export function cropAt(width: number, height: number, cx: number, cy: number, zoom: number): Crop {
  const z = Math.min(Math.max(zoom, 1), 8)
  const size = Math.min(width, height) / z
  const x = Math.min(Math.max(cx - size / 2, 0), width - size)
  const y = Math.min(Math.max(cy - size / 2, 0), height - size)
  return { x, y, size }
}

/**
 * 잘라서 256×256으로 다시 그린다. 캔버스로 다시 그리면 위치 같은 사진 정보(EXIF)는 남지 않는다.
 * GIF는 첫 장면만 그려진다. webp를 못 만드는 브라우저는 png로 만든다.
 */
export async function renderSquare(source: CanvasImageSource, crop: Crop): Promise<Blob> {
  const canvas = document.createElement('canvas')
  canvas.width = PROFILE_SIZE
  canvas.height = PROFILE_SIZE
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas')
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(source, crop.x, crop.y, crop.size, crop.size, 0, 0, PROFILE_SIZE, PROFILE_SIZE)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/webp', 0.9))
  if (blob && blob.type === 'image/webp') return blob
  const png = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!png) throw new Error('encode')
  return png
}

/** 파일을 그릴 수 있는 사진으로 푼다. 휴대폰 사진의 회전 정보는 반영한다. */
export async function decodeFile(file: Blob): Promise<ImageBitmap> {
  return createImageBitmap(file, { imageOrientation: 'from-image' })
}

export function uploadProfileImage(blob: Blob): Promise<Uploaded> {
  return api<Uploaded>('/api/me/profile-image', { method: 'POST', rawBody: blob })
}

/** 소셜 사진을 256 이상 크기로 받도록 주소의 크기 값을 바꾼다. 허용된 다섯 곳이 아니면 null (FR-019). */
export function socialAvatarSource(url: string | null | undefined): string | null {
  if (!url) return null
  let u: URL
  try {
    u = new URL(url)
  } catch {
    return null
  }
  if (u.protocol !== 'https:' || u.port || u.username) return null
  if (u.hostname === 'avatars.githubusercontent.com') {
    u.searchParams.set('s', '512')
    return u.toString()
  }
  // 카카오 사진은 주소에 크기 값이 없고 640px 그대로 온다
  if (u.hostname === 'k.kakaocdn.net') return u.toString()
  // Facebook 사진은 서버가 512px로 요청한 주소가 온다
  if (u.hostname === 'platform-lookaside.fbsbx.com') return u.toString()
  // 네이버 사진은 회원이 올린 원본 크기 그대로 온다. 네이버 기본 사진(ssl.pstatic.net)은 받지 않는다
  if (u.hostname === 'phinf.pstatic.net') return u.toString()
  if (u.hostname === 'lh3.googleusercontent.com') {
    // Google 사진 주소 끝의 "=s96-c" 같은 크기 지정을 512로 바꾼다
    const base = u.pathname.replace(/=[^/]*$/, '')
    return `${u.origin}${base}=s512-c`
  }
  return null
}

/** 소셜 사진을 받아 온다. crossOrigin으로 받아야 캔버스에서 다시 그릴 수 있다. 제한 시간을 넘기면 실패. */
export function loadRemoteImage(url: string, timeoutMs = 5000): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    const timer = window.setTimeout(() => {
      img.src = ''
      reject(new Error('timeout'))
    }, timeoutMs)
    img.crossOrigin = 'anonymous'
    img.referrerPolicy = 'no-referrer'
    img.onload = () => {
      window.clearTimeout(timer)
      resolve(img)
    }
    img.onerror = () => {
      window.clearTimeout(timer)
      reject(new Error('load'))
    }
    img.src = url
  })
}

/** 서버가 가입 대기 중인 사람의 소셜 사진을 대신 받아 준다 (080 FR-012). 사진 서버가 다른 사이트의 가공을 막을 때 쓴다. */
export const SIGNUP_AVATAR_RELAY = '/api/auth/signup/avatar'

async function loadRelayedImage(signal: AbortSignal): Promise<ImageBitmap> {
  const { blob } = await apiFile(SIGNUP_AVATAR_RELAY, signal)
  return createImageBitmap(blob)
}

function sizeOf(img: HTMLImageElement | ImageBitmap): { width: number; height: number } {
  return img instanceof HTMLImageElement ? { width: img.naturalWidth, height: img.naturalHeight } : img
}

/**
 * 가입 전에 소셜 사진을 256×256으로 만들어 둔다 (FR-018). 사진 서버에서 바로 받고, 막히면 서버 대신 받기로 한 번 더 시도한다.
 * 서버 대신 받기는 가입 대기 정보가 있어야 해서 가입 요청보다 먼저 부른다. 제한 시간을 넘기거나 실패하면 null.
 */
export async function prepareSocialAvatar(url: string, timeoutMs = 5000): Promise<Blob | null> {
  const src = socialAvatarSource(url)
  if (!src) return null
  const ctrl = new AbortController()
  let timer: number | undefined
  try {
    const img = await loadRemoteImage(src, timeoutMs).catch(() => {
      timer = window.setTimeout(() => ctrl.abort(), timeoutMs)
      return loadRelayedImage(ctrl.signal)
    })
    const { width, height } = sizeOf(img)
    return await renderSquare(img, centerCrop(width, height))
  } catch {
    return null
  } finally {
    window.clearTimeout(timer)
  }
}

/**
 * 가입 직후 만들어 둔 사진을 올리고 프로필로 연결한다 (FR-018·FR-021). 5초 안에 끝나지 않으면 실패.
 * @return 성공하면 true. 실패해도 가입은 그대로이고 기본 이미지다
 */
export async function attachProfileImage(blob: Blob, timeoutMs = 5000): Promise<boolean> {
  const ctrl = new AbortController()
  const timer = window.setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const up = await api<Uploaded>('/api/me/profile-image', { method: 'POST', rawBody: blob, signal: ctrl.signal })
    await api('/api/me/profile', { method: 'PATCH', body: { profileImageId: up.id }, signal: ctrl.signal })
    return true
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}
