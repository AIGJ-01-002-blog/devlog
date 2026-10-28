import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { anchorHref, Link } from './router'

describe('anchorHref', () => {
  it('돌아올 곳만 담은 쿼리는 href에서 뺀다', () => {
    expect(anchorHref('/login?redirect=%2F%40minseo%2Fposts%2F3')).toBe('/login')
    expect(anchorHref('/signup?redirect=%2Fwrite')).toBe('/signup')
    expect(anchorHref('/support?from=%2F%40minseo%2Fposts%2F3')).toBe('/support')
  })

  it('다른 쿼리와 다른 화면 주소는 그대로 둔다', () => {
    expect(anchorHref('/support?tab=mine')).toBe('/support?tab=mine')
    expect(anchorHref('/support?id=7&from=%2F')).toBe('/support?id=7')
    expect(anchorHref('/?tab=trending')).toBe('/?tab=trending')
    expect(anchorHref('/search?q=redirect')).toBe('/search?q=redirect')
    expect(anchorHref('/@minseo/posts/3')).toBe('/@minseo/posts/3')
  })
})

describe('Link', () => {
  it('href에는 경로만 남긴다', () => {
    const html = renderToStaticMarkup(<Link to="/login?redirect=%2Fmcp">로그인</Link>)
    expect(html).toBe('<a href="/login">로그인</a>')
  })
})
