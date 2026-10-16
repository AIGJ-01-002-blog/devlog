import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { num } from '../lib/dashboard'
import { t } from '../lib/i18n'
import { change, loadPaper, pnlRate, price, qty, tone, type PaperState } from '../lib/paper'
import { points } from '../lib/points'
import { Link } from '../lib/router'
import { PaperChart } from '../components/PaperChart'
import { PaperOrderPanel, type OrderTab } from '../components/PaperOrderPanel'

const REFRESH_MS = 10_000

/**
 * 크레딧 모의투자 (#267, #331, #332). 원화 시장 시세(코빗, v2.1.3부터)로 BTC·ETH·XRP·SOL·DOGE를 크레딧으로 사고판다(지정가·시장가, 현물).
 * 기본 크레딧(가입 첫 지급, 상점에서는 못 씀)을 먼저 쓰고, 고르면 번 크레딧도 쓴다. 체결은 매매 일지에 모의투자로 남는다.
 * 상승·이익은 초록, 하락·손해는 빨강(바이낸스와 같은 색)이고 +·- 기호를 같이 붙인다.
 */
export function PaperPage() {
  const [state, setState] = useState<PaperState | null>(null)
  const [error, setError] = useState(false)
  const [symbol, setSymbol] = useState('BTC')
  const [tab, setTab] = useState<OrderTab>('BUY')
  // 체결될 때마다 늘려 차트가 방금 체결을 바로 찍게 한다 (#324)
  const [filled, setFilled] = useState(0)

  useEffect(() => { document.title = t('크레딧 모의투자 - devlog') }, [])

  // 늦게 온 새로고침 응답이 방금 받은 주문 결과를 덮지 않게, 가장 최근 요청의 응답만 쓴다
  const seq = useRef(0)
  const refresh = useCallback(() => {
    const id = ++seq.current
    return loadPaper()
      .then((s) => { if (id === seq.current) { setState(s); setError(false) } })
      .catch(() => { if (id === seq.current) setError(true) })
  }, [])
  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void refresh() }, REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [refresh])

  const total = state ? state.invest + state.shop + state.locked + state.holdings : 0
  const pnl = useMemo(() => state?.positions.reduce((s, p) => s + p.pnl, 0) ?? 0, [state])
  // 고른 코인을 갖고 있으면 차트에 내 평단선을 그린다. 10초마다 새로 받아도 값이 같으면 같은 것을 넘긴다
  const held = state?.positions.find((p) => p.symbol === symbol)
  const position = useMemo(() => (held ? { avgPrice: held.avgPrice, quantity: held.quantity, side: '', label: t('내 보유') } : null),
    [symbol, held?.avgPrice, held?.quantity])

  return (
    <main className="container dashboard-page paper-page">
      <div className="points-title">
        <h1 className="page-title">{t('크레딧 모의투자')}</h1>
        <Link to="/points" className="btn btn-outline btn-small" data-tip={t('출석하고 크레딧 잔액과 내역 보기')}>{t('내 크레딧')}</Link>
      </div>
      <p className="muted small points-notice">{t('코빗 원화 시세로 비트코인·이더리움·리플·솔라나·도지코인을 크레딧으로 사고팔아요. 처음 받은 기본 크레딧을 먼저 쓰고, 체결은 매매 일지에 남아요. 수익은 모두 크레딧으로 쌓여요.')}</p>
      {error && <p className="error" role="alert">{t('모의투자를 불러오지 못했어요.')}</p>}
      {!state && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {state && (
        <>
          <div className="kpi-grid dashboard-kpis points-kpis" style={{ '--kpi-cols': 3 } as CSSProperties}>
            <div className="kpi" data-tip={t('가입할 때 받은 모의투자 자금과 판 돈이에요. 살 때 먼저 써요')}>
              <span className="kpi-label">{t('기본 크레딧')}</span>
              <span className="kpi-value">{points(state.invest)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{state.locked > 0 ? t('지정가 주문에 {0} 묶임', { 0: points(state.locked) }) : t('살 때 먼저 써요')}</span></span>
            </div>
            <div className="kpi" data-tip={t('출석·글·수익 적립으로 번 크레딧이에요. 켜면 모의투자에도 써요')}>
              <span className="kpi-label">{t('크레딧')}</span>
              <span className="kpi-value">{num(state.shop)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('켜면 모의투자에도 써요')}</span></span>
            </div>
            <div className="kpi" data-tip={t('갖고 있는 코인을 지금 시세로 친 값과 평가 손익이에요')}>
              <span className="kpi-label">{t('보유 코인 평가')}</span>
              <span className="kpi-value">{points(state.holdings)}</span>
              <span className="kpi-foot"><span className={`pnl ${tone(pnl)}`}>{points(pnl, true)}</span><span className="kpi-prev"> · {t('총 {0}', { 0: points(total) })}</span></span>
            </div>
          </div>
          {state.quotesError && <p className="error small" role="alert">{state.quotesError}</p>}

          <PaperChart symbol={symbol} refresh={filled} position={position} />

          <div className="paper-layout">
            <section className="paper-market" aria-label={t('시세')}>
              <div className="shop-head">
                <h2>{t('시세')}</h2>
                <button type="button" className="btn btn-text btn-small" onClick={() => void refresh()} data-tip={t('시세를 다시 받아요. 10초마다 저절로 바뀌어요')}>{t('새로 고침')}</button>
              </div>
              <ul className="paper-coins" role="listbox" aria-label={t('코인 고르기')}>
                {state.quotes.map((q) => (
                  <li key={q.symbol} role="option" aria-selected={q.symbol === symbol}>
                    <button type="button" className={`paper-coin${q.symbol === symbol ? ' active' : ''}`} onClick={() => setSymbol(q.symbol)}
                      data-tip={t('{0} 주문하기', { 0: q.name })}>
                      <span className="paper-coin-name"><strong>{q.symbol}</strong><span className="muted small">{q.name}</span></span>
                      <span className="paper-coin-price">{price(q.price)}</span>
                      <span className={`paper-change pnl ${tone(q.changeRate)}`}>{change(q.changeRate)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>

            <PaperOrderPanel state={state} symbol={symbol} tab={tab} onTab={setTab}
              onState={(s) => { seq.current++; setState(s) }} onFilled={() => setFilled((n) => n + 1)} />
          </div>

          <section aria-label={t('보유 코인')}>
            <h2>{t('보유 코인')}</h2>
            {state.positions.length === 0 ? <p className="muted">{t('아직 산 코인이 없어요. 위에서 코인을 골라 사 보세요.')}</p> : (
              <div className="paper-table-wrap">
                <table className="paper-table">
                  <thead>
                    <tr>
                      <th scope="col">{t('코인')}</th>
                      <th scope="col" className="num">{t('수량')}</th>
                      <th scope="col" className="num hide-sm">{t('평균 단가')}</th>
                      <th scope="col" className="num hide-sm">{t('원금')}</th>
                      <th scope="col" className="num">{t('평가')}</th>
                      <th scope="col" className="num">{t('손익')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.positions.map((p) => (
                      <tr key={p.symbol}>
                        <th scope="row">
                          <button type="button" className="btn btn-text btn-small" onClick={() => { setSymbol(p.symbol); setTab('SELL') }}
                            data-tip={t('{0} 팔기로 가기', { 0: p.name })}>{p.symbol}</button>
                        </th>
                        <td className="num">{qty(p.quantity)}</td>
                        <td className="num hide-sm">{price(p.avgPrice)}</td>
                        <td className="num hide-sm" title={t('기본 {0} · 상점 {1}', { 0: points(p.costInvest), 1: points(p.costShop) })}>{points(p.cost)}</td>
                        <td className="num">{points(p.value)}</td>
                        <td className={`num pnl ${tone(p.pnl)}`}>{points(p.pnl, true)} <span className="small paper-rate">({pnlRate(p)})</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </main>
  )
}
