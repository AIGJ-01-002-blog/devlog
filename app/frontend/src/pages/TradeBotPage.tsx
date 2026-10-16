import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { NavIcon, type IconName } from '../components/NavIcons'
import { PaperBotSettingsForm } from '../components/PaperBotSettingsForm'
import { TradeBotCharts } from '../components/TradeBotCharts'
import { t } from '../lib/i18n'
import { Link } from '../lib/router'
import {
  accountInterval, eventTime, isStale, loadPaperBot, loadTradeBot, setPaperBot, sideLabel, sideUp, type BotAccount, type BotEvent, type BotView,
  type PaperBotView,
} from '../lib/tradeBot'
import { exchangeName, money, pnlClass, qty } from '../lib/trades'

/** 봇은 5분마다 알리니 화면은 1분마다 다시 받는다 */
const REFRESH_MS = 60_000

/** 한 화면에 다 쌓으면 휴대폰에서 길어지니 내 포인트처럼 왼쪽 탭으로 나눈다(블로그 주인 요청). 휴대폰에서는 탭이 위에서 옆으로 밀린다. /trade#fills처럼 바로 열린다. 첫 탭은 코인 5종 실시간 차트(블로그 주인 요청) */
export const TRADE_BOT_TABS = [
  { id: 'chart', label: t('차트'), hint: t('코인 5종의 거래소별 실시간 차트와 봇의 모의 체결 표시'), icon: 'candle' },
  { id: 'accounts', label: t('계좌'), hint: t('모의 계좌의 현금·보유 포지션·적용 중인 규칙'), icon: 'coin' },
  { id: 'fills', label: t('체결'), hint: t('봇이 낸 모의 체결과 이유'), icon: 'trend' },
  { id: 'ai', label: t('Gemini 판단'), hint: t('진입 직전에 Gemini가 허락하거나 막은 기록'), icon: 'ai' },
  { id: 'summary', label: t('하루 요약'), hint: t('Gemini가 쓴 하루 매매 요약'), icon: 'posts' },
] as const satisfies readonly { id: string; label: string; hint: string; icon: IconName }[]
export type TradeBotTab = (typeof TRADE_BOT_TABS)[number]['id']

export function tradeBotTabFromHash(hash: string): TradeBotTab {
  const key = hash.replace(/^#/, '')
  return TRADE_BOT_TABS.find((x) => x.id === key)?.id ?? 'chart'
}

/**
 * 자동매매 모의 화면 (spec 101). 주소 /trade.
 * 내 AI(MCP)가 매매 기준을 정하고, 봇 코드가 그 기준으로 5분마다 모의 주문을 내며, 무료 Gemini는 진입 필터와 하루 요약만 맡는다.
 * 여기서는 봇이 알린 모의 계좌·체결·Gemini 판단·요약을 보여 주기만 한다. 주문 버튼은 두지 않는다.
 */
export function TradeBotPage() {
  const [data, setData] = useState<BotView | null>(null)
  const [error, setError] = useState(false)
  const [tab, setTab] = useState<TradeBotTab>(() => tradeBotTabFromHash(location.hash))

  const [paperView, setPaperView] = useState<PaperBotView | null>(null)
  const paper = paperView ? paperView.enabled : null
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState(false)
  // 모의 봇 조회 순번: 저장·전환 뒤에 늦게 온 옛 조회가 새 기준과 입력값을 덮지 않게 한다
  const paperSeq = useRef(0)
  const applyPaper = (v: PaperBotView | null) => {
    paperSeq.current += 1
    setPaperView(v)
  }

  const reload = () => {
    loadTradeBot().then((d) => { setData(d); setError(false) }).catch(() => setError(true))
    const seq = ++paperSeq.current
    loadPaperBot()
      .then((v) => { if (seq === paperSeq.current) setPaperView(v) })
      .catch(() => { if (seq === paperSeq.current) setPaperView(null) })
  }
  const togglePaper = () => {
    if (paper === null || switching) return
    setSwitching(true)
    setSwitchError(false)
    setPaperBot(!paper)
      .then((p) => { applyPaper(p); reload() })
      .catch(() => setSwitchError(true))
      .finally(() => setSwitching(false))
  }
  useEffect(() => { document.title = t('자동매매 - devlog') }, [])
  useEffect(() => {
    const sync = () => setTab(tradeBotTabFromHash(location.hash))
    window.addEventListener('hashchange', sync)
    window.addEventListener('popstate', sync)
    return () => { window.removeEventListener('hashchange', sync); window.removeEventListener('popstate', sync) }
  }, [])
  const open = (id: TradeBotTab) => {
    if (id === tab) return
    history.replaceState(null, '', `/trade#${id}`)
    setTab(id)
  }
  const count = (id: TradeBotTab) => !data ? 0
    : id === 'chart' ? null : id === 'accounts' ? data.accounts.length : id === 'fills' ? data.fills.length : id === 'ai' ? data.decisions.length : data.summaries.length
  useEffect(() => {
    reload()
    const id = window.setInterval(reload, REFRESH_MS)
    return () => window.clearInterval(id)
  }, [])

  const status = data?.status
  const stale = status ? isStale(status.reportedAt) : false
  return (
    <main className="container dashboard-page tradebot-page">
      <div className="points-title">
        <h1 className="page-title">{t('자동매매')} <span className="badge tradebot-paper" data-tip={t('모의 매매만 보여 줘요. 실계좌 주문은 이 화면과 상관없어요')}>{t('모의')}</span></h1>
        <div className="tradebot-actions">
        {paper !== null && (
          <button type="button" className={`btn btn-small ${paper ? 'btn-outline' : 'btn-primary'}`} onClick={togglePaper} disabled={switching}
            data-tip={t('devlog 서버가 5분마다 거래소 공개 시세로 모의 봇을 돌려요. 끄면 다음 바퀴부터 멈추고 기록은 남아요')}>
            {paper ? t('모의 봇 끄기') : t('모의 봇 켜기')}
          </button>
        )}
        <button type="button" className="btn btn-text btn-small" onClick={reload} data-tip={t('봇이 알린 내용을 다시 받아요. 1분마다 저절로 바뀌어요')}>{t('새로 고침')}</button>
        </div>
      </div>
      <p className="muted small trades-notice">{t('매매 기준은 내 AI가 정하고, 봇이 그 기준대로 5분마다 모의 주문을 내요. Gemini는 진입 직전 필터와 하루 요약만 맡아요. 투자 권유가 아니에요.')}</p>
      <TradeBotGuide />
      {paperView && <PaperBotSettingsForm view={paperView} onSaved={(v) => { applyPaper(v); reload() }} />}
      {switchError && <p className="error" role="alert">{t('모의 봇을 바꾸지 못했어요. 다시 시도해 주세요.')}</p>}
      {error && <p className="error" role="alert">{t('자동매매 기록을 불러오지 못했어요.')}</p>}
      {!data && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {data && status && status.reportedAt === null && (
        <section className="tradebot-empty">
          <h2>{t('아직 봇이 알린 내용이 없어요')}</h2>
          <p className="muted">{t('위의 [모의 봇 켜기]를 누르면 devlog 서버가 바로 모의 매매를 시작해요. 내 컴퓨터의 매매 봇이 report_trade_bot으로 보내도 여기에 보여요. 매매 일지와는 따로 저장되고 공개되지 않아요.')}</p>
          <Link to="/trades" className="btn btn-outline btn-small" data-tip={t('매매 봇이 보낸 체결을 날짜별로 보고 글·쇼츠로 남기기')}>{t('매매 일지')}</Link>
        </section>
      )}
      {data && status && status.reportedAt !== null && (
        <>
          <div className="kpi-grid dashboard-kpis" style={{ '--kpi-cols': 3 } as CSSProperties}>
            <div className="kpi" data-tip={t('봇이 마지막으로 소식을 보낸 시각이에요. 15분 넘게 없으면 봇이 멈췄는지 확인해 주세요')}>
              <span className="kpi-label">{t('마지막 알림')}</span>
              <span className="kpi-value tradebot-when">{eventTime(status.reportedAt)}</span>
              <span className="kpi-foot">
                {stale ? <span className="pnl bad">{t('15분 넘게 소식 없음')}</span> : <span className="pnl good">{t('돌아가는 중')}</span>}
                {status.botVersion && <span className="kpi-prev"> · v{status.botVersion}</span>}
              </span>
            </div>
            <div className="kpi" data-tip={t('진입 직전에 Gemini가 한 번 더 걸러요. 켜고 끄기는 내 AI에게 trade_bot_ai로 부탁해요')}>
              <span className="kpi-label">{t('AI 필터')}</span>
              <span className={`kpi-value ${status.aiEnabled ? 'pnl good' : 'muted'}`}>{status.aiEnabled ? t('켜짐') : t('꺼짐')}</span>
              <span className="kpi-foot"><span className="kpi-prev">{status.aiModel || 'Gemini'} · {t('오늘 {0}번 판단', { 0: status.aiCallsToday })}</span></span>
            </div>
            <div className="kpi" data-tip={t('최근 30일 동안 봇이 낸 모의 체결이에요 (최근 50건까지)')}>
              <span className="kpi-label">{t('모의 체결')}</span>
              <span className="kpi-value">{data.fills.length}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('계좌 {0}개', { 0: data.accounts.length })}</span></span>
            </div>
          </div>

          <div className="settings-layout points-tabs tradebot-tabs">
            <nav className="settings-nav" aria-label={t('자동매매 메뉴')}>
              {TRADE_BOT_TABS.map((x) => (
                <a key={x.id} href={`/trade#${x.id}`} aria-current={x.id === tab ? 'page' : undefined} data-tip={x.hint}
                  onClick={(e) => { e.preventDefault(); open(x.id) }}>
                  <NavIcon name={x.icon} /><span>{x.label}</span>
                  {count(x.id) !== null && <span className="tradebot-tab-count">{count(x.id)}</span>}
                </a>
              ))}
            </nav>
            <div className="settings-panel" role="region" aria-label={TRADE_BOT_TABS.find((x) => x.id === tab)!.label}>
              {tab === 'chart' && <TradeBotCharts accounts={data.accounts} />}
              {tab === 'accounts' && (
                <section className="tradebot-section">
                  <h2>{t('모의 계좌')}</h2>
                  {data.accounts.length === 0 ? <p className="muted small">{t('봇이 알린 모의 계좌가 없어요.')}</p> : (
                    <div className="tradebot-accounts">{data.accounts.map((a) => <AccountCard key={a.account} account={a} />)}</div>
                  )}
                </section>
              )}
              {tab === 'fills' && (
                <section className="tradebot-section">
                  <h2>{t('최근 체결')}</h2>
                  {data.fills.length === 0 ? <p className="muted small">{t('아직 체결이 없어요.')}</p> : (
                    <ul className="trades-fills">{data.fills.map((f) => <FillRow key={f.id} fill={f} />)}</ul>
                  )}
                </section>
              )}
              {tab === 'ai' && (
                <section className="tradebot-section">
                  <h2>{t('Gemini 판단')}</h2>
                  {data.decisions.length === 0 ? <p className="muted small">{t('아직 판단 기록이 없어요.')}</p> : (
                    <ul className="trades-fills">
                      {data.decisions.map((d) => (
                        <li key={d.id}>
                          <div className="trades-fill-head">
                            <span className={`badge ${d.verdict === 'ALLOW' ? 'tradebot-allow' : 'tradebot-block'}`}
                              data-tip={d.verdict === 'ALLOW' ? t('Gemini가 진입을 허락했어요') : t('Gemini가 진입을 막았어요')}>
                              {d.verdict === 'ALLOW' ? t('허락') : t('막음')}
                            </span>
                            {d.symbol && <strong>{d.symbol}</strong>}
                            <span className="muted small">{eventTime(d.occurredAt)}</span>
                          </div>
                          {d.text && <p className="small tradebot-text">{d.text}</p>}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}
              {tab === 'summary' && (
                <section className="tradebot-section">
                  <h2>{t('하루 요약')}</h2>
                  {data.summaries.length === 0 ? <p className="muted small">{t('아직 요약이 없어요.')}</p> : (
                    <ul className="trades-fills">
                      {data.summaries.map((x) => (
                        <li key={x.id}>
                          <span className="muted small">{eventTime(x.occurredAt)}</span>
                          <p className="small tradebot-text">{x.text}</p>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}
            </div>
          </div>
        </>
      )}
    </main>
  )
}

/** 봇이 어떻게 도는지 (블로그 주인 요청 2026-10-15 "이런 내용을 매매 관련 사이트에 공지"). 규칙형 프로그램이고 AI는 거들기만 한다는 것 */
function TradeBotGuide() {
  return (
    <details className="tradebot-guide">
      <summary>{t('이 봇은 어떻게 도나요?')}</summary>
      <ul className="small">
        <li>{t('정해진 규칙으로 도는 자동 프로그램이에요. 5분마다 시세를 보고 이동평균 교차·손절 같은 규칙대로 모의로 사고팔아요. 같은 시세면 늘 같은 결정을 내려요.')}</li>
        <li>{t('AI는 거들기만 해요. 내 AI(Claude·Codex 등)가 MCP로 기준을 정하고, 켜 두면 Gemini가 진입 직전에 한 번 더 걸러요. 주문은 언제나 봇 코드가 상한 안에서 내요.')}</li>
        <li>{t('[모의 봇 켜기]를 누르면 devlog 서버가 거래소 공개 시세로 바이낸스·업비트 모의 계좌를 돌려요. 거래소 키는 받지 않아요. 내 컴퓨터에서 봇을 직접 돌리면 그 계좌도 함께 보여요.')}</li>
        <li>{t('이 화면은 모의 매매만 보여 줘요. 실제 돈으로 따라 하는 건 각자의 판단과 책임이고, 투자 권유가 아니에요.')}</li>
      </ul>
    </details>
  )
}

function AccountCard({ account: a }: { account: BotAccount }) {
  const interval = accountInterval(a.account)
  return (
    <article className="tradebot-account">
      <header className="trades-fill-head">
        <strong>{exchangeName(a.exchange) || a.account}</strong>
        <span className="badge">{a.market === 'FUTURES' ? t('선물') : t('현물')}</span>
        {interval && <span className="badge" data-tip={t('봇이 이 간격의 봉이 닫힐 때마다 판단해요')}>{interval}</span>}
        <span className="muted small tradebot-account-name" title={a.account}>{a.account}</span>
      </header>
      <dl className="tradebot-cash">
        <div><dt>{t('현금')}</dt><dd>{money(a.cash, a.currency)}</dd></div>
        {a.unrealizedPnl !== null && (
          <div><dt>{t('평가 손익')}</dt><dd className={`pnl ${pnlClass(a.unrealizedPnl, a.currency)}`}>{money(a.unrealizedPnl, a.currency, true)}</dd></div>
        )}
      </dl>
      {a.positions.length === 0 ? <p className="muted small">{t('보유 포지션이 없어요.')}</p> : (
        <ul className="tradebot-positions">
          {a.positions.map((p) => (
            <li key={p.symbol + p.side}>
              <span><strong>{p.symbol}</strong>{p.side && <span className={p.side === 'LONG' ? ' trades-buy' : ' trades-sell'}> {p.side === 'LONG' ? t('롱') : t('숏')}</span>}</span>
              <span className="muted small">{qty(p.quantity)}{p.avgPrice !== null && ` @ ${money(p.avgPrice, a.currency)}`}</span>
              {p.unrealizedPnl !== null && <span className={`pnl ${pnlClass(p.unrealizedPnl, a.currency)}`}>{money(p.unrealizedPnl, a.currency, true)}</span>}
            </li>
          ))}
        </ul>
      )}
      {a.rules.length > 0 && (
        <details className="tradebot-rules" open>
          <summary data-tip={t('내 AI가 정해 둔 기준이에요. 바꾸려면 내 AI에게 말해 주세요')}>{t('적용 중인 규칙')}</summary>
          <dl>
            {a.rules.map((r) => <div key={r.label}><dt>{r.label}</dt><dd>{r.value}</dd></div>)}
          </dl>
        </details>
      )}
    </article>
  )
}

function FillRow({ fill: f }: { fill: BotEvent }) {
  return (
    <li>
      <div className="trades-fill-head">
        <span className={sideUp(f.side) ? 'trades-buy' : 'trades-sell'}><strong>{sideLabel(f.side)}</strong></span>
        <strong>{f.symbol}</strong>
        {f.quantity !== null && <span className="small">{qty(f.quantity)}</span>}
        {f.price !== null && <span className="small">@ {money(f.price, f.currency || 'USDT')}</span>}
        {f.realizedPnl !== null && <span className={`pnl small ${pnlClass(f.realizedPnl, f.currency)}`}>{money(f.realizedPnl, f.currency || 'USDT', true)}</span>}
        <span className="muted small trades-fill-delete">{eventTime(f.occurredAt)}</span>
      </div>
      {f.text && <p className="small tradebot-text"><span className="muted">{t('이유')}</span> {f.text}</p>}
    </li>
  )
}
