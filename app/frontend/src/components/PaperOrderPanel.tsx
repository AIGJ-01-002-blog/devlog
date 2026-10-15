import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../lib/api'
import { num } from '../lib/dashboard'
import { t } from '../lib/i18n'
import {
  cancelLimit, floorQty, limitBuyCost, maxBuyQty, newOrderId, placeLimit, placeOrder, price, qty, QTY_SCALE, stepPrice, tone, toTick,
  type PaperState,
} from '../lib/paper'
import { credits, points } from '../lib/points'
import { Link } from '../lib/router'

export type OrderTab = 'BUY' | 'SELL' | 'HISTORY'
type Kind = 'LIMIT' | 'MARKET'

const QUICK = [10, 25, 50, 100]

/** 입력칸 숫자: 쉼표를 빼고 읽는다. 비었거나 이상하면 0 */
const read = (s: string) => Number(s.replace(/,/g, '')) || 0
/** 소수 입력을 그대로 두되 숫자·점만 받는다 */
const decimalInput = (s: string) => s.replace(/[^0-9.]/g, '').replace(/(\..*)\./g, '$1')
/** 가격 칸에 보일 값: 정수 부분에만 쉼표를 넣고 소수는 적은 그대로 둔다 */
const withCommas = (s: string) => {
  const [i, d] = s.split('.')
  const head = i ? Number(i).toLocaleString('en-US') : ''
  return d === undefined ? head : `${head || '0'}.${d}`
}

function shortTime(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 업비트식 주문창 (#332). 매수·매도·거래내역 탭, 지정가·시장가, 가격 −/＋(호가 단위), 수량·총액, 10·25·50·100%, 직접 입력, 초기화.
 * 지정가 매수는 지정가 × 수량 + 수수료만큼 크레딧을 묶고, 매도는 수량을 묶는다. 시세가 지정가에 닿으면 서버가 체결한다.
 * 거래내역 탭에서 미체결 주문을 취소하면 묶인 크레딧·수량이 풀린다.
 */
export function PaperOrderPanel({ state, symbol, tab, onTab, onState, onFilled }: {
  state: PaperState
  symbol: string
  tab: OrderTab
  onTab: (tab: OrderTab) => void
  onState: (s: PaperState) => void
  onFilled: () => void
}) {
  const [kind, setKind] = useState<Kind>('LIMIT')
  const [priceText, setPriceText] = useState('')
  const [qtyText, setQtyText] = useState('')
  const [totalText, setTotalText] = useState('')
  const [sellAll, setSellAll] = useState(false)
  const [useShop, setUseShop] = useState(false)
  const [reason, setReason] = useState('')
  const [history, setHistory] = useState<'OPEN' | 'DONE'>('OPEN')
  const [busy, setBusy] = useState(false)
  const [canceling, setCanceling] = useState<number | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string; tradeId?: number | null } | null>(null)
  const orderId = useRef(newOrderId())
  const qtyRef = useRef<HTMLInputElement>(null)
  const totalRef = useRef<HTMLInputElement>(null)

  const quote = state.quotes.find((q) => q.symbol === symbol) ?? null
  const position = state.positions.find((p) => p.symbol === symbol) ?? null
  const scale = QTY_SCALE[symbol] ?? 8
  const side = tab === 'SELL' ? 'SELL' : 'BUY'
  const market = quote?.price ?? null
  const priceN = read(priceText)
  const qtyN = sellAll ? position?.available ?? 0 : read(qtyText)
  const totalN = read(totalText)
  const spendable = state.invest + (useShop ? state.shop : 0)
  const available = position?.available ?? 0

  // 코인이나 탭을 바꾸면 지금 시세를 지정가 자리에 두고 수량을 비운다
  const reset = () => {
    setPriceText(market ? String(toTick(market)) : '')
    setQtyText('')
    setTotalText('')
    setSellAll(false)
    setReason('')
  }
  useEffect(reset, [symbol, tab, kind])
  // 시세가 늦게 오면 그때 채운다
  useEffect(() => { if (!priceText && market) setPriceText(String(toTick(market))) }, [market])
  useEffect(() => { orderId.current = newOrderId() }, [symbol, tab, kind, priceText, qtyText, totalText, sellAll, useShop])

  // 지정가: 총액은 가격 × 수량. 시장가 매수: 총액만, 시장가 매도: 수량만
  const limitCost = limitBuyCost(priceN, qtyN, state.feeRate)
  const sellGross = Math.floor((kind === 'LIMIT' ? priceN : market ?? 0) * qtyN)
  const fee = side === 'BUY'
    ? kind === 'LIMIT' ? limitCost.fee : Math.ceil(totalN * state.feeRate)
    : Math.ceil(sellGross * state.feeRate)

  const setQty = (q: number) => {
    setSellAll(false)
    setQtyText(q > 0 ? String(q) : '')
    if (kind === 'LIMIT') setTotalText(q > 0 && priceN > 0 ? String(Math.round(priceN * q)) : '')
  }
  const onQty = (s: string) => {
    const v = decimalInput(s)
    setSellAll(false)
    setQtyText(v)
    if (kind === 'LIMIT') setTotalText(read(v) > 0 && priceN > 0 ? String(Math.round(priceN * read(v))) : '')
  }
  const onTotal = (s: string) => {
    const v = s.replace(/[^0-9]/g, '')
    setTotalText(v)
    if (kind === 'LIMIT') setQtyText(read(v) > 0 && priceN > 0 ? String(floorQty(read(v) / priceN, scale)) : '')
  }
  const onPrice = (p: number | string) => {
    const v = typeof p === 'number' ? String(p) : decimalInput(p)
    setPriceText(v)
    if (qtyN > 0 && read(v) > 0) setTotalText(String(Math.round(read(v) * qtyN)))
  }

  const quick = (pct: number) => {
    if (side === 'BUY') {
      const budget = Math.floor((spendable * pct) / 100)
      if (kind === 'MARKET') setTotalText(budget > 0 ? String(budget) : '')
      else setQty(maxBuyQty(budget, priceN, state.feeRate, scale))
    } else if (position) {
      if (pct === 100) { setQty(available); setSellAll(kind === 'MARKET') }
      else setQty(floorQty((available * pct) / 100, scale))
    }
  }
  const direct = () => (side === 'BUY' && kind === 'MARKET' ? totalRef : qtyRef).current?.focus()

  const blocked = (() => {
    if (state.quotesError || !market) return t('시세를 받은 뒤에 주문할 수 있어요')
    if (kind === 'LIMIT' && priceN <= 0) return t('주문 가격을 적어 주세요')
    if (side === 'BUY') {
      if (kind === 'MARKET') {
        if (totalN < state.minOrder) return t('한 번에 {0} 이상 살 수 있어요', { 0: points(state.minOrder) })
        if (totalN > spendable) return useShop ? t('크레딧이 모자라요') : t('기본 크레딧이 모자라요. 번 크레딧도 쓰려면 아래에서 켜 주세요')
        return null
      }
      if (qtyN <= 0) return t('주문 수량을 적어 주세요')
      if (limitCost.gross < state.minOrder) return t('한 번에 {0} 이상 살 수 있어요', { 0: points(state.minOrder) })
      if (limitCost.total > spendable) return useShop ? t('크레딧이 모자라요') : t('기본 크레딧이 모자라요. 번 크레딧도 쓰려면 아래에서 켜 주세요')
      return null
    }
    if (!position) return t('갖고 있지 않은 코인이에요')
    if (qtyN <= 0) return t('주문 수량을 적어 주세요')
    if (qtyN > available) return t('팔 수 있는 수량보다 많아요')
    if (sellGross < state.minOrder && qtyN < available) return t('한 번에 {0} 이상 팔 수 있어요. 남은 것을 모두 팔 때는 제한이 없어요', { 0: points(state.minOrder) })
    return null
  })()

  const done = (text: string, tradeId?: number | null) => {
    setMessage({ ok: true, text, tradeId })
    orderId.current = newOrderId()
    setQtyText('')
    setTotalText('')
    setSellAll(false)
    setReason('')
    onFilled()
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (blocked || busy) return
    setBusy(true)
    setMessage(null)
    const name = quote?.name ?? symbol
    try {
      if (kind === 'LIMIT') {
        const r = await placeLimit({ clientOrderId: orderId.current, symbol, side, price: priceN, quantity: qtyN, useShop: side === 'BUY' && useShop, reason })
        onState(r.state)
        done(r.duplicate ? t('이미 넣은 주문이에요.')
          : r.order?.status === 'FILLED'
            ? t('시세가 지정가에 닿아 바로 체결됐어요. {0} {1}개, {2}', { 0: name, 1: qty(r.order.quantity), 2: price(r.order.filledPrice) })
            : t('지정가 주문을 넣었어요. 시세가 {0}에 닿으면 체결돼요. 거래내역에서 취소할 수 있어요.', { 0: price(priceN) }))
      } else {
        const all = side === 'SELL' && (sellAll || qtyN === available)
        const r = await placeOrder(side === 'BUY'
          ? { clientOrderId: orderId.current, symbol, side, amount: totalN, useShop, reason }
          : { clientOrderId: orderId.current, symbol, side, quantity: all ? undefined : qtyN, all, reason })
        onState(r.state)
        done(r.duplicate ? t('이미 체결된 주문이에요.')
          : r.side === 'BUY' ? t('{0} {1}개를 샀어요. 수수료 {2}', { 0: name, 1: qty(r.quantity ?? 0), 2: points(r.fee) })
          : r.reward > 0 ? t('{0} {1}개를 팔았어요. 손익 {2}, {3} 적립', { 0: name, 1: qty(r.quantity ?? 0), 2: points(r.pnl ?? 0, true), 3: credits(r.reward) })
          : t('{0} {1}개를 팔았어요. 손익 {2}', { 0: name, 1: qty(r.quantity ?? 0), 2: points(r.pnl ?? 0, true) }), r.tradeId)
      }
    } catch (err) {
      setMessage({ ok: false, text: err instanceof ApiError ? err.message : t('주문하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    } finally {
      setBusy(false)
    }
  }

  const cancel = async (id: number) => {
    setCanceling(id)
    setMessage(null)
    try {
      onState(await cancelLimit(id))
      setMessage({ ok: true, text: t('주문을 취소했어요. 묶여 있던 크레딧과 수량이 풀렸어요.') })
    } catch (err) {
      setMessage({ ok: false, text: err instanceof ApiError ? err.message : t('취소하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    } finally {
      setCanceling(null)
    }
  }

  const open = state.orders.filter((o) => o.status === 'OPEN')

  return (
    <section className="paper-order" aria-label={t('주문')}>
      <div className="paper-sides paper-tabs" role="tablist" aria-label={t('매수·매도·거래내역')}>
        {(['BUY', 'SELL', 'HISTORY'] as OrderTab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k}
            className={`paper-side ${k === 'BUY' ? 'buy' : k === 'SELL' ? 'sell' : 'history'}${tab === k ? ' active' : ''}`}
            onClick={() => onTab(k)}
            data-tip={k === 'BUY' ? t('크레딧으로 사요') : k === 'SELL' ? t('갖고 있는 코인을 팔아요') : t('미체결 주문과 최근 체결을 봐요')}>
            {k === 'BUY' ? t('매수') : k === 'SELL' ? t('매도') : open.length > 0 ? t('거래내역 {0}', { 0: open.length }) : t('거래내역')}
          </button>
        ))}
      </div>

      {tab === 'HISTORY' ? (
        <div className="paper-history">
          <div className="paper-kinds" role="radiogroup" aria-label={t('거래내역 보기')}>
            <label className="check"><input type="radio" name="paper-history" checked={history === 'OPEN'} onChange={() => setHistory('OPEN')} /><span>{t('미체결')}</span></label>
            <label className="check"><input type="radio" name="paper-history" checked={history === 'DONE'} onChange={() => setHistory('DONE')} /><span>{t('체결')}</span></label>
          </div>
          {history === 'OPEN' ? (
            open.length === 0 ? <p className="muted small">{t('미체결 주문이 없어요.')}</p> : (
              <ul className="paper-history-list">
                {open.map((o) => (
                  <li key={o.id}>
                    <div className="paper-history-head">
                      <strong>{o.symbol}</strong>
                      <span className={`pnl ${o.side === 'BUY' ? 'good' : 'bad'}`}>{o.side === 'BUY' ? t('매수') : t('매도')}</span>
                      <span className="muted small">{shortTime(o.createdAt)}</span>
                    </div>
                    <div className="paper-history-body small">
                      <span>{t('지정가 {0}', { 0: price(o.price) })}</span>
                      <span>{t('수량 {0}', { 0: qty(o.quantity) })}</span>
                      {o.side === 'BUY' && <span className="muted">{t('묶인 크레딧 {0}', { 0: points(o.locked) })}</span>}
                    </div>
                    <button type="button" className="btn btn-outline btn-small" disabled={canceling === o.id} onClick={() => void cancel(o.id)}
                      data-tip={t('주문을 취소하고 묶인 크레딧·수량을 풀어요')}>
                      {canceling === o.id ? t('취소하는 중…') : t('취소')}
                    </button>
                  </li>
                ))}
              </ul>
            )
          ) : (
            state.fills.length === 0 ? <p className="muted small">{t('아직 체결이 없어요.')}</p> : (
              <ul className="paper-history-list">
                {state.fills.map((f) => (
                  <li key={f.tradeId}>
                    <div className="paper-history-head">
                      <strong>{f.symbol}</strong>
                      <span className={`pnl ${f.side === 'BUY' ? 'good' : 'bad'}`}>{f.side === 'BUY' ? t('매수') : t('매도')}</span>
                      {f.bot && <span className="badge" data-tip={t('서버 모의 봇이 기본 크레딧으로 낸 체결이에요')}>{t('봇')}</span>}
                      <span className="muted small">{shortTime(f.at)}</span>
                    </div>
                    <div className="paper-history-body small">
                      <span>{t('체결가 {0}', { 0: price(f.price) })}</span>
                      <span>{t('수량 {0}', { 0: qty(f.quantity) })}</span>
                      {f.pnl !== null && <span className={`pnl ${tone(f.pnl)}`}>{t('손익 {0}', { 0: points(Math.round(f.pnl), true) })}</span>}
                    </div>
                  </li>
                ))}
              </ul>
            )
          )}
          <Link to="/trades" className="small" data-tip={t('모든 체결을 매매 일지에서 봐요')}>{t('매매 일지에서 모두 보기')}</Link>
        </div>
      ) : (
        <form onSubmit={submit} className="paper-form">
          <div className="paper-kinds" role="radiogroup" aria-label={t('주문 구분')}>
            <span className="muted small">{t('주문구분')}</span>
            <label className="check" data-tip={t('가격을 정해 두면 시세가 그 가격에 닿을 때 체결돼요')}>
              <input type="radio" name="paper-kind" checked={kind === 'LIMIT'} onChange={() => setKind('LIMIT')} /><span>{t('지정가')}</span>
            </label>
            <label className="check" data-tip={t('지금 시세로 바로 체결돼요')}>
              <input type="radio" name="paper-kind" checked={kind === 'MARKET'} onChange={() => setKind('MARKET')} /><span>{t('시장가')}</span>
            </label>
          </div>

          <div className="paper-row">
            <span className="muted small">{t('주문가능')}</span>
            <strong>{side === 'BUY' ? points(spendable) : t('{0} {1}', { 0: qty(available), 1: symbol })}</strong>
          </div>

          {kind === 'LIMIT' && (
            <div className="field">
              <span id="paper-price-label">{side === 'BUY' ? t('매수가격 (KRW)') : t('매도가격 (KRW)')}</span>
              <div className="paper-stepper">
                <input inputMode="decimal" aria-labelledby="paper-price-label" value={withCommas(priceText)} onChange={(e) => onPrice(e.target.value)} />
                <button type="button" className="btn btn-outline btn-small" aria-label={t('한 호가 내리기')} data-tip={t('한 호가 내려요')}
                  onClick={() => onPrice(stepPrice(priceN || market || 0, -1))}>−</button>
                <button type="button" className="btn btn-outline btn-small" aria-label={t('한 호가 올리기')} data-tip={t('한 호가 올려요')}
                  onClick={() => onPrice(stepPrice(priceN || market || 0, 1))}>＋</button>
              </div>
              <span className="muted small">{t('지금 시세 {0}', { 0: price(market) })}</span>
            </div>
          )}

          {!(side === 'BUY' && kind === 'MARKET') && (
            <label className="field">
              <span>{side === 'BUY' ? t('매수수량 ({0})', { 0: symbol }) : t('매도수량 ({0})', { 0: symbol })}</span>
              <input ref={qtyRef} inputMode="decimal" value={sellAll ? qty(available) : qtyText} placeholder="0"
                onChange={(e) => onQty(e.target.value)} aria-describedby="paper-est" />
            </label>
          )}

          <div className="paper-quick paper-quick-5">
            {QUICK.map((p) => (
              <button key={p} type="button" className="btn btn-outline btn-small" onClick={() => quick(p)}
                disabled={(side === 'SELL' && !position) || (side === 'BUY' && kind === 'LIMIT' && priceN <= 0)}
                data-tip={side === 'BUY' ? t('쓸 수 있는 크레딧의 {0}%', { 0: p }) : t('팔 수 있는 수량의 {0}%', { 0: p })}>
                {p}%
              </button>
            ))}
            <button type="button" className="btn btn-outline btn-small" onClick={direct} data-tip={t('수량이나 총액을 직접 적어요')}>{t('직접입력')}</button>
          </div>

          {side === 'BUY' || kind === 'LIMIT' ? (
            <label className="field">
              <span>{t('주문총액 (KRW)')}</span>
              <input ref={totalRef} inputMode="numeric" value={side === 'SELL' && kind === 'LIMIT' && sellAll ? num(sellGross) : totalText ? num(read(totalText)) : ''}
                placeholder={kind === 'MARKET' ? t('{0} 이상', { 0: points(state.minOrder) }) : '0'} onChange={(e) => onTotal(e.target.value)}
                aria-describedby="paper-est" />
            </label>
          ) : (
            <div className="paper-row">
              <span className="muted small">{t('예상 받는 크레딧')}</span>
              <strong>{market && qtyN > 0 ? points(Math.max(0, sellGross - fee)) : '-'}</strong>
            </div>
          )}

          {side === 'BUY' && (
            <label className="check" data-tip={t('기본 크레딧이 모자라면 번 크레딧으로 채워요. 판 돈은 낸 쪽으로 돌아가요')}>
              <input type="checkbox" checked={useShop} onChange={(e) => setUseShop(e.target.checked)} />
              <span>{t('크레딧도 쓰기 (지금 {0})', { 0: credits(state.shop) })}</span>
            </label>
          )}
          <label className="field">
            <span>{t('이유 (선택, 매매 일지에 남아요)')}</span>
            <input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} placeholder={t('예: 지지선에서 반등')} />
          </label>

          <p id="paper-est" className="muted small paper-est">
            {t('최소주문금액: {0} · 수수료(부가세 포함): {1}%', { 0: points(state.minOrder), 1: num(state.feeRate * 100) })}
            {side === 'BUY' && kind === 'LIMIT' && limitCost.total > 0 && <><br />{t('묶일 크레딧 {0} (수수료 {1} 포함)', { 0: points(limitCost.total), 1: points(limitCost.fee) })}</>}
            {fee > 0 && !(side === 'BUY' && kind === 'LIMIT') && <><br />{t('예상 수수료 {0}', { 0: points(fee) })}</>}
          </p>

          <div className="paper-actions">
            <button type="button" className="btn btn-outline" onClick={() => { reset(); setMessage(null) }} data-tip={t('적은 값을 지우고 가격을 지금 시세로 되돌려요')}>{t('초기화')}</button>
            <button type="submit" className={`btn paper-submit ${side === 'BUY' ? 'buy' : 'sell'}`} disabled={busy || blocked !== null}
              data-tip={blocked ?? (kind === 'LIMIT' ? t('지정가로 주문을 넣어요') : side === 'BUY' ? t('지금 시세로 사요') : t('지금 시세로 팔아요'))}>
              {busy ? t('주문하는 중…') : side === 'BUY' ? t('매수') : t('매도')}
            </button>
          </div>
          {blocked && !busy && <p className="muted small">{blocked}</p>}
        </form>
      )}
      {message && (
        <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>
          {message.text}
          {message.ok && message.tradeId && <> <Link to="/trades" data-tip={t('이 체결을 매매 일지에서 봐요')}>{t('매매 일지')}</Link></>}
        </p>
      )}
    </section>
  )
}
