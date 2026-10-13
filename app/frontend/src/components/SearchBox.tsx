import { useEffect, useState, type FormEvent } from 'react'
import { SEARCH_MAX_LENGTH, warmSearch } from '../lib/search'
import { t } from '../lib/i18n'

/** 마우스처럼 정밀한 포인터가 있을 때만 자동 초점을 준다. 휴대폰에서 화면을 열자마자 키보드가 올라오지 않게 */
function finePointer(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(pointer: fine)').matches === true
}

/** 검색창. 50자까지 받고(서버도 자른다) 빈 검색어는 보내지 않는다. */
export function SearchBox({ initial, placeholder, onSearch, autoFocus = false }: {
  initial: string
  placeholder: string
  onSearch: (q: string) => void
  autoFocus?: boolean
}) {
  const [value, setValue] = useState(initial)
  useEffect(() => setValue(initial), [initial])
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const q = value.trim()
    if (q) onSearch(q)
  }
  return (
    <form className="search-box" role="search" onSubmit={submit}>
      <input type="search" value={value} maxLength={SEARCH_MAX_LENGTH} placeholder={placeholder} aria-label={placeholder}
             autoFocus={autoFocus && finePointer()} enterKeyHint="search" onFocus={() => warmSearch()} onChange={(e) => setValue(e.target.value)} />
      <button type="submit" className="btn btn-dark">{t('검색')}</button>
    </form>
  )
}
