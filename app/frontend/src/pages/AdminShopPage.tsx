import { useEffect, useState } from 'react'
import { AdminNav } from '../components/AdminNav'
import { ApiError } from '../lib/api'
import { num } from '../lib/dashboard'
import {
  adminShopItems, BANNER_PRESETS, createShopItem, FRAME_PRESETS, ITEM_KINDS, kindLabel, updateShopItem, type ItemKind, type ShopItem,
} from '../lib/shop'
import { Preview } from './ShopPage'

const EMPTY = { kind: 'THEME' as ItemKind, name: '', description: '', price: '3000', value: '#', imageUrl: '' }

/**
 * 관리자 상점 아이템 (#268). 아이템을 추가하고 가격·이름·올림/내림을 바꾼다. 지우지는 않는다(산 사람이 계속 쓰게 내리기만).
 * 표지 사진은 글쓰기에서 사진을 올려 받은 주소를 붙인다(기존 사진 저장소). 관리자 화면은 한국어만 쓴다.
 */
export function AdminShopPage() {
  const [items, setItems] = useState<ShopItem[] | null>(null)
  const [form, setForm] = useState(EMPTY)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

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
      await createShopItem({ kind: form.kind, name: form.name, description: form.description, price: Number(form.price),
        value: form.value === '#' ? '' : form.value, imageUrl: form.imageUrl || undefined })
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

  const presets = form.kind === 'FRAME' ? FRAME_PRESETS : form.kind === 'BANNER' ? BANNER_PRESETS : []
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
              setForm((f) => ({ ...f, kind, value: kind === 'THEME' ? '#' : kind === 'FRAME' ? 'gold' : 'sunset', price: kind === 'BANNER' ? '5000' : kind === 'FRAME' ? '2000' : '3000' }))
            }}>
              {ITEM_KINDS.map((k) => <option key={k} value={k}>{kindLabel(k)}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="shop-name">이름</label>
            <input id="shop-name" value={form.name} maxLength={40} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} aria-invalid={errors.name ? true : undefined} />
            {errors.name && <span className="error small">{errors.name}</span>}
          </div>
          <div className="field">
            <label htmlFor="shop-price">가격 (P)</label>
            <input id="shop-price" type="number" min={1} value={form.price} onChange={(e) => setForm((f) => ({ ...f, price: e.target.value }))} aria-invalid={errors.price ? true : undefined} />
            {errors.price && <span className="error small">{errors.price}</span>}
          </div>
          <div className="field">
            <label htmlFor="shop-value" data-tip={form.kind === 'THEME' ? '#2f9e44처럼 여섯 자리 색' : form.kind === 'FRAME' ? '정해 둔 모양을 고르거나 #RRGGBB 색' : '정해 둔 그러데이션. 사진을 붙이면 사진 뒤 바탕'}>
              {form.kind === 'THEME' ? '색' : '모양'}
            </label>
            <input id="shop-value" list="shop-presets" value={form.value} onChange={(e) => setForm((f) => ({ ...f, value: e.target.value }))} aria-invalid={errors.value ? true : undefined} />
            <datalist id="shop-presets">{presets.map((p) => <option key={p} value={p} />)}</datalist>
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
        {!items ? <p className="muted">불러오는 중…</p> : (
          <ul className="shop-admin-list">
            {items.map((it) => (
              <li key={it.id}>
                <Preview item={it} kind={it.kind} seed="preview" name="미" image={null} />
                <span>
                  <b>{it.name}</b> <span className="muted small">{kindLabel(it.kind)} · {num(it.price)}P{it.active ? '' : ' · 내림'}</span>
                </span>
                <span className="shop-actions">
                  <button type="button" className="btn btn-outline btn-small" data-tip="가격을 바꿔요. 이미 산 사람에게는 영향이 없어요" onClick={() => {
                    const v = prompt(`${it.name}의 새 가격 (P)`, String(it.price))
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
