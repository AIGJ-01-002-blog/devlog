import { useEffect, useState, type CSSProperties } from 'react'
import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { num } from '../lib/dashboard'
import { t } from '../lib/i18n'
import { credits, points } from '../lib/points'
import { Link } from '../lib/router'
import {
  applyItem, bannerProps, buyItem, frameProps, ITEM_KINDS, kindLabel, kindShort, loadShop, needText, removeDecoration, shortCredits, tierName, tierPrice,
  TIERS, type ItemKind, type Shop, type ShopItem,
} from '../lib/shop'
import { Avatar } from '../components/Avatar'
import { TierEmblem } from '../components/TierEmblem'
import { NavIcon, type IconName } from '../components/NavIcons'

const EFFECT_TIER: Record<string, string> = { glow: 'diamond', stardust: 'grandmaster', radiance: 'challenger' }
const KIND_ICONS: Record<ItemKind, IconName> = { THEME: 'sun', FRAME: 'user', BANNER: 'blog', EFFECT: 'eye' }

/** 주소 뒤 #frame처럼 종류 탭을 바로 연다 */
export function kindFromHash(hash: string): ItemKind {
  const key = hash.replace(/^#/, '').toUpperCase()
  return (ITEM_KINDS as string[]).includes(key) ? (key as ItemKind) : 'THEME'
}

/**
 * 크레딧 상점 (#268). 활동으로 번 크레딧으로 블로그 꾸미기 아이템(테마 색·프로필 테두리·표지·빛 효과)을 사고, 산 것은 종류마다 하나씩 내 블로그에 적용한다.
 * 아이템은 아이언~챌린저 티어가 있고 아래 티어를 가져야 윗 티어가 열린다(블로그 주인 결정). 가입 첫 지급(투자 전용) 포인트로는 살 수 없다.
 */
export function ShopPage() {
  const { me } = useAuth()
  const [shop, setShop] = useState<Shop | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState<number | string | null>(null)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [kind, setKind] = useState<ItemKind>(() => kindFromHash(location.hash))
  useEffect(() => {
    const sync = () => setKind(kindFromHash(location.hash))
    window.addEventListener('hashchange', sync)
    window.addEventListener('popstate', sync)
    return () => { window.removeEventListener('hashchange', sync); window.removeEventListener('popstate', sync) }
  }, [])
  const open = (k: ItemKind) => {
    if (k === kind) return
    history.replaceState(null, '', `/shop#${k.toLowerCase()}`)
    setKind(k)
    setMessage(null)
  }

  useEffect(() => { document.title = t('크레딧 상점 - devlog') }, [])
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
    if (!confirm(t('{0}을(를) {1}로 살까요? 산 뒤에는 되돌릴 수 없어요.', { 0: it.name, 1: credits(it.price) }))) return
    void run(it.id, () => buyItem(it.id), t('{0}을(를) 샀어요. [적용]을 누르면 내 블로그에 보여요.', { 0: it.name }))
  }

  const items = shop?.items.filter((i) => i.kind === kind) ?? []
  const applied = items.find((i) => i.applied)
  return (
    <main className="container dashboard-page shop-page">
      <h1 className="page-title">{t('크레딧 상점')}</h1>
      <p className="muted small shop-notice">{t('출석·글·모의투자 수익으로 번 크레딧으로 블로그를 꾸며요. 아래 티어를 사야 윗 티어가 열려요. 가입 때 받은 투자 전용 포인트로는 살 수 없어요.')}</p>
      {error && <p className="error" role="alert">{t('상점을 불러오지 못했어요.')}</p>}
      {!shop && !error && <p className="muted center">{t('불러오는 중…')}</p>}
      {shop && (
        <>
          <div className="kpi-grid dashboard-kpis" style={{ '--kpi-cols': 2 } as CSSProperties}>
            <div className="kpi" data-tip={t('상점에서 쓸 수 있는 크레딧이에요')}>
              <span className="kpi-label">{t('크레딧')}</span>
              <span className="kpi-value">{num(shop.shopBalance)}</span>
              <span className="kpi-foot"><Link to="/points" className="kpi-prev" data-tip={t('출석하고 크레딧 얻는 법 보기')}>{t('크레딧 더 모으기')}</Link></span>
            </div>
            <div className="kpi" data-tip={t('가입할 때 받은 모의투자 자금이에요. 모의투자에만 쓰고 상점에서는 못 써요')}>
              <span className="kpi-label">{t('투자 전용 포인트')}</span>
              <span className="kpi-value muted">{points(shop.investBalance)}</span>
              <span className="kpi-foot"><span className="kpi-prev">{t('상점에서는 못 써요')}</span></span>
            </div>
          </div>

          <section className="shop-ladder" aria-label={t('티어와 가격')}>
            <ol>
              {TIERS.map((x) => (
                <li key={x.tier} data-tip={t('{0} 티어 기본 가격 {1}', { 0: tierName(x.tier), 1: credits(tierPrice(x.tier)) })}>
                  <TierEmblem tier={x.tier} size={40} label={false} />
                  <span className="shop-ladder-name">{tierName(x.tier)}</span>
                  <span className="shop-ladder-price">{shortCredits(tierPrice(x.tier))}</span>
                </li>
              ))}
            </ol>
          </section>

          <div className="settings-layout shop-tabs">
            <nav className="settings-nav" aria-label={t('아이템 종류')}>
              {ITEM_KINDS.map((k) => (
                <a key={k} href={`/shop#${k.toLowerCase()}`} aria-current={k === kind ? 'page' : undefined} data-tip={kindLabel(k)}
                  onClick={(e) => { e.preventDefault(); open(k) }}>
                  <NavIcon name={KIND_ICONS[k]} /><span>{kindShort(k)}</span>
                </a>
              ))}
            </nav>
            <div className="settings-panel" role="region" aria-label={kindLabel(kind)}>
              <div className="shop-head">
                <h2>{kindLabel(kind)}</h2>
                {applied && (
                  <button type="button" className="btn btn-text btn-small" disabled={busy !== null}
                    data-tip={t('이 종류의 꾸미기를 빼고 기본 모습으로 되돌려요')}
                    onClick={() => void run(kind, () => removeDecoration(kind), t('{0}을(를) 기본으로 되돌렸어요.', { 0: kindLabel(kind) }))}>{t('기본으로')}</button>
                )}
              </div>
              {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
          <div className="shop-grid">
            {items.map((it) => {
              const short = shop.shopBalance < it.price
              return (
                <article key={it.id} className={`shop-card tier-card t${it.tier}${it.applied ? ' applied' : ''}${it.locked ? ' locked' : ''}`}>
                  <Preview item={it} kind={kind} seed={me?.member?.handle ?? ''} name={me?.member?.nickname ?? ''} image={me?.member?.profileImageUrl ?? null} />
                  <div className="shop-body">
                    <TierEmblem tier={it.tier} size={26} />
                    <h3>{it.name}</h3>
                    {it.description && <p className="muted small">{it.description}</p>}
                    <span className="shop-price" data-tip={credits(it.price)}>{t('{0}크레딧', { 0: shortCredits(it.price) })}</span>
                    <div className="shop-actions">
                      {!it.owned ? (
                        it.locked ? (
                          <button type="button" className="btn btn-outline btn-small" disabled data-tip={needText(it)}>
                            <LockIcon />{t('잠김')}
                          </button>
                        ) : (
                          <button type="button" className="btn btn-primary btn-small" disabled={busy !== null || short}
                            data-tip={short ? t('크레딧이 {0} 모자라요', { 0: credits(it.price - shop.shopBalance) }) : t('크레딧으로 사요')}
                            onClick={() => buy(it)}>{busy === it.id ? t('사는 중…') : t('사기')}</button>
                        )
                      ) : it.applied ? (
                        <button type="button" className="btn btn-outline btn-small" disabled aria-pressed="true" data-tip={t('지금 내 블로그에 보이는 꾸미기예요')}>{t('적용 중')}</button>
                      ) : (
                        <button type="button" className="btn btn-outline btn-small" disabled={busy !== null} data-tip={t('내 블로그에 적용해요. 같은 종류는 하나만 보여요')}
                          onClick={() => void run(it.id, () => applyItem(it.id), t('{0}을(를) 적용했어요.', { 0: it.name }))}>{busy === it.id ? t('적용하는 중…') : t('적용')}</button>
                      )}
                    </div>
                    {it.locked && <span className="muted small shop-need">{needText(it)}</span>}
                    {it.owned && !it.active && <span className="muted small">{t('상점에서 내려간 아이템이에요. 산 사람은 계속 써요')}</span>}
                  </div>
                </article>
              )
            })}
          </div>
            </div>
          </div>
          {me?.member && <Link to={`/@${me.member.handle}`} className="btn btn-outline" data-tip={t('적용한 꾸미기를 내 블로그에서 봐요')}>{t('내 블로그에서 보기')}</Link>}
        </>
      )}
    </main>
  )
}

function LockIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}

/** 아이템 미리보기: 테마는 색과 버튼, 테두리는 내 프로필 사진, 표지는 그 표지, 효과는 작은 표지와 프로필에 효과를 건 모습 */
export function Preview({ item, kind, seed, name, image }: { item: Pick<ShopItem, 'value' | 'imageUrl' | 'name'>; kind: ItemKind; seed: string; name: string; image: string | null }) {
  const look = { itemId: 0, name: item.name, tier: 1, value: item.value, imageUrl: item.imageUrl }
  if (kind === 'THEME') {
    return (
      <div className="shop-preview" aria-hidden="true">
        <span className="shop-preview-theme blog-themed" style={{ '--blog-theme': item.value } as CSSProperties}>
          {/* 내 블로그 머리 부분을 줄여 그린 모습: 고른 탭 밑줄·링크·버튼이 이 색으로 바뀐다 */}
          <span className="theme-mock">
            <span className="theme-mock-tabs"><i className="on" /><i /><i /></span>
            <span className="theme-mock-text"><i /><i className="link" /><i /></span>
            <span className="theme-mock-foot"><span className="shop-swatch" style={{ '--swatch': item.value } as CSSProperties} /><span className="btn btn-primary btn-small">{t('새 글')}</span></span>
          </span>
        </span>
      </div>
    )
  }
  if (kind === 'FRAME') {
    const f = frameProps(look)
    return (
      <div className="shop-preview" aria-hidden="true">
        <span className="deco-frame" data-frame={f['data-frame']} style={f.style}><Avatar src={image} name={name || '?'} seed={seed} size={68} /></span>
      </div>
    )
  }
  if (kind === 'EFFECT') {
    // 효과는 그 효과가 놓인 티어의 표지·테두리 위에 걸어 보여 준다
    const tier = EFFECT_TIER[item.value] ?? 'diamond'
    return (
      <div className="shop-preview deco-effect-preview" data-effect={item.value} aria-hidden="true">
        <span className="deco-banner deco-effect-cover" data-banner={tier} />
        <span className="deco-frame deco-effect-avatar" data-frame={tier}><Avatar src={image} name={name || '?'} seed={seed} size={44} /></span>
      </div>
    )
  }
  const b = bannerProps(look)
  return <div className={`shop-preview deco-banner${b.image ? ' has-image' : ''}`} data-banner={b['data-banner']} style={b.style} aria-hidden="true" />
}
