import { useAuth } from '../lib/auth'
import { Link, useLocation } from '../lib/router'

/** feature가 있으면 서버에서 그 기능을 켰을 때만 보인다 */
const ITEMS: { to: string; label: string; tip: string; feature?: 'points' }[] = [
  { to: '/admin', label: '대시보드', tip: '가입·글·조회수 추이와 처리할 일' },
  { to: '/admin/posts', label: '글', tip: '발행한 글 찾기·숨기기' },
  { to: '/admin/members', label: '회원', tip: '회원 찾기·권한·정지·작성 통계' },
  { to: '/admin/reports', label: '신고', tip: '신고 처리' },
  { to: '/admin/inquiries', label: '문의', tip: '문의·버그 신고 답변' },
  { to: '/admin/points', label: '포인트', tip: '출석·글·모의투자 포인트 적립 규칙', feature: 'points' },
]

/** 관리자 페이지 공통 탭 (062). 지금 화면이 어느 묶음인지 표시한다(상세 화면은 그 목록 탭). */
export function AdminNav() {
  const { path } = useLocation()
  const { me } = useAuth()
  const active = (to: string) => (to === '/admin' ? path === '/admin' : path === to || path.startsWith(`${to}/`))
  return (
    <nav className="tabs admin-nav" aria-label="관리자 메뉴">
      {ITEMS.filter((it) => !it.feature || me?.features?.[it.feature]).map((it) => (
        <Link key={it.to} to={it.to} data-tip={it.tip} aria-current={active(it.to) ? 'page' : undefined}>{it.label}</Link>
      ))}
    </nav>
  )
}
