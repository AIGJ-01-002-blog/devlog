import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from 'react'
import { Flash } from './components/Flash'
import { Header } from './components/Header'
import { SkipLink } from './components/SkipLink'
import { TooltipLayer } from './components/TooltipLayer'
import { MobileNav } from './components/MobileNav'
import { SiteFooter } from './components/SiteFooter'
import { PageAnnouncer } from './components/PageAnnouncer'
import { AttendNudge } from './components/AttendNudge'
import { ScrollTopButton } from './components/ScrollTopButton'
import { MAIN_ID } from './lib/focusMain'
import { VerifyBanner } from './components/VerifyBanner'
import { loginPath, useAuth } from './lib/auth'
import { isStaff } from './lib/admin'
import { match, navigate, useLocation } from './lib/router'
import { BlogPage } from './pages/BlogPage'
import { SeriesPage } from './pages/SeriesPage'
import { PortfolioPage } from './pages/PortfolioPage'
// 사이트 포트폴리오는 작은 화면이라 처음부터 같이 받는다. lazy면 서버 본문을 지운 뒤 다시 그려 첫 화면이 밀렸다(CLS 1.08, v2.13.0 보고)
import { SitePortfolioPage } from './pages/SitePortfolioPage'
import { LikedPage, RecentReadsPage } from './pages/LikedPage'
import { FeedPage } from './pages/FeedPage'
import { FollowsPage } from './pages/FollowsPage'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PostPage } from './pages/PostPage'
import { SearchPage } from './pages/SearchPage'
import { TagPage } from './pages/TagPage'
import { TagsPage } from './pages/TagsPage'
import { withdrawnRedirect } from './lib/withdraw'
import { lazyPage } from './lib/lazyPage'
import { PageErrorBoundary } from './components/PageErrorBoundary'
import { sendPage } from './lib/views'
import { t } from './lib/i18n'
import { armMotion, fadeInPage } from './lib/motion'
import { isFirstLogin, isQuietPath, onboardingDone } from './lib/aiOnboarding'

// 글 읽기(홈·글·블로그·태그·검색·피드)는 첫 묶음에 두고, 쓰기·설정·가입·관리자 화면은 처음 열 때 받는다.
// 독자가 받는 첫 JS를 줄이려는 것이다 (spec 028).
const AdminMemberPage = lazyPage(() => import('./pages/AdminMemberPage'), 'AdminMemberPage')
const AdminReportPage = lazyPage(() => import('./pages/AdminReportPage'), 'AdminReportPage')
const AdminReportsPage = lazyPage(() => import('./pages/AdminReportsPage'), 'AdminReportsPage')
const AgreementsPage = lazyPage(() => import('./pages/AgreementsPage'), 'AgreementsPage')
const ManagePage = lazyPage(() => import('./pages/ManagePage'), 'ManagePage')
const SettingsPage = lazyPage(() => import('./pages/SettingsPage'), 'SettingsPage')
const ForgotPasswordPage = lazyPage(() => import('./pages/ForgotPasswordPage'), 'ForgotPasswordPage')
const ResetPasswordPage = lazyPage(() => import('./pages/ResetPasswordPage'), 'ResetPasswordPage')
const SignupEmailPage = lazyPage(() => import('./pages/SignupEmailPage'), 'SignupEmailPage')
const SignupSocialPage = lazyPage(() => import('./pages/SignupSocialPage'), 'SignupSocialPage')
const TermsPage = lazyPage(() => import('./pages/TermsPage'), 'TermsPage')
const VerifyEmailPage = lazyPage(() => import('./pages/VerifyEmailPage'), 'VerifyEmailPage')
const NewPostPage = lazyPage(() => import('./pages/WritePage'), 'NewPostPage')
const WritePage = lazyPage(() => import('./pages/WritePage'), 'WritePage')
const RestorePage = lazyPage(() => import('./pages/RestorePage'), 'RestorePage')
const WithdrawnPage = lazyPage(() => import('./pages/WithdrawnPage'), 'WithdrawnPage')
const WithdrawPage = lazyPage(() => import('./pages/WithdrawPage'), 'WithdrawPage')
const McpPage = lazyPage(() => import('./pages/McpPage'), 'McpPage')
const OAuthAuthorizePage = lazyPage(() => import('./pages/OAuthAuthorizePage'), 'OAuthAuthorizePage')
const SupportPage = lazyPage(() => import('./pages/SupportPage'), 'SupportPage')
const ReleasesPage = lazyPage(() => import('./pages/ReleasesPage'), 'ReleasesPage')
const AdminDashboardPage = lazyPage(() => import('./pages/AdminDashboardPage'), 'AdminDashboardPage')
const AdminPostsPage = lazyPage(() => import('./pages/AdminPostsPage'), 'AdminPostsPage')
const AdminMembersPage = lazyPage(() => import('./pages/AdminMembersPage'), 'AdminMembersPage')
const AdminInquiriesPage = lazyPage(() => import('./pages/AdminInquiriesPage'), 'AdminInquiriesPage')
const RssGuidePage = lazyPage(() => import('./pages/RssGuidePage'), 'RssGuidePage')
const AdminInquiryPage = lazyPage(() => import('./pages/AdminInquiryPage'), 'AdminInquiryPage')
const AboutPage = lazyPage(() => import('./pages/AboutPage'), 'AboutPage')
const ContactPage = lazyPage(() => import('./pages/ContactPage'), 'ContactPage')
const StudioPage = lazyPage(() => import('./pages/StudioPage'), 'StudioPage')
const DashboardPage = lazyPage(() => import('./pages/DashboardPage'), 'DashboardPage')
const TradesPage = lazyPage(() => import('./pages/TradesPage'), 'TradesPage')
const TradeBotPage = lazyPage(() => import('./pages/TradeBotPage'), 'TradeBotPage')
const PointsPage = lazyPage(() => import('./pages/PointsPage'), 'PointsPage')
const AdminPointsPage = lazyPage(() => import('./pages/AdminPointsPage'), 'AdminPointsPage')
const ShopPage = lazyPage(() => import('./pages/ShopPage'), 'ShopPage')
const PaperPage = lazyPage(() => import('./pages/PaperPage'), 'PaperPage')
const AdminShopPage = lazyPage(() => import('./pages/AdminShopPage'), 'AdminShopPage')
// 첫 로그인 회원만 보는 팝업이라 첫 묶음에 넣지 않는다 (spec 098)
const AiOnboarding = lazy(() => import('./components/AiOnboarding').then((m) => ({ default: m.AiOnboarding })))

export function App() {
  const { path } = useLocation()
  useEffect(() => { sendPage(path) }, [path])
  useEffect(() => armMotion(), [])
  return (
    <>
      <SkipLink />
      <PageAnnouncer />
      {!path.startsWith('/write') && path !== '/account/restore' && <Header />}
      {path !== '/verify-email' && <VerifyBanner />}
      <Flash path={path} />
      <AttendNudge path={path} />
      <div id={MAIN_ID} tabIndex={-1}>
        <WithdrawnGate path={path}><AgreementGate path={path}><PageErrorBoundary path={path}><Suspense fallback={<Loading />}>{route(path)}<FadeOnShow path={path} /></Suspense></PageErrorBoundary></AgreementGate></WithdrawnGate>
      </div>
      {!path.startsWith('/write') && path !== '/account/restore' && !path.startsWith('/oauth/') && !path.startsWith('/admin') && !path.startsWith('/manage/') && <SiteFooter />}
      {!path.startsWith('/write') && path !== '/account/restore' && !path.startsWith('/oauth/') && <MobileNav path={path} />}
      {!path.startsWith('/write') && !path.startsWith('/admin') && <ScrollTopButton />}
      <AiOnboardingGate path={path} />
      <TooltipLayer />
    </>
  )
}

/** 가입 뒤 첫 로그인이면 AI 연결 안내를 한 번 띄운다 (spec 098). 쓰기·동의·설정 같은 화면에서는 기다렸다가 다른 화면에서 띄운다. */
function AiOnboardingGate({ path }: { path: string }) {
  const { me } = useAuth()
  const [closed, setClosed] = useState(false)
  if (closed || !isFirstLogin(me) || isQuietPath(path) || onboardingDone(me.member.id)) return null
  return <Suspense fallback={null}><AiOnboarding memberId={me.member.id} onClose={() => setClosed(true)} /></Suspense>
}

/**
 * 화면을 옮기면 본문 자리를 부드럽게 보인다 (095). 첫 화면은 그대로 둔다.
 * Suspense 안에 두어, 처음 여는 화면 묶음을 받는 동안(불러오는 중 표시)이 아니라 화면이 실제로 그려진 뒤에 움직인다.
 */
function FadeOnShow({ path }: { path: string }) {
  const firstPath = useRef<string | null>(path)
  useEffect(() => {
    if (path === firstPath.current) return
    firstPath.current = null
    fadeInPage(document.getElementById(MAIN_ID))
  }, [path])
  return null
}

function route(path: string): ReactNode {
  let p: Record<string, string> | null
  if (path === '/') return <HomePage />
  if ((p = match('/@:handle/posts/:id', path))) return <PostPage key={`${p.handle}/${p.id}`} handle={p.handle} id={p.id} />
  if ((p = match('/@:handle/followers', path))) return <FollowsPage key={`${p.handle}/f`} handle={p.handle} direction="followers" />
  if ((p = match('/@:handle/following', path))) return <FollowsPage key={`${p.handle}/g`} handle={p.handle} direction="following" />
  if ((p = match('/@:handle/series/:slug', path))) return <SeriesPage key={`${p.handle}/s/${p.slug}`} handle={p.handle} slug={p.slug} />
  if ((p = match('/@:handle/series', path))) return <BlogPage key={p.handle} handle={p.handle} tab="series" />
  if ((p = match('/@:handle/portfolio', path))) return <PortfolioPage key={`${p.handle}/portfolio`} handle={p.handle} />
  if ((p = match('/@:handle/rss', path))) return <RssGuidePage key={`${p.handle}/rss`} handle={p.handle.toLowerCase()} />
  if (path === '/rss') return <RssGuidePage handle={null} />
  if ((p = match('/@:handle/about', path))) return <BlogPage key={p.handle} handle={p.handle} tab="about" />
  if ((p = match('/@:handle/shorts', path))) return <BlogPage key={p.handle} handle={p.handle} tab="shorts" />
  if ((p = match('/@:handle', path))) return <BlogPage key={p.handle} handle={p.handle} />
  if (path === '/search') return <SearchPage />
  if (path === '/mcp') return <McpPage />
  if (path === '/oauth/authorize') return <RequireLogin><OAuthAuthorizePage /></RequireLogin>
  if (path === '/support') return <SupportPage />
  if (path === '/releases') return <ReleasesPage />
  if (path === '/tags') return <TagsPage />
  if ((p = match('/tags/:name', path))) return <TagPage key={p.name} name={p.name} />
  if (path === '/login') return <LoginPage />
  if (path === '/signup') return <SignupEmailPage />
  if (path === '/signup/social') return <SignupSocialPage />
  if (path === '/forgot-password') return <ForgotPasswordPage />
  if (path === '/reset-password') return <ResetPasswordPage />
  if (path === '/verify-email') return <VerifyEmailPage />
  if (path === '/agreements') return <AgreementsPage />
  if (path === '/terms') return <TermsPage kind="terms" />
  if (path === '/privacy') return <TermsPage kind="privacy" />
  if (path === '/about') return <AboutPage />
  if (path === '/contact') return <ContactPage />
  if (path === '/portfolio') return <SitePortfolioPage />
  if (path === '/write') return <RequireLogin><NewPostPage /></RequireLogin>
  if ((p = match('/write/:id', path))) return <RequireLogin><WritePage key={p.id} id={p.id} /></RequireLogin>
  if (path === '/manage/posts') return <RequireLogin><ManagePage /></RequireLogin>
  if (path === '/dashboard') return <RequireLogin><DashboardPage /></RequireLogin>
  if (path === '/studio') return <RequireLogin><RequireShorts><StudioPage /></RequireShorts></RequireLogin>
  if (path === '/trades') return <RequireLogin><RequireTrades><TradesPage /></RequireTrades></RequireLogin>
  // 자동매매 모의 화면(spec 101). 봇이 report_trade_bot으로 알린 모의 계좌·체결·Gemini 판단
  if (path === '/trade') return <RequireLogin><RequireTrades><TradeBotPage /></RequireTrades></RequireLogin>
  if (path === '/points') return <RequireLogin><RequirePoints><PointsPage /></RequirePoints></RequireLogin>
  if (path === '/shop') return <RequireLogin><RequirePoints><ShopPage /></RequirePoints></RequireLogin>
  // 포인트 모의투자(#267)는 체결을 매매 일지에 남기므로 둘 다 켜져야 보인다
  if (path === '/paper') return <RequireLogin><RequirePoints><RequireTrades><PaperPage /></RequireTrades></RequirePoints></RequireLogin>
  if (path === '/feed') return <RequireLogin><FeedPage /></RequireLogin>
  if (path === '/lists/liked') return <RequireLogin><LikedPage /></RequireLogin>
  if (path === '/lists/read') return <RecentReadsPage />
  if (path === '/notifications') return <RequireLogin><NotificationsPage /></RequireLogin>
  if (path === '/settings') return <RequireLogin><SettingsPage /></RequireLogin>
  if (path === '/settings/withdraw') return <RequireLogin><WithdrawPage /></RequireLogin>
  if (path === '/withdrawn') return <WithdrawnPage />
  if (path === '/account/restore') return <RequireLogin><RestorePage /></RequireLogin>
  if (path === '/admin') return <RequireAdmin><AdminDashboardPage /></RequireAdmin>
  if (path === '/admin/posts') return <RequireAdmin><AdminPostsPage /></RequireAdmin>
  if (path === '/admin/members') return <RequireAdmin><AdminMembersPage /></RequireAdmin>
  if (path === '/admin/reports') return <RequireAdmin><AdminReportsPage /></RequireAdmin>
  if ((p = match('/admin/reports/:id', path))) return <RequireAdmin><AdminReportPage key={p.id} id={p.id} /></RequireAdmin>
  if (path === '/admin/points') return <RequireAdmin><RequirePoints><AdminPointsPage /></RequirePoints></RequireAdmin>
  if (path === '/admin/shop') return <RequireAdmin><RequirePoints><AdminShopPage /></RequirePoints></RequireAdmin>
  if (path === '/admin/inquiries') return <RequireAdmin><AdminInquiriesPage /></RequireAdmin>
  if ((p = match('/admin/inquiries/:id', path))) return <RequireAdmin><AdminInquiryPage key={p.id} id={p.id} /></RequireAdmin>
  if ((p = match('/admin/members/:handle', path))) return <RequireAdmin><AdminMemberPage key={p.handle} handle={p.handle} /></RequireAdmin>
  return <NotFoundPage />
}

function Loading() {
  // 페이지 자리(#main)의 유일한 <main>이다. 안에 다른 <main>을 품지 않는다
  return <main className="container" aria-busy="true"><p className="muted center" role="status">{t('불러오는 중…')}</p></main>
}

/** 쇼츠 스튜디오(099)는 서버에서 켠 뒤에만 보인다. 꺼져 있으면 없는 주소처럼 */
function RequireShorts({ children }: { children: ReactNode }) {
  const { me } = useAuth()
  if (!me?.features?.shorts) return <NotFoundPage />
  return <>{children}</>
}

function RequireTrades({ children }: { children: ReactNode }) {
  const { me } = useAuth()
  if (!me?.features?.trades) return <NotFoundPage />
  return <>{children}</>
}

/** 블로그 포인트(#266)도 서버에서 켠 뒤에만 보인다 */
function RequirePoints({ children }: { children: ReactNode }) {
  const { me } = useAuth()
  if (!me?.features?.points) return <NotFoundPage />
  return <>{children}</>
}

function RequireLogin({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  useEffect(() => {
    if (!loading && !me?.authenticated) navigate(loginPath(), { replace: true })
  }, [loading, me])
  if (loading || !me?.authenticated) return <Loading />
  return <>{children}</>
}

/** 관리자 화면(관리자·매니저, 062): 서버가 비회원은 로그인으로, 일반 회원은 404로 보내므로 여기서는 화면 안에서 옮겨 온 경우만 막는다. */
function RequireAdmin({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <Loading />
  if (!me?.authenticated) return <RequireLogin>{children}</RequireLogin>
  if (!isStaff(me.member?.role)) return <NotFoundPage />
  return <>{children}</>
}

/** 탈퇴 유예 회원은 복구 화면만 쓴다 (020 FR-016, 서버도 403 ACCOUNT_WITHDRAWN으로 막는다). */
function WithdrawnGate({ path, children }: { path: string; children: ReactNode }) {
  const { me, loading } = useAuth()
  const to = loading ? null : withdrawnRedirect(me?.authenticated ? me.member?.status : undefined, path)
  useEffect(() => {
    if (to) navigate(to, { replace: true })
  }, [to])
  if (to) return null
  return <>{children}</>
}

// 재동의 전에도 열 수 있는 화면: 동의할 약관·처리방침 본문 (077, 바뀐 내용을 읽고 동의하게)
const READABLE_BEFORE_AGREEING = new Set(['/agreements', '/terms', '/privacy'])

/** 재동의가 필요하면 동의 화면과 약관 본문만 쓸 수 있다 (서버도 403 AGREEMENT_REQUIRED로 막는다). */
function AgreementGate({ path, children }: { path: string; children: ReactNode }) {
  const { me } = useAuth()
  useEffect(() => {
    // 탈퇴 유예 회원은 복구 화면만 쓴다(WithdrawnGate). 재동의 요청도 서버가 막으므로 여기서 보내지 않는다
    if (me?.agreementRequired && me.member?.status !== 'WITHDRAWN' && !READABLE_BEFORE_AGREEING.has(path)) {
      navigate(`/agreements?redirect=${encodeURIComponent(path)}`, { replace: true })
    }
  }, [me, path])
  return <>{children}</>
}
