import { useEffect, useRef, useState, type RefObject } from 'react'
import { trapFocus } from '../lib/modalFocus'
import { zoomableImage } from '../lib/postEnhance'
import { t } from '../lib/i18n'

/**
 * 본문 사진 크게 보기 (spec 097 FR-007). 사진을 누르면 화면 가득 원본 크기까지 보이고,
 * 다시 누르거나 Esc·[닫기]로 돌아온다. 키보드로는 사진에 초점을 옮겨 Enter로 연다.
 */
export function ImageZoom({ bodyRef, html }: { bodyRef: RefObject<HTMLElement | null>; html: string }) {
  const [shown, setShown] = useState<{ src: string; alt: string } | null>(null)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const body = bodyRef.current
    if (!body) return
    // 누를 수 있는 사진임을 알린다: 손가락 모양 대신 돋보기, 키보드 초점, 툴팁
    body.querySelectorAll('img').forEach((img) => {
      if (!zoomableImage(img, body)) return
      img.classList.add('zoomable')
      img.tabIndex = 0
      img.setAttribute('role', 'button')
      img.dataset.tip = t('사진 크게 보기')
    })
    const open = (img: HTMLImageElement) => setShown({ src: img.currentSrc || img.src, alt: img.alt })
    const onClick = (e: MouseEvent) => {
      const img = zoomableImage(e.target, body)
      if (img) open(img)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Enter' && e.key !== ' ') return
      const img = zoomableImage(e.target, body)
      if (!img) return
      e.preventDefault()
      open(img)
    }
    body.addEventListener('click', onClick)
    body.addEventListener('keydown', onKey)
    return () => {
      body.removeEventListener('click', onClick)
      body.removeEventListener('keydown', onKey)
    }
  }, [bodyRef, html])

  useEffect(() => (shown && dialogRef.current ? trapFocus(dialogRef.current, () => setShown(null)) : undefined), [shown])

  if (!shown) return null
  return (
    <div className="image-zoom" role="presentation" onClick={() => setShown(null)}>
      <div ref={dialogRef} role="dialog" aria-modal="true" aria-label={shown.alt || t('사진 크게 보기')} className="image-zoom-inner">
        <button type="button" className="image-zoom-close" aria-label={t('닫기')} data-tip={t('닫기 (Esc)')} onClick={() => setShown(null)}>×</button>
        <img src={shown.src} alt={shown.alt} />
        {shown.alt && <p className="image-zoom-caption">{shown.alt}</p>}
      </div>
    </div>
  )
}
