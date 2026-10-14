import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { useAuth } from '../lib/auth'
import { BarChart, type BarPoint } from '../components/BarChart'
import {
  dayLabels, delta, loadDashboard, num, periodOf, PERIODS, type Metric, type MyDashboard, type Period,
} from '../lib/dashboard'
import { t } from '../lib/i18n'
import { Link, navigate, useLocation } from '../lib/router'
import { PLATFORMS } from '../lib/shorts'

const METRICS: { key: Metric; label: () => string; tip: () => string }[] = [
  { key: 'views', label: () => t('조회수'), tip: () => t('내 글(비공개 포함)이 읽힌 횟수예요. 같은 사람이 잇달아 다시 열면 한 번으로 세요') },
  { key: 'likes', label: () => t('좋아요'), tip: () => t('내 글이 받은 좋아요 수예요') },
  { key: 'comments', label: () => t('받은 댓글'), tip: () => t('내 글에 다른 사람이 단 댓글 수예요. 내가 단 답글과 숨긴 댓글은 빼요') },
]

/**
 * 내 대시보드 (#239). 관리자 통계와 별도로, 내 글이 받은 조회·좋아요·댓글과 쇼츠를 한 화면에 모은다.
 * 기간은 주소의 ?days=7|30|90 (기본 30일)이고, 이전 같은 길이 기간과 견준다.
 */
export function DashboardPage() {
  const { search } = useLocation()
  const days = periodOf(search.get('days'))
  const [data, setData] = useState<MyDashboard | null>(null)
  const [error, setError] = useState(false)
  const [metric, setMetric] = useState<Metric>('views')
  const current = useRef(days)
  current.current = days

  useEffect(() => { document.title = t('내 대시보드 - devlog') }, [])
  useEffect(() => {
    setError(false)
    loadDashboard(days)
      .then((d) => { if (current.current === days) setData(d) })
      .catch(() => { if (current.current === days) setError(true) })
  }, [days])

  const m = METRICS.find((x) => x.key === metric) ?? METRICS[0]
  const points = (d: MyDashboard): BarPoint[] => d.daily.map((day) => ({ ...dayLabels(day.date), value: day[metric] }))

  return (
    <main className="container dashboard-page">
      <h1 className="page-title">{t('내 대시보드')}</h1>
      <div className="admin-toolbar">
        <div className="segmented" role="group" aria-label={t('기간')}>
          {PERIODS.map((p: Period) => (
            <button key={p} type="button" aria-pressed={p === days} data-tip={t('오늘을 포함한 최근 {0}일을 봐요', { 0: p })}
              onClick={() => navigate(p === 30 ? '/dashboard' : `/dashboard?days=${p}`)}>{t('{0}일', { 0: p })}</button>
          ))}
        </div>
        {data && <span className="muted small">{t('{0} ~ {1} · 이전 {2}일과 비교', { 0: data.from.replaceAll('-', '.'), 1: data.to.replaceAll('-', '.'), 2: days })}</span>}
      </div>
      {error && <p className="error" role="alert">{t('통계를 불러오지 못했어요.')}</p>}
      {!data && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {data && (
        <>
          <div className="kpi-grid dashboard-kpis" style={{ '--kpi-cols': METRICS.length } as CSSProperties} aria-label={t('최근 {0}일 합계', { 0: days })}>
            {METRICS.map((x) => {
              const d = delta(data.current[x.key], data.previous[x.key])
              return (
                <button key={x.key} type="button" className="kpi" aria-pressed={x.key === metric} onClick={() => setMetric(x.key)}
                  data-tip={`${x.tip()}. ${t('눌러서 아래에 날짜별 그래프 보기')}`}>
                  <span className="kpi-label">{x.label()}</span>
                  <span className="kpi-value">{num(data.current[x.key])}</span>
                  <span className="kpi-foot">
                    <span className={`kpi-delta ${d.trend === 'up' ? 'good' : d.trend === 'down' ? 'bad' : 'flat'}`}>
                      {d.trend !== 'flat' && <span aria-hidden="true">{d.trend === 'up' ? '▲' : '▼'} </span>}{d.text}
                    </span>
                    <span className="kpi-prev">{t('이전 {0}', { 0: num(data.previous[x.key]) })}</span>
                  </span>
                </button>
              )
            })}
          </div>
          <p className="muted small dashboard-totals">
            {t('지금까지 글 {0}편 · 조회 {1} · 좋아요 {2} · 받은 댓글 {3}', { 0: num(data.posts), 1: num(data.totals.views), 2: num(data.totals.likes), 3: num(data.totals.comments) })}
          </p>

          <section className="admin-card">
            <BarChart title={m.label()} unit="" points={points(data)} />
          </section>

          <div className="admin-columns">
            <section className="admin-card">
              <h2 data-tip={t('기간 안에 많이 읽힌 내 글이에요. 좋아요·댓글은 지금까지 받은 수예요')}>{t('많이 읽힌 글')}</h2>
              {data.topPosts.length === 0
                ? <p className="muted small">{data.posts === 0 ? <EmptyPosts /> : t('이 기간에는 읽힌 글이 없어요.')}</p>
                : (
                  <ol className="dashboard-top">
                    {data.topPosts.map((p) => (
                      <li key={p.id}>
                        <Link to={p.link} className="dashboard-top-title">{p.title}</Link>
                        {p.visibility !== 'PUBLIC' && <span className="badge">{t('비공개')}</span>}
                        <span className="muted small dashboard-top-nums">
                          {t('조회 {0} · 좋아요 {1} · 댓글 {2}', { 0: num(p.views), 1: num(p.likes), 2: num(p.comments) })}
                        </span>
                      </li>
                    ))}
                  </ol>
                )}
            </section>
            {data.shorts && <ShortsCard shorts={data.shorts} />}
          </div>
        </>
      )}
    </main>
  )
}

function EmptyPosts() {
  const { me } = useAuth()
  return <>{t('아직 발행한 글이 없어요.')} {me?.member && <Link to="/write">{t('첫 글 쓰기')}</Link>}</>
}

function ShortsCard({ shorts }: { shorts: NonNullable<MyDashboard['shorts']> }) {
  return (
    <section className="admin-card">
      <h2 data-tip={t('쇼츠 스튜디오에서 만든 영상과, 올린 뒤 남긴 영상 주소를 세요')}>{t('쇼츠')}</h2>
      <dl className="dashboard-shorts">
        <div><dt>{t('대본')}</dt><dd>{num(shorts.scripts)}</dd></div>
        <div><dt>{t('완성한 영상')}</dt><dd>{num(shorts.ready)}</dd></div>
        {PLATFORMS.map((p) => <div key={p.id}><dt>{t('{0}에 올림', { 0: p.name })}</dt><dd>{num(shorts.uploads[p.id] ?? 0)}</dd></div>)}
      </dl>
      {shorts.recent.length > 0 ? (
        <>
          <h3 className="small">{t('최근에 올린 영상')}</h3>
          <ul className="dashboard-uploads">
            {shorts.recent.map((u) => (
              <li key={`${u.shortsId}:${u.platform}`}>
                <a href={u.url} target="_blank" rel="noopener noreferrer" data-tip={t('조회수는 그 앱에서 확인해요')}>{u.title}</a>
                <span className="muted small"> · {PLATFORMS.find((p) => p.id === u.platform)?.name}</span>
              </li>
            ))}
          </ul>
        </>
      ) : <p className="muted small">{t('올린 영상 주소를 스튜디오에 남기면 여기에 모여요.')}</p>}
      <Link to="/studio" className="btn btn-outline btn-small">{t('쇼츠 스튜디오로')}</Link>
    </section>
  )
}
