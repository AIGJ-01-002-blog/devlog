import { useEffect, useRef, useState, type RefObject } from 'react'
import { activeIndex, extractToc, TOC_MIN_ITEMS, type TocItem } from '../lib/toc'
import { t } from '../lib/i18n'

const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

/** 헤더 높이만큼 아래를 기준선으로 본다 */
const OFFSET = 96

/**
 * 글 오른쪽 목차 (025 US1). 넓은 화면은 오른쪽에, 좁은 화면은 본문 위에 접어서 보이고(CSS, 097), 스크롤하면 지금 읽는 제목을 강조한다.
 * 누르면 그 제목으로 이동하고 주소에 #id를 남겨 공유할 수 있다.
 */
export function Toc({ bodyRef, html }: { bodyRef: RefObject<HTMLElement | null>; html: string }) {
  const [items, setItems] = useState<TocItem[]>([])
  const [active, setActive] = useState<number | null>(null)
  const mobileRef = useRef<HTMLDetailsElement>(null)

  useEffect(() => {
    setItems(extractToc(bodyRef.current))
    setActive(null) // 글이 바뀌면 이전 글의 위치를 버린다
  }, [bodyRef, html])

  useEffect(() => {
    if (items.length < TOC_MIN_ITEMS) return
    const els = items.map((i) => document.getElementById(i.id))
    let frame = 0
    const update = () => {
      frame = 0
      setActive(activeIndex(els.map((el) => el?.getBoundingClientRect().top ?? Infinity), OFFSET + 1))
    }
    // 스크롤마다 한 번(다음 그림 때)만 계산한다
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update) }
    update()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
      if (frame) cancelAnimationFrame(frame)
    }
  }, [items])

  if (items.length < TOC_MIN_ITEMS) return null
  const go = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' })
    history.replaceState(history.state, '', `#${encodeURIComponent(id)}`)
  }
  const list = (onPick?: () => void) => items.map((item, i) => (
    <li key={item.id} style={{ paddingLeft: `${item.depth * 12}px` }}>
      <a href={`#${encodeURIComponent(item.id)}`} className={i === active ? 'active' : undefined}
         aria-current={i === active ? 'location' : undefined}
         onClick={(e) => { e.preventDefault(); go(item.id); onPick?.() }}>
        {item.text}
      </a>
    </li>
  ))
  return (
    <>
      <aside className="toc" aria-label={t('목차')}>
        <ul className="toc-inner">{list()}</ul>
      </aside>
      {/* 좁은 화면(1280px 미만)은 오른쪽 목차 자리가 없어 본문 위에 접는 목차를 둔다 (097 FR-005) */}
      <details className="toc-mobile" ref={mobileRef}>
        <summary data-tip={t('제목 목록을 펼쳐 원하는 곳으로 바로 가요')}>
          {t('목차')} <span className="muted">{t('{0}개', { 0: items.length })}</span>
          {active != null && items[active] && <span className="toc-mobile-now">{items[active].text}</span>}
        </summary>
        <ul>{list(() => { if (mobileRef.current) mobileRef.current.open = false })}</ul>
      </details>
    </>
  )
}
