import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type FormEvent } from 'react'
import { ApiError } from '../lib/api'
import { num } from '../lib/dashboard'
import { t } from '../lib/i18n'
import {
  change, estimateBuy, estimateSell, loadPaper, newOrderId, placeOrder, pnlRate, price, qty, tone,
  type PaperSide, type PaperState,
} from '../lib/paper'
import { credits, points } from '../lib/points'
import { Link } from '../lib/router'

const REFRESH_MS = 10_000
const QUICK = [10, 25, 50, 100]

/**
 * 포인트 모의투자 (#267). 원화 시장 시세(코빗, v2.1.3부터)로 BTC·ETH·XRP·SOL·DOGE를 포인트로 사고판다(시장가·현물).
 * 투자 전용 포인트를 먼저 쓰고, 고르면 크레딧도 쓴다. 체결은 매매 일지에 모의투자로 남는다.
 * 상승·이익은 초록, 하락·손해는 빨강(바이낸스와 같은 색)이고 +·- 기호를 같이 붙인다.
 */
export function PaperPage() {
  const [state, setState] = useState<PaperState | null>(null)
  const [error, setError] = useState(false)
  const [symbol, setSymbol] = useState('BTC')
  const [side, setSide] = useState<PaperSide>('BUY')
  const [amount, setAmount] = useState('')
  const [quantity, setQuantity] = useState('')
  const [sellAll, setSellAll] = useState(false)
  const [useShop, setUseShop] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string; tradeId?: number | null } | null>(null)
  // 같은 주문을 두 번 보내도 한 번만 체결되게, 주문 내용이 바뀔 때만 새 번호를 쓴다
  const orderId = useRef(newOrderId())

  useEffect(() => { document.title = t('포인트 모의투자 - devlog') }, [])

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
  useEffect(() => { orderId.current = newOrderId() }, [symbol, side, amount, quantity, sellAll, useShop])

  const quote = state?.quotes.find((q) => q.symbol === symbol) ?? null
  const position = state?.positions.find((p) => p.symbol === symbol) ?? null
  const amountN = Number(amount.replace(/,/g, '')) || 0
  const quantityN = sellAll ? position?.quantity ?? 0 : Number(quantity) || 0
  const spendable = state ? state.invest + (useShop ? state.shop : 0) : 0
  const buyEst = state ? estimateBuy(amountN, quote?.price ?? null, state.feeRate) : null
  const sellEst = state ? estimateSell(quantityN, quote?.price ?? null, state.feeRate) : null
  const total = state ? state.invest + state.shop + state.holdings : 0
  const pnl = useMemo(() => state?.positions.reduce((s, p) => s + p.pnl, 0) ?? 0, [state])

  const blocked = (() => {
    if (!state || state.quotesError || !quote?.price) return t('시세를 받은 뒤에 주문할 수 있어요')
    if (side === 'BUY') {
      if (amountN < state.minOrder) return t('한 번에 {0} 이상 살 수 있어요', { 0: points(state.minOrder) })
      if (amountN > spendable) return useShop ? t('포인트가 모자라요') : t('투자 전용 포인트가 모자라요. 크레딧도 쓰려면 아래에서 켜 주세요')
      return null
    }
    if (!position) return t('갖고 있지 않은 코인이에요')
    if (quantityN <= 0) return t('팔 수량을 적어 주세요')
    if (quantityN > position.quantity) return t('갖고 있는 수량보다 많아요')
    return null
  })()

  const quick = (pct: number) => {
    if (!state) return
    if (side === 'BUY') setAmount(String(Math.floor((spendable * pct) / 100)))
    else if (position) {
      if (pct === 100) setSellAll(true)
      else { setSellAll(false); setQuantity(String(Number((position.quantity * pct / 100).toFixed(8)))) }
    }
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (blocked || !state || busy) return
    setBusy(true)
    setMessage(null)
    try {
      const r = await placeOrder(side === 'BUY'
        ? { clientOrderId: orderId.current, symbol, side, amount: amountN, useShop, reason }
        : { clientOrderId: orderId.current, symbol, side, quantity: sellAll ? undefined : quantityN, all: sellAll, reason })
      seq.current++
      setState(r.state)
      orderId.current = newOrderId()
      const name = quote?.name ?? symbol
      setMessage({
        ok: true,
        tradeId: r.tradeId,
        text: r.duplicate ? t('이미 체결된 주문이에요.')
          : r.side === 'BUY' ? t('{0} {1}개를 샀어요. 수수료 {2}', { 0: name, 1: qty(r.quantity ?? 0), 2: points(r.fee) })
          : r.reward > 0 ? t('{0} {1}개를 팔았어요. 손익 {2}, {3} 적립', { 0: name, 1: qty(r.quantity ?? 0), 2: points(r.pnl ?? 0, true), 3: credits(r.reward) })
          : t('{0} {1}개를 팔았어요. 손익 {2}', { 0: name, 1: qty(r.quantity ?? 0), 2: points(r.pnl ?? 0, true) }),
      })
      setAmount('')
      setQuantity('')
      setSellAll(false)
      setReason('')
    } catch (err) {
      setMessage({ ok: false, text: err instanceof ApiError ? err.message : t('주문하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="container dashboard-page paper-page">
      <div className="points-title">
        <h1 className="page-title">{t('포인트 모의투자')}</h1>
        <Link to="/points" className="btn btn-outline btn-small" data-tip={t('출석하고 포인트 잔액과 내역 보기')}>{t('내 포인트')}</Link>
      </div>
      <p className="muted small points-notice">{t('거래소 원화 시세로 비트코인·이더리움·리플·솔라나·도지코인을 포인트로 사고팔아요. 체결은 매매 일지에 모의투자로 남고, 수익은 모두 크레딧으로 쌓여요.')}</p>
      {error && <p className="error" role="alert">{t('모의투자를 불러오지 못했어요.')}</p>}
      {!state && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {state && (
        <>
          <div className="kpi-grid dashboard-kpis points-kpis" style={{ '--kpi-cols': 3 } as CSSProperties}>
            <div className="kpi" data-tip={t('가입할 때 받은 모의투자 자금과 판 돈이에요. 살 때 먼저 써요')}>
              <span className="kpi-label">{t('투자 전용 포인트')}</span>
              <span className="kpi-value">{points(state.invest)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('살 때 먼저 써요')}</span></span>
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

            <section className="paper-order" aria-label={t('주문')}>
              <div className="paper-sides" role="tablist" aria-label={t('사기·팔기')}>
                <button type="button" role="tab" aria-selected={side === 'BUY'} className={`paper-side buy${side === 'BUY' ? ' active' : ''}`}
                  onClick={() => setSide('BUY')} data-tip={t('포인트로 사요')}>{t('사기')}</button>
                <button type="button" role="tab" aria-selected={side === 'SELL'} className={`paper-side sell${side === 'SELL' ? ' active' : ''}`}
                  onClick={() => setSide('SELL')} data-tip={t('갖고 있는 코인을 팔아요')}>{t('팔기')}</button>
              </div>
              <form onSubmit={submit} className="paper-form">
                <div className="paper-row">
                  <span className="muted small">{quote?.name ?? symbol}</span>
                  <strong>{price(quote?.price ?? null)}</strong>
                </div>
                {side === 'BUY' ? (
                  <label className="field">
                    <span>{t('살 금액 (수수료 포함)')}</span>
                    <input inputMode="numeric" value={amount ? num(Number(amount)) : ''} placeholder={t('{0} 이상', { 0: points(state.minOrder) })}
                      onChange={(e) => setAmount(e.target.value.replace(/[^0-9]/g, ''))} aria-describedby="paper-est" />
                  </label>
                ) : (
                  <label className="field">
                    <span>{t('팔 수량')}</span>
                    <input inputMode="decimal" value={sellAll ? qty(position?.quantity ?? 0) : quantity} disabled={sellAll}
                      placeholder={position ? t('최대 {0}', { 0: qty(position.quantity) }) : t('갖고 있지 않아요')}
                      onChange={(e) => setQuantity(e.target.value.replace(/[^0-9.]/g, ''))} aria-describedby="paper-est" />
                  </label>
                )}
                <div className="paper-quick">
                  {QUICK.map((p) => (
                    <button key={p} type="button" className="btn btn-outline btn-small" onClick={() => quick(p)}
                      disabled={side === 'SELL' && !position}
                      data-tip={side === 'BUY' ? t('쓸 수 있는 포인트의 {0}%', { 0: p }) : t('갖고 있는 수량의 {0}%', { 0: p })}>
                      {p === 100 ? t('최대') : `${p}%`}
                    </button>
                  ))}
                </div>
                {side === 'BUY' && (
                  <label className="check" data-tip={t('투자 전용 포인트가 모자라면 크레딧으로 채워요. 판 돈은 낸 지갑으로 돌아가요')}>
                    <input type="checkbox" checked={useShop} onChange={(e) => setUseShop(e.target.checked)} />
                    <span>{t('크레딧도 쓰기 (지금 {0})', { 0: credits(state.shop) })}</span>
                  </label>
                )}
                <label className="field">
                  <span>{t('이유 (선택, 매매 일지에 남아요)')}</span>
                  <input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} placeholder={t('예: 지지선에서 반등')} />
                </label>
                <p id="paper-est" className="muted small paper-est">
                  {side === 'BUY'
                    ? buyEst ? t('예상 {0}개 · 수수료 {1}', { 0: qty(buyEst.quantity), 1: points(buyEst.fee) }) : t('수수료 {0}%', { 0: num(state.feeRate * 100) })
                    : sellEst ? t('예상 받는 포인트 {0} · 수수료 {1}', { 0: points(sellEst.proceeds), 1: points(sellEst.fee) }) : t('수수료 {0}%', { 0: num(state.feeRate * 100) })}
                </p>
                <button type="submit" className={`btn paper-submit ${side === 'BUY' ? 'buy' : 'sell'}`} disabled={busy || blocked !== null}
                  data-tip={blocked ?? (side === 'BUY' ? t('지금 시세로 사요') : t('지금 시세로 팔아요'))}>
                  {busy ? t('주문하는 중…') : side === 'BUY' ? t('{0} 사기', { 0: symbol }) : t('{0} 팔기', { 0: symbol })}
                </button>
                {blocked && !busy && <p className="muted small">{blocked}</p>}
              </form>
              {message && (
                <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>
                  {message.text}
                  {message.ok && message.tradeId && <> <Link to="/trades" data-tip={t('이 체결을 매매 일지에서 봐요')}>{t('매매 일지')}</Link></>}
                </p>
              )}
            </section>
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
                          <button type="button" className="btn btn-text btn-small" onClick={() => { setSymbol(p.symbol); setSide('SELL') }}
                            data-tip={t('{0} 팔기로 가기', { 0: p.name })}>{p.symbol}</button>
                        </th>
                        <td className="num">{qty(p.quantity)}</td>
                        <td className="num hide-sm">{price(p.avgPrice)}</td>
                        <td className="num hide-sm" title={t('투자 전용 {0} · 상점 {1}', { 0: points(p.costInvest), 1: points(p.costShop) })}>{points(p.cost)}</td>
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
