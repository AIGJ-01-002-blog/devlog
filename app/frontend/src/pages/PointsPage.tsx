import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { ApiError } from '../lib/api'
import { num } from '../lib/dashboard'
import { compactNumber } from '../lib/format'
import { t } from '../lib/i18n'
import { NavIcon, type IconName } from '../components/NavIcons'
import { StreakBadgeIcon } from '../components/StreakBadge'
import {
  amountOf, attend, attendanceDots, attendanceReward, badgeName, BADGE_DAYS, credits, entryTime, loadPointHistory, loadPoints, points, reasonLabel, ruleValue, walletLabel,
  type PointEntry, type PointSummary, type Wallet,
} from '../lib/points'

/** 내 포인트 탭: 내 설정처럼 왼쪽 목록에서 고르고, 휴대폰에서는 위에서 옆으로 밀린다. 주소 뒤 #이름으로 바로 열린다(/points#badges) */
export const POINTS_TABS = [
  { id: 'attend', label: t('출석'), hint: t('오늘 출석하고 연속 출석 보상 보기'), icon: 'check' },
  { id: 'badges', label: t('휘장'), hint: t('연속 출석으로 받는 휘장'), icon: 'shield' },
  { id: 'earn', label: t('크레딧 얻는 법'), hint: t('출석·글 발행·모의투자 수익 적립 규칙'), icon: 'coin' },
  { id: 'history', label: t('내역'), hint: t('적립·사용 내역'), icon: 'posts' },
] as const satisfies readonly { id: string; label: string; hint: string; icon: IconName }[]
export type PointsTab = (typeof POINTS_TABS)[number]['id']

export function pointsTabFromHash(hash: string): PointsTab {
  const key = hash.replace(/^#/, '')
  return POINTS_TABS.find((x) => x.id === key)?.id ?? 'attend'
}

/** 휘장을 받은 날(한국 시간): 2026.10.14 */
const kstDate = (iso: string) => new Date(Date.parse(iso) + 9 * 3600_000).toISOString().slice(0, 10).replace(/-/g, '.')

type Filter = 'ALL' | Wallet
const filters = (): { key: Filter; label: string; tip: string }[] => [
  { key: 'ALL', label: t('전체'), tip: t('모든 적립·사용 내역을 봐요') },
  { key: 'SHOP', label: t('크레딧'), tip: t('출석·글·모의투자 수익으로 번 크레딧 내역만 봐요') },
  { key: 'INVEST_ONLY', label: t('투자 전용'), tip: t('가입 첫 지급과 모의투자에 쓴 내역만 봐요') },
]

/**
 * 내 포인트 (#266). 지갑 두 개(상점·투자 전용) 잔액, 출석, 포인트 얻는 법, 내역.
 * 가입 첫 지급은 모의투자에만 쓰고, 활동으로 번 포인트는 상점에서 꾸미기 아이템을 산다. 현금으로 바꿀 수 없다.
 */
export function PointsPage() {
  const [data, setData] = useState<PointSummary | null>(null)
  const [error, setError] = useState(false)
  const [filter, setFilter] = useState<Filter>('ALL')
  const [items, setItems] = useState<PointEntry[]>([])
  const [next, setNext] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [tab, setTab] = useState<PointsTab>(() => pointsTabFromHash(location.hash))

  useEffect(() => { document.title = t('내 포인트 - devlog') }, [])
  useEffect(() => {
    const sync = () => setTab(pointsTabFromHash(location.hash))
    window.addEventListener('hashchange', sync)
    window.addEventListener('popstate', sync)
    return () => { window.removeEventListener('hashchange', sync); window.removeEventListener('popstate', sync) }
  }, [])
  const open = (id: PointsTab) => {
    if (id === tab) return
    history.replaceState(null, '', `/points#${id}`)
    setTab(id)
  }
  useEffect(() => {
    loadPoints().then((d) => { setData(d); setItems(d.history.items); setNext(d.history.nextBefore) }).catch(() => setError(true))
  }, [])

  // 필터를 빠르게 바꿨을 때 늦게 온 이전 필터의 응답이 목록을 덮어쓰지 않게, 지금 필터를 따로 들고 있는다
  const current = useRef<Filter>('ALL')
  const choose = (f: Filter) => { current.current = f; setFilter(f) }

  const pick = async (f: Filter) => {
    choose(f)
    try {
      const h = await loadPointHistory(f === 'ALL' ? null : f, null)
      if (current.current !== f) return
      setItems(h.items)
      setNext(h.nextBefore)
    } catch {
      setMessage({ ok: false, text: t('내역을 불러오지 못했어요.') })
    }
  }

  const more = async () => {
    if (next === null) return
    const f = filter
    try {
      const h = await loadPointHistory(f === 'ALL' ? null : f, next)
      if (current.current !== f) return
      setItems((prev) => [...prev, ...h.items])
      setNext(h.nextBefore)
    } catch {
      setMessage({ ok: false, text: t('내역을 불러오지 못했어요.') })
    }
  }

  const checkIn = async () => {
    setBusy(true)
    setMessage(null)
    try {
      const r = await attend()
      setData(r.summary)
      if (filter !== 'INVEST_ONLY') { setItems(r.summary.history.items); setNext(r.summary.history.nextBefore); choose('ALL') }
      setMessage({
        ok: true,
        text: r.already ? t('오늘은 이미 출석했어요.')
          : (r.streak > 1 ? t('{0}일 연속 출석이라 {1}을 받았어요.', { 0: num(r.streak), 1: credits(r.earned) })
            : t('출석했어요. {0}을 받았어요.', { 0: credits(r.earned) }))
            + (r.newBadges.length > 0 ? ' ' + t('{0} 휘장을 받았어요!', { 0: badgeName(r.newBadges[r.newBadges.length - 1]) }) : ''),
      })
    } catch (e) {
      setMessage({ ok: false, text: e instanceof ApiError ? e.message : t('출석하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="container dashboard-page points-page">
      <h1 className="page-title">{t('내 포인트')}</h1>
      <p className="muted small points-notice">{t('포인트는 devlog 안에서만 써요. 현금이나 코인으로 바꾸거나 다른 사람에게 줄 수 없어요.')}</p>
      {error && <p className="error" role="alert">{t('포인트를 불러오지 못했어요.')}</p>}
      {!data && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {data && (
        <>
          <div className="kpi-grid dashboard-kpis points-kpis" style={{ '--kpi-cols': 3 } as CSSProperties}>
            <div className="kpi" data-tip={t('출석·글 발행·모의투자 수익으로 번 크레딧이에요. 상점에서 블로그 꾸미기 아이템을 살 수 있어요')}>
              <span className="kpi-label">{t('크레딧')}</span>
              <span className="kpi-value">{num(data.balance.shop)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('꾸미기 아이템·모의투자에 써요')}</span></span>
            </div>
            <div className="kpi" data-tip={t('가입할 때 받은 모의투자 자금이에요. 모의투자에만 쓰고 상점에서는 못 써요')}>
              <span className="kpi-label">{t('투자 전용 포인트')}</span>
              <span className="kpi-value">{points(data.balance.invest)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('모의투자에만 써요')}</span></span>
            </div>
            <div className="kpi" data-tip={t('하루도 빠지지 않고 출석한 날 수예요. 오늘 출석 전이면 어제까지 센 값이에요')}>
              <span className="kpi-label">{t('연속 출석')}</span>
              <span className="kpi-value">{t('{0}일', { 0: num(data.attendance.streak) })}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('내일 이어서 오면 {0}', { 0: credits(data.attendance.nextReward) })}</span></span>
            </div>
          </div>

          <div className="settings-layout points-tabs">
            <nav className="settings-nav" aria-label={t('포인트 메뉴')}>
              {POINTS_TABS.map((x) => (
                <a key={x.id} href={`/points#${x.id}`} aria-current={x.id === tab ? 'page' : undefined} data-tip={x.hint}
                  onClick={(e) => { e.preventDefault(); open(x.id) }}>
                  <NavIcon name={x.icon} /><span>{x.label}</span>
                </a>
              ))}
            </nav>
            <div className="settings-panel" role="region" aria-label={POINTS_TABS.find((x) => x.id === tab)!.label}>
              {tab === 'attend' && (
            <section className="admin-card points-attend" aria-label={t('출석')}>
              <h2>{t('오늘 출석')}</h2>
              <ol className="points-dots" aria-label={t('최근 7일 출석')}>
                {attendanceDots(data.attendance).map((d) => (
                  <li key={d.date} className={`points-dot${d.done ? ' done' : ''}${d.today ? ' today' : ''}`}
                    title={`${d.date} ${d.done ? t('출석') : t('출석 안 함')}`}>
                    <span className="points-dot-mark" aria-hidden="true">{d.done ? '✓' : ''}</span>
                    <span className="points-dot-day">{d.today ? t('오늘') : Number(d.date.slice(8))}</span>
                  </li>
                ))}
              </ol>
              <p className="small muted">
                {data.attendance.attendedToday
                  ? t('오늘 {0}을 받았어요. 내일도 오면 {1}이에요.', { 0: credits(data.attendance.todayReward), 1: credits(data.attendance.nextReward) })
                  : t('지금 출석하면 {0}을 받아요. 하루라도 빠지면 다시 {1}부터예요.', {
                    0: credits(data.attendance.todayReward), 1: credits(ruleValue(data.rules, 'ATTENDANCE')),
                  })}
              </p>
              <ol className="points-ladder" aria-label={t('연속 출석 보상')}>
                {[1, 2, 3, 7, 10, 30, 100, data.attendance.maxStreak].filter((d, i, a) => d > 0 && d <= data.attendance.maxStreak && a.indexOf(d) === i).map((d) => (
                  <li key={d} className={data.attendance.streak >= d ? 'reached' : ''}
                    data-tip={t('{0}일 연속 출석하면 그날 {1}', { 0: d, 1: credits(attendanceReward(ruleValue(data.rules, 'ATTENDANCE'), data.attendance.maxReward, d)) })}>
                    <span className="muted small">{t('{0}일', { 0: d })}</span>
                    <b>{compactNumber(attendanceReward(ruleValue(data.rules, 'ATTENDANCE'), data.attendance.maxReward, d))}</b>
                  </li>
                ))}
              </ol>
              <button type="button" className="btn btn-primary points-attend-btn" disabled={busy || data.attendance.attendedToday} onClick={() => void checkIn()}
                data-tip={data.attendance.attendedToday ? t('내일 0시(한국 시간)에 다시 출석할 수 있어요') : t('하루에 한 번, 한국 시간 0시에 새로 출석할 수 있어요')}>
                {data.attendance.attendedToday ? t('오늘 출석 완료') : busy ? t('출석하는 중…') : t('출석하기')}
              </button>
              {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
            </section>
              )}
              {tab === 'badges' && (
            <section className="admin-card points-badges-card" aria-label={t('휘장')}>
              <h2>{t('연속 출석 휘장')}</h2>
              <ul className="points-badges">
                {BADGE_DAYS.map((d) => {
                  const got = data.badges.find((b) => b.days === d)
                  return (
                    <li key={d} className={got ? 'got' : ''}>
                      <StreakBadgeIcon days={d} size={56} locked={!got} />
                      <span className="small">{d >= 365 ? t('1년') : t('{0}일', { 0: d })}</span>
                      <span className="muted small points-badge-date">{got ? kstDate(got.earnedAt) : t('{0}일 남음', { 0: num(Math.max(0, d - data.attendance.streak)) })}</span>
                    </li>
                  )
                })}
              </ul>
              <p className="muted small">
                {data.nextBadge !== null
                  ? t('다음 휘장까지 {0}일 더 이어서 출석하면 돼요. 받은 휘장은 연속이 끊겨도 남고 블로그 이름 옆에 보여요.', {
                    0: data.nextBadge - data.attendance.streak,
                  })
                  : t('모든 휘장을 받았어요.')}
              </p>
            </section>
              )}
              {tab === 'earn' && (
            <section className="admin-card points-ways" aria-label={t('크레딧 얻는 법')}>
              <h2>{t('크레딧 얻는 법')}</h2>
              <ul className="points-rules">
                <li>
                  <span data-tip={t('연속 출석 일수를 제곱해서 곱해요. 1일 100, 2일 400, 10일 1만')}>{t('출석')}</span>
                  <b>{t('{0} × 연속일²', { 0: num(ruleValue(data.rules, 'ATTENDANCE')) })}</b>
                  <span className="muted small">{t('하루 최대 {0} ({1}일 연속)', { 0: credits(data.attendance.maxReward), 1: num(data.attendance.maxStreak) })}</span>
                </li>
                <li>
                  <span data-tip={t('처음으로 전체 공개한 글만 쳐요. 짧은 글·AI 일기·비공개 글은 빼요')}>{t('글 발행')}</span>
                  <b>{credits(ruleValue(data.rules, 'POST'), true)}</b>
                  <span className="muted small">{t('오늘 {0}/{1}편 · {2}자 이상', {
                    0: data.postsRewardedToday, 1: ruleValue(data.rules, 'POST_DAILY_MAX'), 2: num(ruleValue(data.rules, 'POST_MIN_CHARS')),
                  })}</span>
                </li>
                <li>
                  <span data-tip={t('모의투자에서 팔아서 확정된 수익의 일부가 크레딧으로 들어와요. 손실은 빼지 않아요')}>{t('모의투자 수익')}</span>
                  <b>{t('수익의 {0}%', { 0: ruleValue(data.rules, 'TRADE_PROFIT_PERCENT') })}</b>
                  <span className="muted small">{t('오늘 {0}/{1}', { 0: credits(data.tradeProfitToday), 1: credits(ruleValue(data.rules, 'TRADE_PROFIT_DAILY_MAX')) })}</span>
                </li>
              </ul>
            </section>
              )}
              {tab === 'history' && (
          <section className="admin-card points-history" aria-label={t('포인트 내역')}>
            <div className="points-history-head">
              <h2>{t('포인트 내역')}</h2>
              <div className="segmented" role="group" aria-label={t('지갑')}>
                {filters().map((f) => (
                  <button key={f.key} type="button" aria-pressed={filter === f.key} data-tip={f.tip} onClick={() => void pick(f.key)}>{f.label}</button>
                ))}
              </div>
            </div>
            {items.length === 0 ? <p className="muted small">{t('아직 내역이 없어요.')}</p> : (
              <ul className="points-entries">
                {items.map((e) => (
                  <li key={e.id}>
                    <span className="points-entry-main">
                      <strong>{reasonLabel(e.reason)}</strong>
                      <span className={`badge points-wallet ${e.wallet === 'SHOP' ? 'shop' : 'invest'}`}>{walletLabel(e.wallet)}</span>
                      {e.memo && e.memo !== reasonLabel(e.reason) && <span className="muted small points-entry-memo">{e.memo}</span>}
                    </span>
                    <span className={`points-amount ${e.amount > 0 ? 'plus' : 'minus'}`}>{amountOf(e.wallet, e.amount, true)}</span>
                    <span className="muted small points-entry-time">{entryTime(e.createdAt)}</span>
                  </li>
                ))}
              </ul>
            )}
            {next !== null && <button type="button" className="btn btn-outline btn-small" onClick={() => void more()} data-tip={t('이전 내역을 더 불러와요')}>{t('더 보기')}</button>}
          </section>
              )}
            </div>
          </div>
        </>
      )}
    </main>
  )
}
