import { useEffect, useState } from 'react'
import { AdminNav } from '../components/AdminNav'
import { ApiError } from '../lib/api'
import { num } from '../lib/dashboard'
import {
  adminShopItems, createShopItem, EFFECT_PRESETS, ITEM_KINDS, kindLabel, TIER_PRESETS, tierKey, tierPrice, TIERS, updateShopItem, type ItemKind, type ShopItem,
} from '../lib/shop'
import { TierEmblem } from '../components/TierEmblem'
import { Preview } from './ShopPage'

const EMPTY = { kind: 'THEME' as ItemKind, tier: '1', name: '', description: '', price: '100', value: '#', imageUrl: '' }
const HEX = /^#[0-9a-fA-F]{6}$/
const EFFECT_NAMES: Record<string, string> = { glow: '빛무리', stardust: '별가루', radiance: '광휘' }
/** 색 칸의 처음 색: 아직 안 골랐으면 회색에서 시작한다 */
const pickable = (v: string) => (HEX.test(v) ? v : '#5c5f66')
const kindFromHash = (): ItemKind => {
  const h = window.location.hash.slice(1).toUpperCase()
  return (ITEM_KINDS as readonly string[]).includes(h) ? (h as ItemKind) : 'THEME'
}

/** 종류·티어를 바꾸면 모양 칸의 기본값: 테마는 색을 직접, 테두리·표지는 그 티어 모양, 효과는 빛무리 */
function defaultValue(kind: ItemKind, tier: number): string {
  return kind === 'THEME' ? '#' : kind === 'EFFECT' ? 'glow' : tierKey(tier)
}

/**
 * 관리자 상점 아이템 (#268). 아이템을 티어와 함께 추가하고 가격·이름·올림/내림을 바꾼다. 가격은 티어 기본값(100 × 10^(티어-1))으로 채워 준다. 지우지는 않는다(산 사람이 계속 쓰게 내리기만).
 * 표지 사진은 글쓰기에서 사진을 올려 받은 주소를 붙인다(기존 사진 저장소). 관리자 화면은 한국어만 쓴다.
 */
export function AdminShopPage() {
  const [items, setItems] = useState<ShopItem[] | null>(null)
  const [form, setForm] = useState(EMPTY)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  // 목록은 종류별 탭으로 나눠 한 번에 한 종류만 보여 준다 (블로그 주인 요청). 주소 #theme 등으로 남겨 새로 고쳐도 그 탭에 있는다
  const [tab, setTab] = useState<ItemKind>(kindFromHash)
  const openTab = (k: ItemKind) => { setTab(k); window.history.replaceState(null, '', `#${k.toLowerCase()}`) }
  useEffect(() => {
    // 주소창에서 #frame 등을 직접 바꾸거나 뒤로 가기를 해도 탭이 따라간다
    const sync = () => setTab(kindFromHash())
    window.addEventListener('hashchange', sync)
    window.addEventListener('popstate', sync)
    return () => { window.removeEventListener('hashchange', sync); window.removeEventListener('popstate', sync) }
  }, [])

  useEffect(() => { document.title = '상점 아이템 - 관리자 - devlog' }, [])
  const reload = () => adminShopItems().then(setItems).catch(() => setMessage({ ok: false, text: '아이템을 불러오지 못했어요.' }))
  useEffect(() => { void reload() }, [])

  const fail = (e: unknown) => {
    if (e instanceof ApiError) {
      setErrors(Object.fromEntries(e.errors.map((x) => [x.field, x.message])))
      setMessage({ ok: false, text: e.errors.length ? '고칠 값이 있어요.' : e.message })
    } else setMessage({ ok: false, text: '저장하지 못했어요.' })
  }

  const create = async () => {
    setBusy(true)
    setErrors({})
    setMessage(null)
    try {
      await createShopItem({ kind: form.kind, tier: Number(form.tier), name: form.name, description: form.description, price: Number(form.price),
        value: form.value === '#' ? '' : form.value, imageUrl: form.imageUrl || undefined })
      openTab(form.kind)
      setForm(EMPTY)
      setMessage({ ok: true, text: '아이템을 올렸어요.' })
      await reload()
    } catch (e) { fail(e) } finally { setBusy(false) }
  }

  const patch = async (it: ShopItem, body: { price?: number; active?: boolean }, done: string) => {
    setMessage(null)
    try {
      await updateShopItem(it.id, body)
      setMessage({ ok: true, text: done })
      await reload()
    } catch (e) { fail(e) }
  }

  const setValue = (value: string) => setForm((f) => ({ ...f, value }))
  const frameColor = form.kind === 'FRAME' && !TIER_PRESETS.includes(form.value)
  const shown = items?.filter((it) => it.kind === tab) ?? []
  return (
    <main className="container narrow admin">
      <h1 className="page-title">상점 아이템</h1>
      <AdminNav />
      <section className="admin-card">
        <h2>새 아이템</h2>
        <form className="shop-admin-form" onSubmit={(e) => { e.preventDefault(); void create() }}>
          <div className="field">
            <label htmlFor="shop-kind">종류</label>
            <select id="shop-kind" value={form.kind} onChange={(e) => {
              const kind = e.target.value as ItemKind
              setForm((f) => ({ ...f, kind, value: defaultValue(kind, Number(f.tier)) }))
            }}>
              {ITEM_KINDS.map((k) => <option key={k} value={k}>{kindLabel(k)}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="shop-tier" data-tip="윗 티어는 바로 아래 티어를 가진 회원만 살 수 있어요. 바꾸면 가격이 티어 기본값으로 바뀌어요">티어</label>
            <select id="shop-tier" value={form.tier} onChange={(e) => {
              const tier = Number(e.target.value)
              setForm((f) => ({ ...f, tier: e.target.value, price: String(tierPrice(tier)), value: f.kind === 'THEME' || f.kind === 'EFFECT' || HEX.test(f.value) ? f.value : tierKey(tier) }))
            }} aria-invalid={errors.tier ? true : undefined}>
              {TIERS.map((x) => <option key={x.tier} value={x.tier}>{x.tier}. {x.name}</option>)}
            </select>
            {errors.tier && <span className="error small">{errors.tier}</span>}
          </div>
          <div className="field">
            <label htmlFor="shop-name">이름</label>
            <input id="shop-name" value={form.name} maxLength={40} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} aria-invalid={errors.name ? true : undefined} />
            {errors.name && <span className="error small">{errors.name}</span>}
          </div>
          <div className="field">
            <label htmlFor="shop-price">가격 (크레딧)</label>
            <input id="shop-price" type="number" min={1} value={form.price} onChange={(e) => setForm((f) => ({ ...f, price: e.target.value }))} aria-invalid={errors.price ? true : undefined} />
            {errors.price && <span className="error small">{errors.price}</span>}
          </div>
          <div className="field shop-value-field">
            <label htmlFor="shop-value">{form.kind === 'THEME' ? '색' : form.kind === 'EFFECT' ? '효과' : '모양'}</label>
            {form.kind === 'EFFECT' ? (
              <select id="shop-value" value={form.value} onChange={(e) => setValue(e.target.value)} aria-invalid={errors.value ? true : undefined}>
                {EFFECT_PRESETS.map((p) => <option key={p} value={p}>{EFFECT_NAMES[p]}</option>)}
              </select>
            ) : form.kind === 'THEME' ? null : (
              <select id="shop-value" value={frameColor ? 'color' : form.value} aria-invalid={errors.value ? true : undefined}
                onChange={(e) => setValue(e.target.value === 'color' ? pickable(form.value) : e.target.value)}>
                {TIERS.map((x) => <option key={x.key} value={x.key}>{x.name} 모양</option>)}
                {form.kind === 'FRAME' && <option value="color">한 가지 색 (직접 고르기)</option>}
              </select>
            )}
            {(form.kind === 'THEME' || frameColor) && (
              <span className="shop-color">
                <input type="color" aria-label="색 고르기" value={pickable(form.value)} onChange={(e) => setValue(e.target.value)} />
                <input id={form.kind === 'THEME' ? 'shop-value' : 'shop-color'} value={form.value} maxLength={7} placeholder="#2f9e44" spellCheck={false}
                  aria-label="색 코드" onChange={(e) => setValue(e.target.value)} aria-invalid={errors.value ? true : undefined} />
              </span>
            )}
            <span className="muted small">{form.kind === 'THEME' ? '색 칸을 눌러 고르거나 #2f9e44처럼 # 뒤에 여섯 자리를 적어요. 이 색이 블로그의 버튼·탭·링크 색이 돼요.'
              : form.kind === 'FRAME' ? '티어 모양은 그 티어의 금속 테두리예요. 한 가지 색을 고르면 그 색 테두리가 돼요.'
              : form.kind === 'EFFECT' ? '효과는 블로그 머리에 빛을 더해요. 아래 미리보기에서 바로 볼 수 있어요.'
              : '고른 티어 무늬가 표지가 돼요. 사진 주소를 붙이면 사진이 표지가 되고 무늬는 사진 뒤 바탕이 돼요.'}</span>
            {errors.value && <span className="error small">{errors.value}</span>}
          </div>
          {form.kind === 'BANNER' && (
            <div className="field">
              <label htmlFor="shop-image" data-tip="글쓰기 화면에서 사진을 올리고 받은 주소를 붙여요. 이 블로그 사진 저장소 주소만 받아요">표지 사진 주소 (선택)</label>
              <input id="shop-image" value={form.imageUrl} onChange={(e) => setForm((f) => ({ ...f, imageUrl: e.target.value }))} aria-invalid={errors.imageUrl ? true : undefined} />
              {errors.imageUrl && <span className="error small">{errors.imageUrl}</span>}
            </div>
          )}
          <div className="field">
            <label htmlFor="shop-desc">설명</label>
            <input id="shop-desc" value={form.description} maxLength={200} onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))} />
          </div>
          <div className="field">
            <span className="muted small">미리보기</span>
            <Preview item={{ name: form.name, value: form.value, imageUrl: form.imageUrl || null }} kind={form.kind} seed="preview" name="미" image={null} />
          </div>
          <div className="field">
            <button type="submit" className="btn btn-primary" disabled={busy} data-tip="상점에 바로 올라가요">{busy ? '올리는 중…' : '올리기'}</button>
          </div>
        </form>
        {message && <p className={message.ok ? 'ok small' : 'error small'} role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
      </section>
      <section className="admin-card">
        <h2>아이템 목록</h2>
        <nav className="tabs shop-admin-tabs" aria-label="아이템 종류">
          {ITEM_KINDS.map((k) => (
            <a key={k} href={`#${k.toLowerCase()}`} aria-current={k === tab ? 'page' : undefined} title={`${kindLabel(k)} 아이템만 보기`}
              onClick={(e) => { e.preventDefault(); openTab(k) }}>
              {kindLabel(k)} <span className="muted small">{items?.filter((it) => it.kind === k).length ?? ''}</span>
            </a>
          ))}
        </nav>
        {!items ? <p className="muted">불러오는 중…</p> : shown.length === 0 ? <p className="muted">이 종류의 아이템이 아직 없어요.</p> : (
          <ul className="shop-admin-list">
            {shown.map((it) => (
              <li key={it.id}>
                <Preview item={it} kind={it.kind} seed="preview" name="미" image={null} small />
                <span>
                  <TierEmblem tier={it.tier} /> <b>{it.name}</b> <span className="muted small">{num(it.price)}크레딧{it.active ? '' : ' · 내림'}</span>
                </span>
                <span className="shop-actions">
                  <button type="button" className="btn btn-outline btn-small" data-tip="가격을 바꿔요. 이미 산 사람에게는 영향이 없어요" onClick={() => {
                    const v = prompt(`${it.name}의 새 가격 (크레딧)`, String(it.price))
                    if (v !== null && v.trim()) void patch(it, { price: Number(v) }, '가격을 바꿨어요.')
                  }}>가격</button>
                  <button type="button" className="btn btn-outline btn-small" data-tip={it.active ? '상점에서 내려요. 이미 산 사람은 계속 써요' : '상점에 다시 올려요'}
                    onClick={() => void patch(it, { active: !it.active }, it.active ? '내렸어요.' : '다시 올렸어요.')}>{it.active ? '내리기' : '올리기'}</button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  )
}
