import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { AdminNav } from '../components/AdminNav'
import { BarChart, type BarPoint } from '../components/BarChart'
import { ago, change, consoleApi, count, PERIODS, workerHealth, workerLabel, type Dashboard, type IndexReport, type Period, type Sums, type WorkerStatus } from '../lib/admin'
import { Link, navigate, useLocation } from '../lib/router'
import { decode, GROUP_LABEL, pageLabel, sourceGroup, sourceLabel, type SourceGroup } from '../lib/visitSources'

type Metric = keyof Sums

/** tip은 합계 칸 툴팁, chart는 그래프 제목(날짜별 값의 뜻이 합계와 다를 때만) */
/** upIsBad는 늘면 나쁜 지표(신고): 오르면 빨강, 내리면 초록 */
const METRICS: { key: Metric; label: string; unit: string; tip?: string; chart?: string; upIsBad?: boolean }[] = [
  { key: 'visitors', label: '방문자', unit: '명', tip: '기간 동안 사이트에 온 사람 수예요. 여러 날 와도 한 명으로 세요', chart: '방문자 (날마다 센 사람 수)' },
  { key: 'visits', label: '방문', unit: '회', tip: '사이트에 들어온 횟수예요. 30분 넘게 쉬었다 다시 오면 한 번 더 세요' },
  { key: 'searchVisits', label: '검색 유입', unit: '회', tip: '구글·네이버·다음·빙 검색 결과를 눌러 들어온 방문 수예요', chart: '검색 유입 (구글·네이버·다음·빙)' },
  { key: 'activeMembers', label: '활동한 회원', unit: '명', tip: '기간 동안 로그인한 채로 블로그를 쓴 회원 수예요. 여러 날 와도 한 명으로 세요', chart: '활동한 회원 (날마다 센 회원 수)' },
  { key: 'views', label: '조회수', unit: '회' },
  { key: 'posts', label: '새 글', unit: '편' },
  { key: 'signups', label: '가입', unit: '명' },
  { key: 'comments', label: '댓글', unit: '개' },
  { key: 'likes', label: '좋아요', unit: '개' },
  { key: 'reports', label: '신고', unit: '건', upIsBad: true },
]

/** 지표 칸 묶음 (073): 보는 순서대로 방문 → 회원·글 → 반응. today는 '오늘 방문자' 칸 */
const KPI_GROUPS: { id: string; title: string; tip: string; keys: (Metric | 'today')[]; hero?: boolean }[] = [
  { id: 'visit', title: '방문', tip: '사이트에 누가 얼마나 왔는지', keys: ['visitors', 'visits', 'today', 'searchVisits'], hero: true },
  { id: 'member', title: '회원·글', tip: '회원이 얼마나 쓰고 들어왔는지', keys: ['activeMembers', 'signups', 'posts'] },
  { id: 'reaction', title: '반응', tip: '글을 읽고 남긴 반응', keys: ['views', 'comments', 'likes', 'reports'] },
]

/** 날짜별 값을 작은 막대로 (마지막 날만 진하게). 읽는 값은 칸의 숫자이고 이건 흐름만 보여 준다 */
function Spark({ values }: { values: number[] }) {
  const max = Math.max(1, ...values)
  const n = values.length
  return (
    <svg className="kpi-spark" viewBox={`0 0 ${n * 3} 24`} preserveAspectRatio="none" aria-hidden="true" focusable="false">
      {values.map((v, i) => {
        const h = v === 0 ? 0 : Math.max(2, (v / max) * 24)
        return <rect key={i} x={i * 3} y={24 - h} width={2} height={h} rx={0.6} className={i === n - 1 ? 'now' : undefined} />
      })}
    </svg>
  )
}

/** ▲ +12% 처럼 방향을 기호로도 보여 준다(색만으로 구분하지 않게) */
function Delta({ current, previous, upIsBad }: { current: number; previous: number; upIsBad?: boolean }) {
  const c = change(current, previous)
  if (!c.text) return <span className="kpi-delta flat">변화 없음</span>
  const good = c.trend === 'flat' ? 'flat' : (c.trend === 'up') !== !!upIsBad ? 'good' : 'bad'
  const arrow = c.trend === 'up' ? '▲' : c.trend === 'down' ? '▼' : ''
  return <span className={`kpi-delta ${good}`}>{arrow && <span aria-hidden="true">{arrow} </span>}{c.text}</span>
}

function points(d: Dashboard, key: Metric): BarPoint[] {
  return d.daily.map((day) => {
    const [, m, dd] = day.date.split('-').map(Number)
    return { label: `${m}.${dd}`, fullLabel: `${m}월 ${dd}일`, value: day[key] }
  })
}

const GROUPS: SourceGroup[] = ['search', 'sns', 'direct', 'other']

const percent = (n: number, total: number) => (total > 0 ? Math.round((n / total) * 100) : 0)

/** 유입 경로 (070): 묶음별 비율 한 줄, 그 아래 경로별 막대 */
function Sources({ data }: { data: Dashboard }) {
  const total = data.sources.reduce((s, x) => s + x.visits, 0)
  if (total === 0) return <p className="muted small">이 기간에 기록된 방문이 없어요. 유입 경로는 v1.44.0부터 쌓여요.</p>
  const byGroup = (g: SourceGroup) => data.sources.filter((x) => sourceGroup(x.source) === g).reduce((s, x) => s + x.visits, 0)
  return (
    <>
      <p className="source-groups">
        {GROUPS.map((g) => (
          <span key={g} className={`source-group src-${g}`}>{GROUP_LABEL[g]} <strong>{percent(byGroup(g), total)}%</strong></span>
        ))}
      </p>
      <ul className="source-list">
        {data.sources.map((x) => {
          const p = percent(x.visits, total)
          return (
            <li key={`${x.source}/${x.host}`} data-tip={`${GROUP_LABEL[sourceGroup(x.source)]} · 새 방문 ${count(x.visits)}회 (${p}%)`}>
              <span className="source-name">{sourceLabel(x.source, x.host)}</span>
              <span className="source-count">{count(x.visits)}회 · {p}%</span>
              <span className="source-bar" aria-hidden="true"><span className={`src-${sourceGroup(x.source)}`} style={{ width: `${Math.max(p, 1)}%` }} /></span>
            </li>
          )
        })}
      </ul>
    </>
  )
}

/** 관리자 대시보드 (062): 처리할 일, 기간 합계(이전 기간 대비), 날짜별 추이, 많이 본 글·많이 쓴 회원, 전체 현황. */
/** 쇼츠 렌더 워커 (#241): 대기 중인 영상이 있는데 워커가 안 보이면 맨 위 할 일에도 띄운다 */
function workerAlert(w: WorkerStatus | null): string | null {
  if (!w) return null
  const h = workerHealth(w)
  if (w.queued > 0 && h.state !== 'ok') return `영상 ${count(w.queued)}개가 기다리는데 렌더 워커가 ${h.state === 'no-token' ? '꺼져 있어요' : '안 보여요'}`
  if (w.previousToken) return '렌더 워커가 옛 토큰으로 들어오고 있어요'
  return null
}

function WorkerCard({ w }: { w: WorkerStatus }) {
  const h = workerHealth(w)
  const waited = w.oldestQueuedAt ? Math.floor((Date.now() - Date.parse(w.oldestQueuedAt)) / 60_000) : null
  return (
    <section className="admin-card">
      <h2 data-tip="회원이 [영상 만들기]를 누르면 대기에 들어가고, 내 컴퓨터의 렌더 워커가 받아 MP4로 만들어 올려요">쇼츠 렌더 워커</h2>
      <dl className="totals">
        <div data-tip="워커는 몇 초마다 일감을 물어요. 5분 넘게 묻지 않으면 꺼졌거나 연결이 끊긴 거예요">
          <dt>워커</dt><dd className={h.state === 'ok' ? '' : 'warn-text'}>{workerLabel(h)}</dd>
          {h.state === 'no-token' && <dd className="muted small">SHORTS_WORKER_TOKEN을 실행 환경 비밀값으로 넣어야 워커가 붙어요</dd>}
          {w.previousToken && <dd className="muted small">옛 토큰(SHORTS_WORKER_TOKEN_PREVIOUS)으로 들어온 워커가 있어요. 워커 설정을 새 토큰으로 바꾼 뒤 옛 토큰을 비워 주세요</dd>}
        </div>
        <div data-tip="만들기를 기다리는 영상"><dt>대기</dt><dd>{count(w.queued)}개</dd>
          {waited !== null && <dd className="muted small">가장 오래 기다린 영상 {ago(waited)}</dd>}</div>
        <div data-tip="워커가 받아서 만드는 중인 영상. 15분 안에 결과가 없으면 다시 대기로 돌아가요"><dt>만드는 중</dt><dd>{count(w.rendering)}개</dd></div>
        <div data-tip="오늘 0시(한국 시간)부터 세 번 실패해 멈춘 영상"><dt>오늘 실패</dt><dd>{count(w.failedToday)}개</dd></div>
      </dl>
    </section>
  )
}

const REFRESH_MS = 60_000

export function AdminDashboardPage() {
  const { search } = useLocation()
  const days = (PERIODS.find((p) => String(p) === search.get('days')) ?? 30) as Period
  const [data, setData] = useState<Dashboard | null>(null)
  const [error, setError] = useState(false)
  const [metric, setMetric] = useState<Metric>('visitors')
  const current = useRef(days)
  current.current = days

  useEffect(() => { document.title = '관리자 대시보드 - devlog' }, [])

  useEffect(() => {
    setError(false)
    consoleApi.dashboard(days)
      .then((d) => { if (current.current === days) setData(d) })
      .catch(() => { if (current.current === days) setError(true) })
    // 열어 둔 화면에서도 워커가 멈춘 것을 알 수 있게 1분마다 다시 읽는다. 다시 읽다 실패하면 보던 숫자를 그대로 둔다
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return
      consoleApi.dashboard(days).then((d) => { if (current.current === days) setData(d) }).catch(() => {})
    }, REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [days])

  const m = METRICS.find((x) => x.key === metric) ?? METRICS[0]
  const tile = (data: Dashboard, key: Metric | 'today', hero: boolean) => {
    if (key === 'today') {
      return (
        <div key="today" className={`kpi kpi-static${hero ? ' hero' : ''}`} data-tip="오늘 0시(한국 시간)부터 온 사람 수. 관리자·매니저도 세고, 로봇은 세지 않아요">
          <span className="kpi-label">오늘 방문자</span>
          <span className="kpi-value">{count(data.visitors.today)}<small>명</small></span>
          <span className="kpi-foot">
            <Delta current={data.visitors.today} previous={data.visitors.yesterday} />
            <span className="kpi-prev">어제 {count(data.visitors.yesterday)}명</span>
          </span>
        </div>
      )
    }
    const x = METRICS.find((mm) => mm.key === key)!
    const selected = x.key === metric
    return (
      <button key={x.key} type="button" className={`kpi${hero ? ' hero' : ''}`} aria-pressed={selected}
              data-tip={`${x.tip ? `${x.tip}. ` : ''}눌러서 아래에 날짜별 그래프 보기`} onClick={() => setMetric(x.key)}>
        <span className="kpi-label">{x.label}</span>
        <span className="kpi-value">{count(data.current[x.key])}<small>{x.unit}</small></span>
        <span className="kpi-foot">
          <Delta current={data.current[x.key]} previous={data.previous[x.key]} upIsBad={x.upIsBad} />
          <span className="kpi-prev">이전 {count(data.previous[x.key])}</span>
        </span>
        <Spark values={data.daily.map((d) => d[x.key])} />
      </button>
    )
  }

  return (
    <main className="container admin admin-wide">
      <h1 className="page-title">관리자 페이지</h1>
      <AdminNav />
      <div className="admin-toolbar">
        <div className="segmented" role="group" aria-label="기간">
          {PERIODS.map((p) => (
            <button key={p} type="button" aria-pressed={p === days} data-tip={`오늘을 포함한 최근 ${p}일을 봐요`}
                    onClick={() => navigate(p === 30 ? '/admin' : `/admin?days=${p}`)}>{p}일</button>
          ))}
        </div>
        {data && <span className="muted small">{data.from.replaceAll('-', '.')} ~ {data.to.replaceAll('-', '.')} · 이전 {days}일과 비교</span>}
      </div>
      {error && <p className="error" role="alert">통계를 불러오지 못했어요.</p>}
      {!data && !error && <p className="muted center">불러오는 중…</p>}
      {data && (
        <>
          {(data.totals.pendingReports > 0 || data.totals.openInquiries > 0 || workerAlert(data.worker)) && (
            <section className="admin-todo" aria-label="처리할 일">
              {workerAlert(data.worker) && (
                <span className="admin-todo-item" data-tip="아래 쇼츠 렌더 워커 칸에서 자세히 봐요">{workerAlert(data.worker)}</span>
              )}
              {data.totals.pendingReports > 0 && (
                <Link to="/admin/reports" className="admin-todo-item" data-tip="처리를 기다리는 신고로 가요">
                  처리할 신고 <strong>{count(data.totals.pendingReports)}</strong>건
                </Link>
              )}
              {data.totals.openInquiries > 0 && (
                <Link to="/admin/inquiries" className="admin-todo-item" data-tip="답하지 않은 문의·버그 신고로 가요">
                  답할 문의 <strong>{count(data.totals.openInquiries)}</strong>건
                </Link>
              )}
            </section>
          )}

          <div className="kpi-board" aria-label={`최근 ${days}일 합계`}>
            {KPI_GROUPS.map((g) => (
              <section key={g.id} className={`kpi-group${g.hero ? ' hero' : ''}`} aria-labelledby={`kpi-${g.id}`}>
                <h2 id={`kpi-${g.id}`} className="kpi-group-title" data-tip={g.tip}>{g.title}</h2>
                <div className="kpi-grid" style={{ '--kpi-cols': g.keys.length } as CSSProperties}>
                  {g.keys.map((k) => tile(data, k, !!g.hero))}
                </div>
              </section>
            ))}
          </div>

          <section className="admin-card">
            <BarChart title={m.chart ?? m.label} unit={m.unit} points={points(data, metric)} />
            {(metric === 'activeMembers' || metric === 'visitors' || metric === 'visits') && (
              <p className="muted small">
                {metric === 'activeMembers'
                  ? '날짜별 활동 기록은 v1.40.0부터 쌓여요. 그 전 날짜에는 회원마다 마지막으로 활동한 날만 있어요.'
                  : '방문 기록은 v1.38.0부터 쌓여요.'}
              </p>
            )}
            {metric === 'searchVisits' && <p className="muted small">유입 경로 기록은 v1.44.0부터 쌓여요.</p>}
          </section>

          <div className="admin-columns">
            <section className="admin-card">
              <h2 data-tip="새 방문(처음 왔거나 30분 넘게 쉬었다 온 것)마다 어디를 거쳐 들어왔는지 세요">어디서 왔나요</h2>
              <Sources data={data} />
            </section>
            <section className="admin-card">
              <h2 data-tip="블로그 안에서 화면을 옮길 때마다 세요. 관리자·글쓰기 화면은 빼요">많이 본 화면</h2>
              {data.topPages.length === 0 ? <p className="muted small">이 기간에 기록된 화면이 없어요. 화면 기록은 v1.44.0부터 쌓여요.</p> : (
                <ol className="rank-list">
                  {data.topPages.map((p) => (
                    <li key={p.path}>
                      <a href={p.path} className="rank-title">{pageLabel(p.path, p.title)}</a>
                      <span className="muted small page-path">{decode(p.path)} · {count(p.views)}회</span>
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </div>

          <div className="admin-columns">
            <section className="admin-card">
              <h2>많이 본 글</h2>
              {data.topPosts.length === 0 ? <p className="muted small">이 기간에 조회된 공개 글이 없어요.</p> : (
                <ol className="rank-list">
                  {data.topPosts.map((p) => (
                    <li key={p.id}>
                      <a href={p.link} className="rank-title">{p.title}</a>
                      <span className="muted small">@{p.authorHandle} · 조회 {count(p.views)} · 좋아요 {count(p.likes)} · 댓글 {count(p.comments)}</span>
                    </li>
                  ))}
                </ol>
              )}
            </section>
            <section className="admin-card">
              <h2>많이 쓴 회원</h2>
              {data.topAuthors.length === 0 ? <p className="muted small">이 기간에 발행한 글이 없어요.</p> : (
                <ol className="rank-list">
                  {data.topAuthors.map((a) => (
                    <li key={a.handle}>
                      <Link to={`/admin/members/${a.handle}`} className="rank-title" data-tip="회원 통계·관리 보기">{a.nickname ?? a.handle}</Link>
                      <span className="muted small">@{a.handle} · 새 글 {count(a.posts)}편</span>
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </div>

          {data.worker && <WorkerCard w={data.worker} />}

          <IndexCard />

          <section className="admin-card">
            <h2>전체 현황</h2>
            <dl className="totals">
              <div><dt>회원</dt><dd>{count(data.totals.members.total)}명</dd>
                <dd className="muted small">활동 {count(data.totals.members.active)} · 정지 {count(data.totals.members.suspended)} · 탈퇴 신청 {count(data.totals.members.withdrawing)} · 매니저 {count(data.totals.members.managers)}</dd></div>
              <div><dt>발행한 글</dt><dd>{count(data.totals.posts.published)}편</dd>
                <dd className="muted small">공개 {count(data.totals.posts.publicPosts)} · 비공개 {count(data.totals.posts.privatePosts)} · 숨김 {count(data.totals.posts.hidden)}</dd></div>
              <div><dt>임시글·휴지통</dt><dd>{count(data.totals.posts.drafts)}편</dd>
                <dd className="muted small">휴지통 {count(data.totals.posts.trash)}편</dd></div>
              <div><dt>방문자</dt><dd>{count(data.current.visitors)}명</dd>
                <dd className="muted small">최근 {days}일 · 회원 {count(data.visitors.members)} · 비회원 {count(Math.max(0, data.current.visitors - data.visitors.members))}</dd></div>
              <div><dt>조회수</dt><dd>{count(data.totals.views)}회</dd></div>
              <div><dt>좋아요</dt><dd>{count(data.totals.likes)}개</dd></div>
              <div><dt>댓글</dt><dd>{count(data.totals.comments)}개</dd></div>
            </dl>
          </section>
        </>
      )}
    </main>
  )
}

/**
 * 검색 수집 현황 (spec 107): 사이트맵·검색 엔진에 나가는 글과 빠지는 글의 수, 검수를 기다리는 외부 회원 글.
 * 외부 회원 글은 [수집 허락]을 눌러야 검색 엔진에 나간다. 운영진 글은 수집 기준(일기 아님·본문 1,000자 이상)만 본다.
 */
function IndexCard() {
  const [report, setReport] = useState<IndexReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const load = () => consoleApi.indexReport().then((r) => { setReport(r); setError(null) }, () => setError('검색 수집 현황을 불러오지 못했어요.'))
  useEffect(() => { void load() }, [])
  const approve = async (id: number) => {
    setBusy(id)
    try {
      await consoleApi.setIndexApproval(id, true)
      await load()
    } catch {
      setError('수집을 허락하지 못했어요. 잠시 후 다시 해 주세요.')
    } finally {
      setBusy(null)
    }
  }
  return (
    <section className="admin-card" aria-labelledby="index-card-title">
      <h2 id="index-card-title" data-tip="공개 글 가운데 검색 엔진·사이트맵에 내보내는 글과 빼는 글이에요. 글은 모두 독자에게 그대로 보여요">검색 수집</h2>
      {error && <p className="error" role="alert">{error}</p>}
      {report && (
        <>
          <dl className="totals">
            <div><dt data-tip="사이트맵에 들어가고 검색 엔진이 색인하는 글">수집하는 글</dt><dd>{count(report.indexed)}편</dd></div>
            <div><dt data-tip="noindex, follow: 검색 결과에는 안 나오고, 글 안의 링크는 검색 엔진이 따라가요">빼는 글</dt>
              <dd>{count(report.aiDiary + report.shortPost + report.unreviewed)}편</dd>
              <dd className="muted small">AI 일기 {count(report.aiDiary)} · 짧은 글 {count(report.shortPost)} · 검수 전 외부 글 {count(report.unreviewed)}</dd></div>
          </dl>
          {report.pending.length > 0 && (
            <>
              <h3 className="small">검수를 기다리는 외부 회원 글</h3>
              <ol className="rank-list">
                {report.pending.map((p) => (
                  <li key={p.id}>
                    <a href={p.url} className="rank-title">{p.title}</a>
                    <span className="muted small">@{p.authorHandle} · 본문 {count(p.bodyChars)}자</span>
                    <button type="button" className="btn btn-text" disabled={busy === p.id} onClick={() => void approve(p.id)}
                            data-tip="읽어 보고 괜찮으면 검색 엔진·사이트맵에 내보내요">수집 허락</button>
                  </li>
                ))}
              </ol>
            </>
          )}
        </>
      )}
    </section>
  )
}
