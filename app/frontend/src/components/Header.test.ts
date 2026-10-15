import { describe, expect, it } from 'vitest'
import { headerItems, toolItems } from './Header'
import { blogTone } from '../pages/BlogPage'

describe('머리말 메뉴 (063)', () => {
  it('피드 · 좋아한 글 · 태그 · 문의·신고 · 릴리스 노트 순서이고 모두 툴팁 글이 있다', () => {
    const items = headerItems('/')
    expect(items.map((i) => i.label)).toEqual(['피드', '좋아한 글', '태그', '문의·신고', '릴리스 노트'])
    expect(items.every((i) => i.tip.length > 0)).toBe(true)
    expect(items.filter((i) => i.memberOnly).map((i) => i.label)).toEqual(['피드', '좋아한 글'])
  })

  it('문의·신고에는 지금 화면 주소를 넘긴다', () => {
    expect(headerItems('/@minseo/posts/3').find((i) => i.label === '문의·신고')!.to).toBe('/support?from=%2F%40minseo%2Fposts%2F3')
    expect(headerItems('/support').find((i) => i.label === '문의·신고')!.to).toBe('/support')
  })
})

describe('머리말 도구 (v2.2.0)', () => {
  it('켠 기능만 쇼츠 스튜디오 · 매매 일지 · 자동매매 · 모의투자 순으로 머리말에 두고 모두 툴팁 글이 있다', () => {
    const all = toolItems({ shorts: true, trades: true, points: true })
    expect(all.map((i) => i.to)).toEqual(['/studio', '/trades', '/trade', '/paper'])
    expect(all.every((i) => i.tip.length > 0)).toBe(true)
    expect(toolItems({ shorts: true, trades: false, points: true }).map((i) => i.to)).toEqual(['/studio'])
    expect(toolItems({ shorts: false, trades: true }).map((i) => i.to)).toEqual(['/trades', '/trade'])
    expect(toolItems(undefined)).toEqual([])
  })
})

describe('블로그 표지 색 (063)', () => {
  it('같은 블로그는 같은 색, 0~3 중 하나', () => {
    for (const h of ['minseo', 'a', 'devlog', 'z9']) {
      expect(blogTone(h)).toBe(blogTone(h))
      expect([0, 1, 2, 3]).toContain(blogTone(h))
    }
  })
})
