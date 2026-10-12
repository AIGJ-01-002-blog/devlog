import { useCallback, useEffect, useRef, useState } from 'react'
import { InfiniteLoader } from '../components/InfiniteLoader'
import { TagRow } from '../components/TagRow'
import { takeInitialData } from '../lib/api'
import { navigate, useLocation } from '../lib/router'
import {
  MAX_TAG_LENGTH, parseTagSort, TAG_SORTS, tagsApi, tagsIndexPath, type TagBrowsePage, type TagRowData, type TagSort,
} from '../lib/tags'
import { intlTag, t } from '../lib/i18n'

/** 검색어를 주소에 옮기기 전에 기다리는 시간. 글자마다 서버를 부르지 않게 */
export const TAG_SEARCH_DEBOUNCE_MS = 250

/** 정렬 버튼 글자와 툴팁. 화면 언어가 바뀌어도 다시 읽게 함수로 둔다 */
function sortLabel(s: TagSort): { label: string; tip: string } {
  switch (s) {
    case 'name': return { label: t('이름순'), tip: t('이름 가나다·ABC 순서로') }
    case 'recent': return { label: t('최근순'), tip: t('최근에 글이 올라온 태그부터') }
    default: return { label: t('인기순'), tip: t('공개 글이 많은 태그부터') }
  }
}

/**
 * 전체 태그 (spec 089): 맨 위에 태그 검색과 정렬, 아래로 태그 줄을 무한 스크롤로 잇는다.
 * 줄마다 그 태그의 인기 글을 가로로 넘겨 본다. 검색어·정렬은 주소(?q=·?sort=)에 남아 새로 고침·공유에도 그대로다.
 */
export function TagsPage() {
  const { search } = useLocation()
  const q = (search.get('q') ?? '').trim()
  const sort = parseTagSort(search.get('sort'))
  const [text, setText] = useState(q)
  const [total, setTotal] = useState<number | null>(null)
  // 서버가 함께 내려준 첫 줄들은 처음 그리는 목록에만 쓴다 (서버가 같은 주소의 검색어·정렬로 만든 것)
  const [first] = useState(() => ({ key: `${q}|${sort}`, page: takeInitialData<{ browse?: TagBrowsePage }>('tags')?.browse ?? null }))
  const key = `${q}|${sort}`

  useEffect(() => {
    document.title = t('태그 - devlog')
  }, [])

  // 뒤로 가기로 주소가 바뀌면 검색창도 따라간다
  useEffect(() => {
    const sync = () => setText((new URLSearchParams(location.search).get('q') ?? '').trim())
    window.addEventListener('popstate', sync)
    return () => window.removeEventListener('popstate', sync)
  }, [])

  useEffect(() => {
    if (text.trim() === q) return
    const id = setTimeout(() => navigate(tagsIndexPath(text, sort), { replace: true }), TAG_SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [text, q, sort])

  return (
    <main className="container tags-page">
      <header className="tags-head">
        <h1 className="page-title">{t('태그')}</h1>
        {total != null && <p className="muted tags-total" role="status">{t('태그 {0}개', { 0: total.toLocaleString(intlTag()) })}</p>}
      </header>
      <div className="tags-toolbar">
        <form className="tags-search" role="search" onSubmit={(e) => {
          e.preventDefault()
          navigate(tagsIndexPath(text, sort), { replace: true })
        }}>
          <span className="tags-search-hash" aria-hidden="true">#</span>
          <input type="search" value={text} maxLength={MAX_TAG_LENGTH + 1} placeholder={t('태그 이름으로 찾기')}
                 aria-label={t('태그 이름으로 찾기')} enterKeyHint="search" autoComplete="off" spellCheck={false}
                 onChange={(e) => setText(e.target.value)} />
        </form>
        <div className="segmented" role="group" aria-label={t('정렬')}>
          {TAG_SORTS.map((s) => (
            <button key={s} type="button" aria-pressed={sort === s} data-tip={sortLabel(s).tip}
                    onClick={() => navigate(tagsIndexPath(text, s), { replace: true })}>{sortLabel(s).label}</button>
          ))}
        </div>
      </div>
      <TagRows key={key} q={q} sort={sort} initial={first.key === key ? first.page : null} onTotal={setTotal} />
    </main>
  )
}

function TagRows({ q, sort, initial, onTotal }: {
  q: string
  sort: TagSort
  initial: TagBrowsePage | null
  onTotal: (n: number) => void
}) {
  const [rows, setRows] = useState<TagRowData[]>(initial?.items ?? [])
  const [cursor, setCursor] = useState<string | null>(initial?.nextCursor ?? null)
  const [loaded, setLoaded] = useState(initial != null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const total = useRef(onTotal)
  total.current = onTotal

  const load = useCallback(async (next: string | null) => {
    setLoading(true)
    setFailed(false)
    try {
      const page = await tagsApi.browse(q, sort, next)
      if (page.total != null) total.current(page.total)
      setRows((prev) => {
        const base = next ? prev : []
        const seen = new Set(base.map((r) => r.name))
        return [...base, ...page.items.filter((r) => !seen.has(r.name))]
      })
      setCursor(page.nextCursor)
      setLoaded(true)
    } catch {
      setFailed(true)
    } finally {
      setLoading(false)
    }
  }, [q, sort])

  useEffect(() => {
    if (initial?.total != null) total.current(initial.total)
    else if (!initial) void load(null)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (!loaded && failed) {
    return (
      <p className="feed-error" role="alert">{t('불러오지 못했어요')}{' '}
        <button type="button" className="btn btn-text" onClick={() => void load(null)}>{t('다시 시도')}</button>
      </p>
    )
  }
  if (!loaded) return <p className="muted center">{t('불러오는 중…')}</p>
  if (rows.length === 0) {
    return <div className="empty"><p>{q ? t('\'{0}\'에 맞는 태그가 없어요.', { 0: q }) : t('아직 태그가 없어요.')}</p></div>
  }
  return (
    <div className="tag-rows">
      {rows.map((r) => <TagRow key={r.name} row={r} />)}
      <InfiniteLoader hasMore={cursor != null} loading={loading} failed={failed} onMore={() => void load(cursor)}
                      failedText={t('태그를 더 불러오지 못했어요')} />
    </div>
  )
}
