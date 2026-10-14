import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { isStaff } from '../lib/admin'
import { loginPath, useAuth } from '../lib/auth'
import { Link, navigate, useLocation } from '../lib/router'
import { Avatar } from './Avatar'
import { NavIcon, type IconName } from './NavIcons'
import { NotificationBell } from './NotificationBell'
import { LanguageMenu } from './LanguageMenu'
import { ThemeToggle } from './ThemeToggle'
import { t } from '../lib/i18n'

interface NavItem {
  to: string; label: string; tip: string; icon: IconName; on: (p: string) => boolean; memberOnly?: boolean
  /** 회원에게는 머리말 대신 내 메뉴에 둔다 (072: 머리말은 피드·좋아한 글·태그만) */
  inMenu?: boolean
}

/**
 * 머리말 메뉴 (063, 072). 회원은 "피드 · 좋아한 글 · 태그 · 🔔"이고 문의·신고·릴리스 노트는 내 메뉴에 있다(072 시안).
 * 비회원은 내 메뉴가 없어 문의·신고·릴리스 노트를 머리말에 아이콘으로 둔다.
 * 넓은 화면은 아이콘과 이름을, 중간 화면(641~1180px)은 아이콘만 보이고 툴팁으로 이름을 알린다.
 * 휴대폰(640px 이하)은 아래 탭과 내 메뉴가 같은 곳으로 데려간다.
 */
export function headerItems(path: string): NavItem[] {
  return [
    { to: '/feed', label: t('피드'), tip: t('팔로우한 사람의 새 글'), icon: 'feed', on: (p) => p === '/feed', memberOnly: true },
    { to: '/lists/liked', label: t('좋아한 글'), tip: t('내가 좋아요를 누른 글'), icon: 'heart', on: (p) => p === '/lists/liked' || p === '/lists/read', memberOnly: true },
    { to: '/tags', label: t('태그'), tip: t('태그별로 글 모아 보기'), icon: 'tag', on: (p) => p === '/tags' || p.startsWith('/tags/') },
    // 지금 화면 주소를 함께 넘겨 버그가 난 곳을 남긴다 (054)
    { to: path.startsWith('/support') ? '/support' : `/support?from=${encodeURIComponent(path)}`, label: t('문의·신고'),
      tip: t('궁금한 점·버그·제안을 운영자에게 보내요'), icon: 'support', on: (p) => p === '/support', inMenu: true },
    { to: '/releases', label: t('릴리스 노트'), tip: t('버전마다 바뀐 점을 봐요'), icon: 'releases', on: (p) => p === '/releases', inMenu: true },
  ]
}

export function Header() {
  const { me, logout } = useAuth()
  const { path } = useLocation()
  const [open, setOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    // 열리면 첫 항목으로 초점을 옮긴다 (WAI-ARIA menu button)
    menuItems()[0]?.focus()
    const close = (e: MouseEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) setOpen(false)
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

  const menuItems = () => [...(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
    .filter((el) => {
      // 넓은 화면에서 숨긴 휴대폰 전용 항목은 건너뛴다
      const box = el.closest<HTMLElement>('.menu-mobile-only')
      return !box || getComputedStyle(box).display !== 'none'
    })

  const onMenuKey = (e: ReactKeyboardEvent) => {
    const items = menuItems()
    if (items.length === 0) return
    const i = items.indexOf(document.activeElement as HTMLElement)
    let next: number
    if (e.key === 'ArrowDown') next = i < 0 ? 0 : (i + 1) % items.length
    else if (e.key === 'ArrowUp') next = i <= 0 ? items.length - 1 : i - 1
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = items.length - 1
    else return
    e.preventDefault()
    items[next].focus()
  }

  const onLogout = async () => {
    try {
      await logout()
      navigate('/')
    } catch {
      alert(t('로그아웃하지 못했어요. 잠시 뒤 다시 시도해 주세요.'))
    }
  }

  const member = me?.member
  const all = headerItems(path).filter((it) => member || !it.memberOnly)
  const items = all.filter((it) => !member || !it.inMenu)
  // 회원의 문의·신고·릴리스 노트는 늘 내 메뉴에 있다
  const menuExtra = all.filter((it) => member && it.inMenu)
  // 휴대폰에서는 머리말 메뉴가 숨으니 아래 탭에 없는 좋아한 글도 내 메뉴 아래에 둔다
  const mobileExtra = items.filter((it) => it.icon !== 'feed' && it.icon !== 'tag')
  return (
    <header className="site-header">
      <div className="container header-inner">
        <Link to="/" className="logo" data-tip={t('첫 화면으로')}><span className="logo-mark" aria-hidden="true" />devlog</Link>
        <nav className="header-actions" aria-label={t('머리말 메뉴')}>
          <Link to="/search" className="btn btn-text header-search header-nav" aria-label={t('검색')} data-tip={t('글·사람 검색')}>
            <NavIcon name="search" />
          </Link>
          <Link to="/mcp" className="btn btn-text header-mcp" data-tip={t('Claude·ChatGPT 같은 AI 도구에 devlog 연결하기')}>{t('AI 연결')}</Link>
          <span className="header-sep header-nav" aria-hidden="true" />
          {items.map((it) => {
            const on = it.on(path)
            return (
              <Link key={it.icon} to={it.to} className={`btn btn-text header-nav header-link${on ? ' on' : ''}`}
                    aria-current={on ? 'page' : undefined} aria-label={it.label} data-tip={it.tip}>
                <NavIcon name={it.icon} /><span className="header-label">{it.label}</span>
              </Link>
            )
          })}
          {member ? (
            <>
              {isStaff(member.role) && (
                <Link to="/admin" className={`btn btn-text header-admin header-nav header-link${path.startsWith('/admin') ? ' on' : ''}`} aria-label={t('관리자 페이지')}
                      data-tip={t('관리자 페이지: 통계·글·회원·신고 관리')} aria-current={path.startsWith('/admin') ? 'page' : undefined}>
                  <NavIcon name="shield" />
                </Link>
              )}
              <NotificationBell />
              <Link to="/write" className="btn btn-outline header-write" aria-label={t('새 글 작성')} data-tip={t('새 글 쓰기')}><span className="long">{t('새 글 작성')}</span><span className="short" aria-hidden="true">{t('글쓰기')}</span><span className="icon" aria-hidden="true">✏️</span></Link>
              <div className="menu" ref={menuRef} onBlur={(e) => {
                // 초점이 메뉴 밖의 다른 요소로 옮겨 가면 닫는다 (빈 곳 클릭은 mousedown이 맡는다)
                if (open && e.relatedTarget && !e.currentTarget.contains(e.relatedTarget as Node)) setOpen(false)
              }}>
                <button type="button" ref={buttonRef} className="menu-button" aria-haspopup="menu" aria-expanded={open} data-tip={t('내 메뉴: 내 설정·내 블로그·글 관리·로그아웃')}
                        onClick={() => setOpen((o) => !o)}>
                  <Avatar src={member.profileImageUrl} name={member.nickname} seed={member.handle} />
                  <span className="sr-only">{t('내 메뉴')}</span>
                </button>
                {open && (
                  <div className="menu-list profile-menu" role="menu" aria-label={t('내 메뉴')} onClick={() => setOpen(false)} onKeyDown={onMenuKey}>
                    <div className="menu-who" role="none">
                      <Avatar src={member.profileImageUrl} name={member.nickname} seed={member.handle} size={40} />
                      <span><b>{member.nickname}</b><span className="muted">@{member.handle}</span></span>
                    </div>
                    <Link to="/settings" role="menuitem"><NavIcon name="settings" />{t('내 설정')}</Link>
                    <Link to={`/@${member.handle}`} role="menuitem"><NavIcon name="blog" />{t('내 블로그')}</Link>
                    <Link to="/manage/posts" role="menuitem"><NavIcon name="posts" />{t('내 글 관리')}</Link>
                    <Link to="/dashboard" role="menuitem" title={t('내 글과 쇼츠가 받은 반응을 기간별로 보기')}><NavIcon name="chart" />{t('내 대시보드')}</Link>
                    {me?.features?.shorts && <Link to="/studio" role="menuitem" title={t('글로 쇼츠 대본을 쓰고 영상 만들기')}><NavIcon name="video" />{t('쇼츠 스튜디오')}</Link>}
                    {mobileExtra.length > 0 && (
                      <div className="menu-mobile-only" role="none">
                        <div className="menu-sep" role="separator" />
                        {mobileExtra.map((it) => <Link key={it.icon} to={it.to} role="menuitem"><NavIcon name={it.icon} />{it.label}</Link>)}
                      </div>
                    )}
                    <div className="menu-sep" role="separator" />
                    {menuExtra.map((it) => <Link key={it.icon} to={it.to} role="menuitem"><NavIcon name={it.icon} />{it.label}</Link>)}
                    {isStaff(member.role) && (
                      <>
                        <div className="menu-sep" role="separator" />
                        <Link to="/admin" role="menuitem" title={t('통계·글·회원·신고·문의 관리')}><NavIcon name="shield" />{t('관리자 페이지')}</Link>
                      </>
                    )}
                    <div className="menu-sep" role="separator" />
                    <button type="button" role="menuitem" onClick={() => void onLogout()}><NavIcon name="logout" />{t('로그아웃')}</button>
                  </div>
                )}
              </div>
            </>
          ) : (
            <Link to={loginPath()} className="btn btn-dark" data-tip={t('로그인하고 글쓰기·좋아요·팔로우')}>{t('로그인')}</Link>
          )}
          <LanguageMenu />
          <ThemeToggle />
        </nav>
      </div>
    </header>
  )
}
