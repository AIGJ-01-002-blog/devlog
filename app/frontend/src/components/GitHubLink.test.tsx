// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { describe, expect, it } from 'vitest'
import { GITHUB_ORG_URL, GitHubLink } from './GitHubLink'
import { SiteFooter } from './SiteFooter'

async function mount(node: React.ReactNode) {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const el = document.createElement('div'); document.body.appendChild(el)
  await act(async () => { createRoot(el).render(node) })
  return el
}

describe('GitHub 바로가기', () => {
  it('GitHub 조직을 새 탭에서 열고 툴팁으로 어디로 가는지 알린다', async () => {
    const a = (await mount(<GitHubLink className="btn" />)).querySelector('a')!
    expect(a.getAttribute('href')).toBe('https://github.com/AIGJ-01-002-blog')
    expect(a.target).toBe('_blank')
    expect(a.rel).toContain('noopener')
    expect(a.dataset.tip).toBe('devlog 소스 코드를 GitHub에서 보기 (새 탭)')
    expect(a.textContent).toBe('GitHub')
  })

  it('사이트 맨 아래에도 있어서 로그인한 사람도 찾는다', async () => {
    const el = await mount(<SiteFooter />)
    expect([...el.querySelectorAll('a')].some((a) => a.getAttribute('href') === GITHUB_ORG_URL)).toBe(true)
  })
})
