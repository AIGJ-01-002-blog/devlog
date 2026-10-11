import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { addTag, MAX_TAGS, moveTag, tagFormatError, tagsApi, type TagSuggestion } from '../lib/tags'
import { t, tNodes } from '../lib/i18n'

/**
 * 발행 설정 창의 태그 입력 (docs/22 §3·§7, 010 US1·US4). Enter·쉼표로 추가, 띄어쓰기는 하이픈, ×·빈 칸 Backspace로 삭제,
 * 끌기·Alt + 방향키로 순서 바꾸기. 입력이 0.3초 멈추면 자동완성을 부르고(한글 조합 중에는 부르지 않는다), 늦게 온 응답은 버린다.
 * 자동완성이 실패하거나 후보가 없으면 목록을 띄우지 않고 입력을 막지 않는다.
 */
export function TagInput({ value, onChange, errors }: {
  value: string[]
  onChange: (tags: string[]) => void
  /** 칩 번호 → 서버 오류 문구, -1은 목록 전체(개수 초과) */
  errors: Map<number, string>
}) {
  const [text, setText] = useState('')
  const [suggestions, setSuggestions] = useState<TagSuggestion[]>([])
  const [active, setActive] = useState(-1)
  const [dragFrom, setDragFrom] = useState<number | null>(null)
  const composing = useRef(false)
  const [composeEnd, setComposeEnd] = useState(0)
  const seq = useRef(0)
  const chipRefs = useRef<(HTMLSpanElement | null)[]>([])
  const listId = useId()
  const hintId = useId()

  // 0.3초 멈춤 뒤 자동완성. 요청마다 번호를 매겨 마지막 요청의 응답만 쓴다
  useEffect(() => {
    const q = text.trim()
    const mySeq = ++seq.current
    setActive(-1)
    if (!q || composing.current) {
      setSuggestions([])
      return
    }
    const timer = setTimeout(() => {
      tagsApi.suggest(q)
        .then((list) => { if (seq.current === mySeq) setSuggestions(list.filter((s) => !value.includes(s.name))) })
        .catch(() => { if (seq.current === mySeq) setSuggestions([]) })
    }, 300)
    return () => clearTimeout(timer)
  }, [text, value, composeEnd])

  const commit = (raw: string) => {
    const next = addTag(value, raw)
    if (next !== value) onChange(next)
    setText('')
    setSuggestions([])
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.nativeEvent.isComposing || composing.current) return
    if (e.key === 'ArrowDown' && suggestions.length) {
      e.preventDefault()
      setActive((i) => (i + 1) % suggestions.length)
    } else if (e.key === 'ArrowUp' && suggestions.length) {
      e.preventDefault()
      setActive((i) => (i <= 0 ? suggestions.length - 1 : i - 1))
    } else if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      if (active >= 0 && suggestions[active]) commit(suggestions[active].name)
      else if (text.trim()) commit(text)
    } else if (e.key === 'Escape' && suggestions.length) {
      e.preventDefault()
      e.stopPropagation()
      setSuggestions([])
    } else if (e.key === 'Backspace' && !text && value.length) {
      onChange(value.slice(0, -1))
    }
  }

  const onChipKey = (e: KeyboardEvent<HTMLSpanElement>, i: number) => {
    if (!e.altKey || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return
    e.preventDefault()
    const to = e.key === 'ArrowLeft' ? i - 1 : i + 1
    const next = moveTag(value, i, to)
    if (next === value) return
    onChange(next)
    requestAnimationFrame(() => chipRefs.current[to]?.focus())
  }

  const listError = errors.get(-1)
  return (
    <div className="field tag-field">
      <label htmlFor={`${listId}-input`}>{t('태그')}</label>
      <div className="tag-box">
      <div className={`tag-input${listError ? ' invalid' : ''}`}>
        {value.map((tag, i) => {
          const error = errors.get(i) ?? tagFormatError(tag)
          return (
            <span key={tag} ref={(el) => { chipRefs.current[i] = el }} className={`tag-chip${error ? ' invalid' : ''}`}
                  tabIndex={0} draggable aria-label={t('태그 {0}{1}. Alt와 방향키로 순서를 바꿔요', { 0: tag, 1: error ? t(', 오류: {0}', { 0: error }) : '' })}
                  onKeyDown={(e) => onChipKey(e, i)}
                  onDragStart={() => setDragFrom(i)} onDragEnd={() => setDragFrom(null)}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => { e.preventDefault(); if (dragFrom != null) onChange(moveTag(value, dragFrom, i)); setDragFrom(null) }}>
              {error && <span aria-hidden="true">⚠ </span>}#{tag}
              <button type="button" className="tag-remove" aria-label={t('{0} 태그 지우기', { 0: tag })}
                      onClick={() => onChange(value.filter((_, j) => j !== i))}>×</button>
            </span>
          )
        })}
        <input id={`${listId}-input`} value={text} placeholder={value.length ? '' : t('태그 입력')} autoComplete="off"
               role="combobox" aria-expanded={suggestions.length > 0} aria-controls={listId} aria-describedby={hintId}
               aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
               onChange={(e) => {
                 // 붙여 넣은 쉼표도 나눈다
                 if (e.target.value.includes(',')) {
                   const parts = e.target.value.split(',')
                   let next = value
                   parts.slice(0, -1).forEach((p) => { next = addTag(next, p) })
                   if (next !== value) onChange(next)
                   setText(parts[parts.length - 1])
                 } else setText(e.target.value)
               }}
               onCompositionStart={() => { composing.current = true }}
               onCompositionEnd={(e) => { composing.current = false; setText(e.currentTarget.value); setComposeEnd((n) => n + 1) }}
               onKeyDown={onKeyDown}
               onBlur={() => setTimeout(() => setSuggestions([]), 150)} />
      </div>
      {suggestions.length > 0 && (
        <ul className="tag-suggest" role="listbox" id={listId}>
          {suggestions.map((s, i) => (
            <li key={s.name} id={`${listId}-${i}`} role="option" aria-selected={i === active}
                onMouseDown={(e) => { e.preventDefault(); commit(s.name) }}>
              <span>{s.name}{s.mine && <span className="muted small">  {t('· 내 태그')}</span>}</span>
              <span className="muted small">{s.postCount}</span>
            </li>
          ))}
        </ul>
      )}
      </div>
      <small id={hintId} className={value.length > MAX_TAGS ? 'error' : 'muted'}>
        {tNodes('{0} / {1} · Enter나 쉼표로 추가', { 0: value.length, 1: MAX_TAGS })}
      </small>
      {[...errors.entries()].filter(([i]) => i >= 0).map(([i, m]) => (
        <small key={i} className="error" role="alert">#{value[i] ?? ''}: {m}</small>
      ))}
      {listError && <small className="error" role="alert">{listError}</small>}
    </div>
  )
}
