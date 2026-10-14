import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { changeLocale, LOCALES, t, useLocale } from '../lib/i18n'
import { NavIcon } from './NavIcons'

/**
 * 머리말의 🌐 언어 메뉴 (081). 테마 버튼 옆에 두어 어느 화면에서나 한 번에 바꾼다.
 * 고르면 changeLocale이 저장하고 페이지를 다시 불러온다.
 */
export function LanguageMenu() {
  const locale = useLocale()
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const current = LOCALES.find((l) => l.code === locale) ?? LOCALES[0]

  useEffect(() => {
    if (!open) return
    // 열리면 지금 언어에 초점을 둔다 (WAI-ARIA menu button)
    boxRef.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus()
    const close = (e: MouseEvent) => {
      if (!boxRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const esc = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setOpen(false)
      buttonRef.current?.focus()
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', esc)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', esc)
    }
  }, [open])

  const onKey = (e: ReactKeyboardEvent) => {
    const items = [...(boxRef.current?.querySelectorAll<HTMLElement>('[role="menuitemradio"]') ?? [])]
    const i = items.indexOf(document.activeElement as HTMLElement)
    let next: number
    if (e.key === 'ArrowDown') next = (i + 1) % items.length
    else if (e.key === 'ArrowUp') next = i <= 0 ? items.length - 1 : i - 1
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = items.length - 1
    else return
    e.preventDefault()
    items[next]?.focus()
  }

  return (
    <div className="menu lang-menu" ref={boxRef}>
      <button type="button" ref={buttonRef} className="btn btn-text lang-toggle" aria-haspopup="menu" aria-expanded={open}
              aria-label={t('언어: {0}', { 0: current.label })} data-tip={t('화면 언어 바꾸기')} onClick={() => setOpen((o) => !o)}>
        <NavIcon name="globe" />
      </button>
      {open && (
        <div className="menu-list lang-list" role="menu" aria-label={t('언어')} onKeyDown={onKey}>
          {LOCALES.map((l) => (
            <button key={l.code} type="button" role="menuitemradio" aria-checked={l.code === locale} lang={l.code}
                    onClick={() => { setOpen(false); if (l.code !== locale) changeLocale(l.code) }}>
              <span className="lang-check" aria-hidden="true">{l.code === locale ? '✓' : ''}</span>{l.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
