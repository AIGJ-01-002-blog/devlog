import { useEffect, useState, type CSSProperties } from 'react'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { t } from '../lib/i18n'
import { points } from '../lib/points'
import { Link } from '../lib/router'
import {
  applyItem, bannerProps, buyItem, frameProps, ITEM_KINDS, kindLabel, loadShop, removeDecoration, type ItemKind, type Shop, type ShopItem,
} from '../lib/shop'
import { Avatar } from '../components/Avatar'

/**
 * 포인트 상점 (#268). 상점 포인트로 블로그 꾸미기 아이템(테마 색·프로필 테두리·표지)을 사고, 산 것은 종류마다 하나씩 내 블로그에 적용한다.
 * 가입 첫 지급(투자 전용) 포인트로는 살 수 없다.
 */
export function ShopPage() {
  const { me } = useAuth()
  const [shop, setShop] = useState<Shop | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState<number | string | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => { document.title = t('포인트 상점 - devlog') }, [])
  useEffect(() => { loadShop().then(setShop).catch(() => setError(true)) }, [])

  const run = async (key: number | string, action: () => Promise<Shop>, done: string) => {
    setBusy(key)
    setMessage(null)
    try {
      setShop(await action())
      setMessage({ ok: true, text: done })
    } catch (e) {
      setMessage({ ok: false, text: e instanceof ApiError ? e.message : t('처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.') })
    } finally {
      setBusy(null)
    }
  }

  const buy = (it: ShopItem) => {
    if (!confirm(t('{0}을(를) {1}로 살까요? 산 뒤에는 되돌릴 수 없어요.', { 0: it.name, 1: points(it.price) }))) return
    void run(it.id, () => buyItem(it.id), t('{0}을(를) 샀어요. [적용]을 누르면 내 블로그에 보여요.', { 0: it.name }))
  }

  return (
    <main className="container dashboard-page shop-page">
      <h1 className="page-title">{t('포인트 상점')}</h1>
      <p className="muted small shop-notice">{t('출석·글·모의투자 수익으로 번 상점 포인트로 블로그를 꾸며요. 가입 때 받은 투자 전용 포인트로는 살 수 없어요.')}</p>
      {error && <p className="error" role="alert">{t('상점을 불러오지 못했어요.')}</p>}
      {!shop && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {shop && (
        <>
          <div className="kpi-grid dashboard-kpis" style={{ '--kpi-cols': 2 } as CSSProperties}>
            <div className="kpi" data-tip={t('상점에서 쓸 수 있는 포인트예요')}>
              <span className="kpi-label">{t('상점 포인트')}</span>
              <span className="kpi-value">{points(shop.shopBalance)}</span>
              <span className="kpi-foot"><Link to="/points" className="kpi-prev" data-tip={t('출석하고 포인트 얻는 법 보기')}>{t('포인트 더 모으기')}</Link></span>
            </div>
            <div className="kpi" data-tip={t('가입할 때 받은 모의투자 자금이에요. 모의투자에만 쓰고 상점에서는 못 써요')}>
              <span className="kpi-label">{t('투자 전용 포인트')}</span>
              <span className="kpi-value muted">{points(shop.investBalance)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('상점에서는 못 써요')}</span></span>
            </div>
          </div>
          {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
          {ITEM_KINDS.map((kind) => {
            const items = shop.items.filter((i) => i.kind === kind)
            if (items.length === 0) return null
            const applied = items.find((i) => i.applied)
            return (
              <section key={kind} aria-label={kindLabel(kind)}>
                <div className="shop-head">
                  <h2>{kindLabel(kind)}</h2>
                  {applied && (
                    <button type="button" className="btn btn-text btn-small" disabled={busy !== null}
                      data-tip={t('이 종류의 꾸미기를 빼고 기본 모습으로 되돌려요')}
                      onClick={() => void run(kind, () => removeDecoration(kind), t('{0}을(를) 기본으로 되돌렸어요.', { 0: kindLabel(kind) }))}>{t('기본으로')}</button>
                  )}
                </div>
                <div className="shop-grid">
                  {items.map((it) => (
                    <article key={it.id} className={`shop-card${it.applied ? ' applied' : ''}`}>
                      <Preview item={it} kind={kind} seed={me?.member?.handle ?? ''} name={me?.member?.nickname ?? ''} image={me?.member?.profileImageUrl ?? null} />
                      <div className="shop-body">
                        <h3>{it.name}</h3>
                        {it.description && <p className="muted small">{it.description}</p>}
                        <span className="shop-price">{points(it.price)}</span>
                        <div className="shop-actions">
                          {!it.owned ? (
                            <button type="button" className="btn btn-primary btn-small" disabled={busy !== null || shop.shopBalance < it.price}
                              data-tip={shop.shopBalance < it.price ? t('상점 포인트가 {0} 모자라요', { 0: points(it.price - shop.shopBalance) }) : t('상점 포인트로 사요')}
                              onClick={() => buy(it)}>{busy === it.id ? t('사는 중…') : t('사기')}</button>
                          ) : it.applied ? (
                            <button type="button" className="btn btn-outline btn-small" disabled aria-pressed="true" data-tip={t('지금 내 블로그에 보이는 꾸미기예요')}>{t('적용 중')}</button>
                          ) : (
                            <button type="button" className="btn btn-outline btn-small" disabled={busy !== null} data-tip={t('내 블로그에 적용해요. 같은 종류는 하나만 보여요')}
                              onClick={() => void run(it.id, () => applyItem(it.id), t('{0}을(를) 적용했어요.', { 0: it.name }))}>{busy === it.id ? t('적용하는 중…') : t('적용')}</button>
                          )}
                        </div>
                        {it.owned && !it.active && <span className="muted small">{t('상점에서 내려간 아이템이에요. 산 사람은 계속 써요')}</span>}
                      </div>
                    </article>
                  ))}
                </div>
              </section>
            )
          })}
          {me?.member && <Link to={`/@${me.member.handle}`} className="btn btn-outline" data-tip={t('적용한 꾸미기를 내 블로그에서 봐요')}>{t('내 블로그에서 보기')}</Link>}
        </>
      )}
    </main>
  )
}

/** 아이템 미리보기: 테마는 색과 버튼, 테두리는 내 프로필 사진, 표지는 그 표지 */
export function Preview({ item, kind, seed, name, image }: { item: Pick<ShopItem, 'value' | 'imageUrl' | 'name'>; kind: ItemKind; seed: string; name: string; image: string | null }) {
  const look = { itemId: 0, name: item.name, value: item.value, imageUrl: item.imageUrl }
  if (kind === 'THEME') {
    return (
      <div className="shop-preview" aria-hidden="true">
        <span className="shop-preview-theme blog-themed" style={{ '--blog-theme': item.value } as CSSProperties}>
          <span className="shop-swatch" style={{ '--swatch': item.value } as CSSProperties} />
          <span className="btn btn-primary btn-small">{t('새 글')}</span>
        </span>
      </div>
    )
  }
  if (kind === 'FRAME') {
    const f = frameProps(look)
    return (
      <div className="shop-preview" aria-hidden="true">
        <span className="deco-frame" data-frame={f['data-frame']} style={f.style}><Avatar src={image} name={name || '?'} seed={seed} size={56} /></span>
      </div>
    )
  }
  const b = bannerProps(look)
  return <div className={`shop-preview deco-banner${b.image ? ' has-image' : ''}`} data-banner={b['data-banner']} style={b.style} aria-hidden="true" />
}
