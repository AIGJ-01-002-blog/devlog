import { createElement, Fragment, useSyncExternalStore, type ReactNode } from 'react'

/**
 * 화면 언어 (spec 081). 한국어 문장을 그대로 키로 쓰고, 다른 언어는 locales/의 사전에서 찾는다.
 * 사전에 없는 문장은 한국어로 보인다(번역이 늦어도 화면이 비지 않게).
 * 고르는 순서: 주소의 ?lang= → 내가 고른 언어(이 브라우저) → 브라우저 언어 → 한국어.
 * 사전은 고른 언어 하나만 따로 받는다(loadDictionary). 한국어 화면은 다른 나라 사전 세 개(약 200KB)를 받지 않는다.
 */
export type Locale = 'ko' | 'en' | 'ja' | 'zh'

export const LOCALES: { code: Locale; label: string }[] = [
  { code: 'ko', label: '한국어' },
  { code: 'en', label: 'English' },
  { code: 'ja', label: '日本語' },
  { code: 'zh', label: '中文' },
]

type Dict = Record<string, string>
const DICTS: Partial<Record<Exclude<Locale, 'ko'>, Dict>> = {}
const LOADERS: Record<Exclude<Locale, 'ko'>, () => Promise<Dict>> = {
  en: () => import('./locales/en').then((m) => m.en),
  ja: () => import('./locales/ja').then((m) => m.ja),
  zh: () => import('./locales/zh').then((m) => m.zh),
}

/**
 * 지금 언어의 사전을 받는다. 화면 모듈이 불러올 때 t()를 부르는 곳이 있으니 main.tsx가 화면을 불러오기 전에 기다린다.
 * 받지 못하면(네트워크 오류) 한국어 문장이 보인다. 화면이 비지는 않는다.
 */
export async function loadDictionary(locale: Locale = current): Promise<void> {
  if (locale === 'ko' || DICTS[locale]) return
  try {
    DICTS[locale] = await LOADERS[locale]()
  } catch {
    // 사전 없이 한국어로 보인다
  }
}

function lookup(ko: string): string {
  return current === 'ko' ? ko : DICTS[current]?.[ko] ?? ko
}
const STORAGE_KEY = 'devlog.lang'

function isLocale(v: unknown): v is Locale {
  return v === 'ko' || v === 'en' || v === 'ja' || v === 'zh'
}

export function fromLanguageTag(tag: string | null | undefined): Locale | null {
  const base = (tag ?? '').toLowerCase().split('-')[0]
  return isLocale(base) ? base : null
}

function detect(): Locale {
  if (typeof window === 'undefined') return 'ko'
  const fromUrl = fromLanguageTag(new URLSearchParams(window.location.search).get('lang'))
  if (fromUrl) return fromUrl
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY)
    if (isLocale(saved)) return saved
  } catch {
    // 저장소를 못 쓰는 브라우저(사생활 보호 등)는 브라우저 언어로 정한다
  }
  // 테스트는 jsdom의 en-US 대신 한국어 화면을 본다
  if (import.meta.env.MODE === 'test') return 'ko'
  for (const tag of navigator.languages ?? [navigator.language]) {
    const l = fromLanguageTag(tag)
    if (l) return l
  }
  return 'ko'
}

let current: Locale = detect()
const listeners = new Set<() => void>()
if (typeof document !== 'undefined') document.documentElement.lang = current

export function getLocale(): Locale {
  return current
}

/**
 * 언어를 바꾼다. 화면 곳곳의 문장이 불러올 때 한 번 번역되므로, 바꾼 뒤에는 페이지를 다시 불러온다.
 * ?lang= 이 주소에 있으면 지워서 고른 언어가 이긴다.
 */
export function changeLocale(next: Locale): void {
  setLocale(next)
  const url = new URL(window.location.href)
  if (url.searchParams.has('lang')) {
    url.searchParams.delete('lang')
    window.location.replace(url.toString())
  } else {
    window.location.reload()
  }
}

/** 바꾼 언어의 사전은 loadDictionary로 받는다 (changeLocale은 다시 불러오면서 받는다) */
export function setLocale(next: Locale, remember = true): void {
  if (remember) {
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 저장하지 못해도 이번 방문에는 바뀐 언어로 보인다
    }
  }
  if (next === current) return
  current = next
  document.documentElement.lang = next
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** 지금 언어. 언어가 바뀌면 이 훅을 쓴 화면이 다시 그려진다. */
export function useLocale(): Locale {
  return useSyncExternalStore(subscribe, getLocale, getLocale)
}

/** 한국어 문장을 지금 언어로. {0}·{name} 자리는 vars로 채운다. */
export function t(ko: string, vars?: Record<string | number, string | number | null | undefined>): string {
  const text = lookup(ko)
  if (!vars) return text
  return text.replace(/\{(\w+)\}/g, (whole, k: string) => (k in vars ? String(vars[k] ?? '') : whole))
}

/** 문장 가운데에 링크·굵은 글씨 같은 요소를 끼울 때. {0} 자리에 vars의 요소가 들어간다 */
export function tNodes(ko: string, vars: Record<string | number, ReactNode>): ReactNode[] {
  const text = lookup(ko)
  return text.split(/\{(\w+)\}/).map((part, i) =>
    i % 2 === 0 ? part : createElement(Fragment, { key: i }, part in vars ? vars[part] : `{${part}}`))
}

/** 숫자·날짜 서식에 쓰는 BCP 47 태그 */
export function intlTag(locale: Locale = current): string {
  return { ko: 'ko-KR', en: 'en-US', ja: 'ja-JP', zh: 'zh-CN' }[locale]
}
