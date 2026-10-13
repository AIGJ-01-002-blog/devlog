import { describe, expect, it, vi } from 'vitest'
import { centerCrop, checkSourceFile, cropAt, prepareSocialAvatar, SIGNUP_AVATAR_RELAY, socialAvatarSource } from './image'

describe('프로필 사진 자르기', () => {
  it('가운데 정사각형으로 시작한다', () => {
    expect(centerCrop(400, 300)).toEqual({ x: 50, y: 0, size: 300 })
    expect(centerCrop(100, 200)).toEqual({ x: 0, y: 50, size: 100 })
  })

  it('확대하면 영역이 작아지고, 사진 밖으로 나가지 않는다', () => {
    expect(cropAt(400, 300, 200, 150, 2)).toEqual({ x: 125, y: 75, size: 150 })
    expect(cropAt(400, 300, 0, 0, 2)).toEqual({ x: 0, y: 0, size: 150 })
    expect(cropAt(400, 300, 999, 999, 2)).toEqual({ x: 250, y: 150, size: 150 })
    expect(cropAt(400, 300, 200, 150, 0.5).size).toBe(300)
  })

  it('jpg·png·gif·webp 10MB까지만 고를 수 있다', () => {
    expect(checkSourceFile({ type: 'image/png', size: 1000 })).toBeNull()
    expect(checkSourceFile({ type: 'image/svg+xml', size: 10 })).toContain('jpg')
    expect(checkSourceFile({ type: 'image/jpeg', size: 10 * 1024 * 1024 + 1 })).toContain('10MB')
  })
})

describe('소셜 사진 주소', () => {
  it('GitHub·Google 사진 서버의 https 주소만 쓰고 크기를 512로 바꾼다', () => {
    expect(socialAvatarSource('https://avatars.githubusercontent.com/u/1?v=4')).toBe('https://avatars.githubusercontent.com/u/1?v=4&s=512')
    expect(socialAvatarSource('https://lh3.googleusercontent.com/a/ACg8=s96-c')).toBe('https://lh3.googleusercontent.com/a/ACg8=s512-c')
    expect(socialAvatarSource('https://lh3.googleusercontent.com/a/ACg8')).toBe('https://lh3.googleusercontent.com/a/ACg8=s512-c')
  })

  it('카카오 사진은 주소 그대로 쓴다', () => {
    expect(socialAvatarSource('https://k.kakaocdn.net/dn/abc/img_640x640.jpg')).toBe('https://k.kakaocdn.net/dn/abc/img_640x640.jpg')
    expect(socialAvatarSource('http://k.kakaocdn.net/dn/abc/img_640x640.jpg')).toBeNull()
  })

  it('Facebook 사진은 주소 그대로 쓴다', () => {
    const fb = 'https://platform-lookaside.fbsbx.com/platform/profilepic/?asid=1&height=512&width=512&ext=1&hash=x'
    expect(socialAvatarSource(fb)).toBe(fb)
    expect(socialAvatarSource('https://scontent.xx.fbcdn.net/v/a.jpg')).toBeNull()
  })

  it('네이버 사진은 회원이 올린 사진만 주소 그대로 쓴다', () => {
    const nv = 'https://phinf.pstatic.net/contact/20231001_1/abc.png'
    expect(socialAvatarSource(nv)).toBe(nv)
    expect(socialAvatarSource('https://ssl.pstatic.net/static/pwe/address/img_profile.png')).toBeNull()
  })

  it('다른 곳이거나 https가 아니면 쓰지 않는다', () => {
    expect(socialAvatarSource('http://avatars.githubusercontent.com/u/1')).toBeNull()
    expect(socialAvatarSource('https://evil.example/a.png')).toBeNull()
    expect(socialAvatarSource('https://avatars.githubusercontent.com:444/u/1')).toBeNull()
    expect(socialAvatarSource('not a url')).toBeNull()
    expect(socialAvatarSource(null)).toBeNull()
  })
})

describe('가입 전 소셜 사진 만들기', () => {
  it('허용되지 않은 주소는 받지도, 서버에 대신 받아 달라고도 하지 않는다', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    expect(await prepareSocialAvatar('https://example.com/a.png')).toBeNull()
    expect(fetchSpy).not.toHaveBeenCalled()
    fetchSpy.mockRestore()
  })

  it('사진 서버에서 바로 받지 못하면 서버가 대신 받은 사진으로 만든다', async () => {
    // 사진 서버가 CORS를 허락하지 않은 것처럼 바로 받기를 실패시킨다
    class FailingImage {
      onerror: (() => void) | null = null
      onload: (() => void) | null = null
      crossOrigin = ''
      referrerPolicy = ''
      set src(_: string) { queueMicrotask(() => this.onerror?.()) }
    }
    vi.stubGlobal('Image', FailingImage)
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      { ok: true, headers: new Headers(), blob: async () => new Blob(['x'], { type: 'image/png' }) } as unknown as Response)
    vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width: 640, height: 640 }))
    const drawn = new Blob(['y'], { type: 'image/webp' })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((cb) => cb(drawn))
    try {
      expect(await prepareSocialAvatar('https://k.kakaocdn.net/dn/a/img_640x640.jpg')).toBe(drawn)
      expect(fetchSpy).toHaveBeenCalledWith(SIGNUP_AVATAR_RELAY, expect.objectContaining({ credentials: 'same-origin' }))
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })
})
