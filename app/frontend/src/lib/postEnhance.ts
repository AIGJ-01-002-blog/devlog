import { t } from './i18n'

/**
 * 글 본문 다듬기 (spec 097). 서버가 정화해 준 본문 HTML은 그대로 두고, 읽는 화면에서만 버튼을 붙인다.
 * 그래서 RSS·검색 엔진·스크립트 없는 화면은 전과 같은 본문을 받는다.
 */

const COPY_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/></svg>'
const WRAP_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M3 12h15a3 3 0 0 1 0 6h-4"/><path d="m16 16-2 2 2 2M3 18h7"/></svg>'

/** 코드 블록에 [복사]와 [줄바꿈] 버튼을 붙인다 (FR-004·FR-012). 그림(mermaid) 블록과 이미 붙인 블록은 건너뛴다 */
export function enhanceCodeBlocks(root: HTMLElement | null, clipboard: Pick<Clipboard, 'writeText'> | undefined = navigator.clipboard): void {
  if (!root) return
  root.querySelectorAll<HTMLPreElement>('pre').forEach((pre) => {
    const code = pre.querySelector('code')
    if (!code || code.classList.contains('language-mermaid') || pre.parentElement?.classList.contains('code-block')) return
    const box = document.createElement('div')
    box.className = 'code-block'
    const bar = document.createElement('div')
    bar.className = 'code-tools'
    const lang = [...code.classList].find((c) => c.startsWith('language-'))?.slice('language-'.length)
    if (lang) {
      const label = document.createElement('span')
      label.className = 'code-lang'
      label.textContent = lang
      bar.append(label)
    }
    const wrap = toolButton(WRAP_ICON, t('줄바꿈'), t('긴 줄을 화면 폭에 맞춰 접어 보기'))
    wrap.setAttribute('aria-pressed', 'false')
    wrap.addEventListener('click', () => {
      const on = !pre.classList.contains('is-wrapped')
      pre.classList.toggle('is-wrapped', on)
      wrap.setAttribute('aria-pressed', String(on))
    })
    const copy = toolButton(COPY_ICON, t('복사'), t('이 코드를 클립보드에 복사'))
    const status = document.createElement('span')
    status.className = 'code-status'
    status.setAttribute('role', 'status')
    let timer = 0
    copy.addEventListener('click', async () => {
      window.clearTimeout(timer)
      try {
        if (!clipboard) throw new Error('no clipboard')
        await clipboard.writeText(code.textContent ?? '')
        status.textContent = t('복사했어요')
        copy.classList.add('is-done')
      } catch {
        selectContents(code)
        status.textContent = t('복사하지 못했어요. 골라 둔 글자를 Ctrl+C로 복사해 주세요')
      }
      timer = window.setTimeout(() => { status.textContent = ''; copy.classList.remove('is-done') }, 2000)
    })
    bar.append(status, wrap, copy)
    pre.replaceWith(box)
    box.append(bar, pre)
  })
}

function toolButton(icon: string, text: string, tip: string): HTMLButtonElement {
  const b = document.createElement('button')
  b.type = 'button'
  b.className = 'code-tool'
  b.dataset.tip = tip
  b.setAttribute('aria-label', text) // 좁은 화면은 글자를 숨기므로 이름을 따로 단다
  b.innerHTML = icon // 고정 문자열 아이콘만 넣는다
  const span = document.createElement('span')
  span.textContent = text
  b.append(span)
  return b
}

function selectContents(el: HTMLElement) {
  const sel = window.getSelection()
  if (!sel) return
  const range = document.createRange()
  range.selectNodeContents(el)
  sel.removeAllRanges()
  sel.addRange(range)
}

/** 할 일 목록 체크 상자에 이름을 붙인다 (FR-006). 글자만 읽어 주던 화면 읽기 프로그램이 완료 여부도 읽는다 */
export function labelTaskItems(root: HTMLElement | null): void {
  root?.querySelectorAll<HTMLInputElement>('li > input[type="checkbox"]').forEach((box) => {
    const item = box.parentElement?.textContent?.trim() ?? ''
    box.setAttribute('aria-label', box.checked ? t('완료: {0}', { 0: item }) : t('할 일: {0}', { 0: item }))
  })
}

/** 눌러서 크게 볼 사진: 링크 안 사진(GIF 재생·외부 링크)은 링크 동작을 그대로 둔다 */
export function zoomableImage(target: EventTarget | null, root: HTMLElement | null): HTMLImageElement | null {
  if (!(target instanceof HTMLImageElement) || !root?.contains(target)) return null
  if (target.closest('a, .diagram, figure.mermaid')) return null
  return target
}
