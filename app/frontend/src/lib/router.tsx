// 작은 라우터: History API + 경로 패턴. 서버가 같은 주소를 그리므로(SSR 머리말) 주소 규칙은 서버와 같다.
import { useEffect, useState, type AnchorHTMLAttributes, type MouseEvent, type ReactNode } from 'react'
import { focusMain } from './focusMain'
import { requestPageAnnouncement } from './announcePage'

type Listener = () => void
const listeners = new Set<Listener>()

export function navigate(to: string, options: { replace?: boolean } = {}): void {
  if (options.replace) history.replaceState(null, '', to)
  else history.pushState(null, '', to)
  listeners.forEach((l) => l())
}

/** 페이지를 떠나기 전에 확인할 것(미저장 변경 등). false를 돌려주면 이동을 막는다. */
let leaveGuard: (() => boolean) | null = null
export function setLeaveGuard(guard: (() => boolean) | null): void {
  leaveGuard = guard
}

export function useLocation(): { path: string; search: URLSearchParams } {
  const [, force] = useState(0)
  useEffect(() => {
    const l = () => force((n) => n + 1)
    listeners.add(l)
    window.addEventListener('popstate', l)
    return () => {
      listeners.delete(l)
      window.removeEventListener('popstate', l)
    }
  }, [])
  return { path: location.pathname, search: new URLSearchParams(location.search) }
}

export function match(pattern: string, path: string): Record<string, string> | null {
  const names: string[] = []
  const re = new RegExp('^' + pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/:(\w+)/g, (_, n: string) => {
    names.push(n)
    return '([^/]+)'
  }) + '/?$')
  const m = path.match(re)
  if (!m) return null
  const params: Record<string, string> = {}
  names.forEach((n, i) => (params[n] = decodeURIComponent(m[i + 1])))
  return params
}

/**
 * 돌아올 곳만 담은 쿼리(로그인 뒤 돌아갈 주소, 문의를 연 화면)는 a 태그 href에서 뺀다. 누르면 to 그대로 간다.
 * 화면마다 /login?redirect=… · /support?from=… 주소가 하나씩 생겨, 구글이 robots.txt 차단·noindex 주소를
 * 수십 개 모아 서치 콘솔 비색인 수를 부풀렸다(2026-10-23 robots 차단 84, noindex 58). 경로만 남기면 한 주소로 모인다.
 */
const RETURN_PARAM: Record<string, string> = { '/login': 'redirect', '/signup': 'redirect', '/support': 'from' }

export function anchorHref(to: string): string {
  const q = to.indexOf('?')
  if (q < 0) return to
  const path = to.slice(0, q)
  const key = RETURN_PARAM[path]
  if (!key) return to
  const params = new URLSearchParams(to.slice(q + 1))
  if (!params.has(key)) return to
  params.delete(key)
  const rest = params.toString()
  return rest ? `${path}?${rest}` : path
}

export function Link({ to, children, onClick, ...rest }: { to: string; children: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) {
  const handle = (e: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(e)
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    if (rest.target === '_blank') return
    e.preventDefault()
    if (leaveGuard && !leaveGuard()) return
    navigate(to)
    requestPageAnnouncement()
    window.scrollTo(0, 0)
    // 본문 자리는 화면을 불러오는 동안에도 남아 있으므로, 불러오는 중이어도 초점이 새 화면으로 이어진다
    requestAnimationFrame(() => focusMain())
  }
  return (
    <a href={anchorHref(to)} onClick={handle} {...rest}>
      {children}
    </a>
  )
}
