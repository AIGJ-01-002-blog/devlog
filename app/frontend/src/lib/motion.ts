/**
 * 화면 움직임 (spec 095). 효과는 거의 다 styles.css의 CSS 전환·애니메이션이고, 여기서는 두 가지만 맡는다.
 * - 첫 화면(서버가 그린 화면을 React가 다시 그리는 순간)은 움직이지 않는다. 사용자가 처음 스크롤·클릭·키 입력을 하면
 *   <html>에 motion-ready를 붙이고, 그때부터 새로 생기는 카드·배지만 등장 효과를 낸다. 첫 화면 시간(LCP)을 늦추지 않으려는 것이다.
 * - 화면을 옮길 때 본문 자리를 짧게 흐렸다가 보인다. transform을 쓰지 않는다: 본문 자리에 transform이 걸리면
 *   그 안의 position: fixed(읽기 진행 막대 등)가 잠깐 본문 기준으로 바뀐다.
 */
export const MOTION_READY = 'motion-ready'
const START_EVENTS = ['scroll', 'pointerdown', 'keydown'] as const

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false)
}

/** 첫 사용자 동작 뒤에 등장 효과를 켠다. 돌려준 함수로 기다림을 거둔다. */
export function armMotion(root: HTMLElement = document.documentElement): () => void {
  const ready = () => {
    root.classList.add(MOTION_READY)
    stop()
  }
  const stop = () => START_EVENTS.forEach((e) => window.removeEventListener(e, ready, true))
  START_EVENTS.forEach((e) => window.addEventListener(e, ready, { capture: true, passive: true }))
  return stop
}

/** 화면을 옮긴 뒤 본문 자리를 0.18초 동안 흐림에서 또렷하게. 동작 줄이기를 켰거나 Web Animations가 없으면 하지 않는다. */
export function fadeInPage(el: HTMLElement | null): void {
  if (!el || typeof el.animate !== 'function' || prefersReducedMotion()) return
  el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 180, easing: 'cubic-bezier(0.2, 0.7, 0.2, 1)' })
}
