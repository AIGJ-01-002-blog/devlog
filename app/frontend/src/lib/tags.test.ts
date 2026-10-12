import { describe, expect, it } from 'vitest'
import {
  addTag, blogTagPath, browseEndpoint, moveTag, normalizeTag, parseTagSort, tagErrors, tagFormatError, tagPath, tagsIndexPath,
} from './tags'

describe('normalizeTag (docs/22 §2-1)', () => {
  it.each([
    ['Spring Boot', 'spring-boot'], ['spring-boot', 'spring-boot'], ['#SPRING  BOOT', 'spring-boot'],
    ['ｓｐｒｉｎｇ　ｂｏｏｔ', 'spring-boot'], ['#JPA', 'jpa'], ['  C++ ', 'c++'], ['C#', 'c#'], ['Node.JS', 'node.js'],
    ['.NET', '.net'], ['스프링  부트', '스프링-부트'], ['spring--boot-', 'spring-boot'], ['자바_기초', '자바_기초'],
    ['​ja‮va', 'java'],
  ])('%s → %s', (raw, expected) => {
    expect(normalizeTag(raw)).toBe(expected)
  })

  it('형식에 맞지 않으면 이유를 준다', () => {
    for (const bad of ['...', '---', '#', 'ㅋㅋ', '🔥hot', 'a/b', 'c@d']) expect(tagFormatError(normalizeTag(bad))).not.toBeNull()
    expect(tagFormatError('a'.repeat(31))).toContain('30자')
    expect(tagFormatError('가'.repeat(30))).toBeNull()
  })
})

describe('태그 주소', () => {
  it('#은 인코딩하고 +와 .은 그대로 둔다', () => {
    expect(tagPath('c#')).toBe('/tags/c%23')
    expect(tagPath('c++')).toBe('/tags/c++')
    expect(tagPath('node.js')).toBe('/tags/node.js')
    expect(tagPath('.net')).toBe('/tags/.net')
    expect(tagPath('스프링-부트')).toBe('/tags/%EC%8A%A4%ED%94%84%EB%A7%81-%EB%B6%80%ED%8A%B8')
    expect(blogTagPath('gi-kim', 'c++')).toBe('/@gi-kim?tag=c%2B%2B')
  })
})

describe('칩 목록', () => {
  it('정규화한 모양이 같으면 처음 것만 남긴다', () => {
    let tags: string[] = []
    for (const t of ['Spring', 'spring', '#SPRING', ' ', 'jpa']) tags = addTag(tags, t)
    expect(tags).toEqual(['spring', 'jpa'])
  })

  it('순서를 옮긴다', () => {
    expect(moveTag(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
    expect(moveTag(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b'])
    expect(moveTag(['a', 'b'], 1, 2)).toEqual(['a', 'b'])
  })

  it('서버 오류를 칩 번호로 나눈다', () => {
    const m = tagErrors({ 'tags[2]': '형식', tags: '너무 많음', title: '제목' })
    expect(m.get(2)).toBe('형식')
    expect(m.get(-1)).toBe('너무 많음')
    expect(m.size).toBe(2)
  })
})

describe('태그 둘러보기 주소 (spec 089)', () => {
  it('기본값(빈 검색어·인기순)은 주소에서 빼고, 나머지는 남긴다', () => {
    expect(tagsIndexPath('', 'popular')).toBe('/tags')
    expect(tagsIndexPath('  ', 'popular')).toBe('/tags')
    expect(tagsIndexPath(' c# ', 'name')).toBe('/tags?q=c%23&sort=name')
    expect(tagsIndexPath('spring', 'popular')).toBe('/tags?q=spring')
  })

  it('모르는 정렬은 인기순', () => {
    expect(parseTagSort('recent')).toBe('recent')
    expect(parseTagSort('NAME')).toBe('popular')
    expect(parseTagSort(null)).toBe('popular')
  })

  it('API 주소에 정렬·검색어·커서를 싣는다', () => {
    expect(browseEndpoint('', 'popular')).toBe('/api/tags/browse?sort=popular')
    expect(browseEndpoint('c++', 'recent', '6')).toBe('/api/tags/browse?sort=recent&q=c%2B%2B&cursor=6')
  })
})
