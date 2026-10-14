import { useEffect, useState } from 'react'
import { NavIcon } from './NavIcons'
import { t } from '../lib/i18n'

const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

/** 화면 한 장 반 넘게 내려가면 오른쪽 아래에 [맨 위로]가 나온다 (spec 097 FR-010). 휴대폰은 아래 탭 위에 둔다 */
export function ScrollTopButton() {
  const [shown, setShown] = useState(false)
  useEffect(() => {
    let frame = 0
    const update = () => { frame = 0; setShown(window.scrollY > window.innerHeight * 1.5) }
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    update()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      if (frame) cancelAnimationFrame(frame)
    }
  }, [])
  if (!shown) return null
  return (
    <button type="button" className="scroll-top" aria-label={t('맨 위로')} data-tip={t('맨 위로')}
            onClick={() => window.scrollTo({ top: 0, behavior: reducedMotion() ? 'auto' : 'smooth' })}>
      <NavIcon name="arrowUp" size={20} />
    </button>
  )
}
