import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react'
import { cropAt, PROFILE_SIZE, type Crop } from '../lib/image'
import { t } from '../lib/i18n'

/**
 * 정사각형 자르기 (005 FR-011): 끌거나 화살표 키로 위치를, 막대·휠로 확대를 조정한다. 미리보기는 실제로 올라갈 256×256 그대로다.
 */
export function ImageCropper({ image, onApply, onCancel, busy }: {
  image: ImageBitmap
  onApply: (crop: Crop) => void
  onCancel: () => void
  busy?: boolean
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [zoom, setZoom] = useState(1)
  const [center, setCenter] = useState({ x: image.width / 2, y: image.height / 2 })
  const drag = useRef<{ x: number; y: number } | null>(null)
  const crop = cropAt(image.width, image.height, center.x, center.y, zoom)

  useEffect(() => {
    const ctx = canvas.current?.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, PROFILE_SIZE, PROFILE_SIZE)
    ctx.drawImage(image, crop.x, crop.y, crop.size, crop.size, 0, 0, PROFILE_SIZE, PROFILE_SIZE)
  }, [image, crop.x, crop.y, crop.size])

  // React의 onWheel은 passive라 페이지 스크롤을 막지 못한다. 직접 non-passive로 건다
  useEffect(() => {
    const el = canvas.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      setZoom((z) => Math.min(Math.max(z * (e.deltaY < 0 ? 1.1 : 1 / 1.1), 1), 8))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  const onDown = (e: ReactPointerEvent<HTMLCanvasElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId)
    drag.current = { x: e.clientX, y: e.clientY }
  }
  const onMove = (e: ReactPointerEvent<HTMLCanvasElement>) => {
    if (!drag.current) return
    const rect = e.currentTarget.getBoundingClientRect()
    const scale = crop.size / rect.width
    const dx = (e.clientX - drag.current.x) * scale
    const dy = (e.clientY - drag.current.y) * scale
    drag.current = { x: e.clientX, y: e.clientY }
    // 지금 보이는(당겨진) 가운데에서 움직여야 가장자리에서 멈춘 뒤 바로 되돌아온다
    setCenter({ x: crop.x + crop.size / 2 - dx, y: crop.y + crop.size / 2 - dy })
  }
  const onUp = () => { drag.current = null }
  // 화살표 키는 끌기와 같은 쪽으로 사진을 옮긴다 (한 번에 보이는 폭의 1/20)
  const onKey = (e: ReactKeyboardEvent<HTMLCanvasElement>) => {
    const step = crop.size / 20
    const directions: Record<string, readonly [number, number] | undefined> = {
      ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1],
    }
    const d = directions[e.key]
    if (!d) return
    e.preventDefault()
    setCenter({ x: crop.x + crop.size / 2 - d[0] * step, y: crop.y + crop.size / 2 - d[1] * step })
  }

  return (
    <div className="cropper" role="group" aria-label={t('사진 자르기')}>
      {/* 캔버스는 정사각형 그대로 그리고, 위에 원 바깥을 어둡게 덮고 둘레 선을 그어 실제로 보일 원을 드러낸다 */}
      <div className="cropper-stage" title={t('원 안이 프로필 사진으로 보여요')}>
        <canvas ref={canvas} width={PROFILE_SIZE} height={PROFILE_SIZE} className="cropper-canvas"
                tabIndex={0} role="img" aria-label={t('사진 위치 (화살표 키로 옮기기)')} onKeyDown={onKey}
                onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp} />
        <span className="cropper-mask" aria-hidden="true" />
      </div>
      <label className="cropper-zoom">
        <span className="small muted">{t('확대')}</span>
        <input type="range" min={1} max={8} step={0.01} value={zoom} onChange={(e) => setZoom(Number(e.target.value))} aria-label={t('확대')} />
      </label>
      <p className="small muted">{t('원 안이 프로필 사진으로 보여요.')} {t('끌거나 화살표 키로 위치를 맞추세요. 256×256 크기로 바뀌고 촬영 위치 같은 사진 정보는 지워져요.')}</p>
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onApply(crop)}>{busy ? t('올리는 중…') : t('이 사진 쓰기')}</button>
        <button type="button" className="btn btn-text" disabled={busy} onClick={onCancel}>{t('취소')}</button>
      </div>
    </div>
  )
}
