import { t } from './i18n'
// 화면 테마 (spec 021). 선택은 이 기기 브라우저에만 저장하고(비회원 포함), 서버·계정에는 두지 않는다.
// 처음 테마는 public/theme.js가 그리기 전에 정한다. 여기서는 버튼으로 바꿀 때만 쓴다.

export type ThemeChoice = 'system' | 'light' | 'dark'

export const THEME_KEY = 'blog.theme'

const ORDER: ThemeChoice[] = ['system', 'light', 'dark']

export const THEME_LABEL: Record<ThemeChoice, string> = { system: t('시스템 설정'), light: t('라이트'), dark: t('다크') }
export const THEME_ICON = { system: 'monitor', light: 'sun', dark: 'moon' } as const satisfies Record<ThemeChoice, string>

/** 시스템 → 라이트 → 다크 → 시스템 (FR-004) */
export function nextTheme(t: ThemeChoice): ThemeChoice {
  return ORDER[(ORDER.indexOf(t) + 1) % ORDER.length]
}

export function readTheme(storage: Pick<Storage, 'getItem'> | null = safeStorage()): ThemeChoice {
  try {
    const v = storage?.getItem(THEME_KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

/** 저장하고 바로 적용한다 (FR-005). "시스템"이면 data-theme를 지워 CSS가 기기 설정을 따르게 한다(FR-006). */
export function applyTheme(t: ThemeChoice, root: HTMLElement = document.documentElement,
                           storage: Pick<Storage, 'setItem' | 'removeItem'> | null = safeStorage()): void {
  try {
    if (t === 'system') storage?.removeItem(THEME_KEY)
    else storage?.setItem(THEME_KEY, t)
  } catch {
    // 저장소가 막혀도 이번 화면에는 적용한다
  }
  // 색이 서서히 바뀌지 않게 한 프레임 동안 전환 효과를 끈다 (FR-012)
  root.classList.add('theme-switching')
  if (t === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', t)
  // 휴대폰 주소창 색(theme-color)도 맞춘다 (068). "시스템"이면 index.html 원래 값(라이트·다크 미디어별)으로 되돌린다
  root.ownerDocument.querySelectorAll('meta[name="theme-color"]').forEach((m) => {
    const light = !m.getAttribute('media')?.includes('dark')
    m.setAttribute('content', t === 'dark' || (t === 'system' && !light) ? '#1b1e25' : '#ffffff')
  })
  void root.offsetWidth
  requestAnimationFrame(() => root.classList.remove('theme-switching'))
}

function safeStorage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}
