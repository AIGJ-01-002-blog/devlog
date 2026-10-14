import { describe, expect, it, vi } from 'vitest'
import { enhanceCodeBlocks, labelTaskItems, zoomableImage } from './postEnhance'

function body(html: string) {
  const el = document.createElement('div')
  el.innerHTML = html
  return el
}

describe('글 본문 다듬기 (097)', () => {
  it('코드 블록에 언어·[줄바꿈]·[복사]를 붙이고 그림 블록과 두 번째 호출은 건너뛴다', async () => {
    const el = body('<pre><code class="language-java">int a = 1;</code></pre><pre><code class="language-mermaid">graph TD</code></pre>')
    const writeText = vi.fn().mockResolvedValue(undefined)
    enhanceCodeBlocks(el, { writeText })
    enhanceCodeBlocks(el, { writeText })
    expect(el.querySelectorAll('.code-block')).toHaveLength(1)
    expect(el.querySelector('.code-lang')?.textContent).toBe('java')
    const [wrap, copy] = Array.from(el.querySelectorAll<HTMLButtonElement>('.code-tool'))
    expect(copy.dataset.tip).toBe('이 코드를 클립보드에 복사')
    wrap.click()
    expect(el.querySelector('pre')?.classList.contains('is-wrapped')).toBe(true)
    expect(wrap.getAttribute('aria-pressed')).toBe('true')
    copy.click()
    await Promise.resolve()
    expect(writeText).toHaveBeenCalledWith('int a = 1;')
  })

  it('클립보드를 못 쓰면 코드를 골라 두고 알린다', async () => {
    const el = body('<pre><code>x</code></pre>')
    enhanceCodeBlocks(el, { writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    el.querySelectorAll<HTMLButtonElement>('.code-tool')[1].click()
    await new Promise((r) => setTimeout(r, 0))
    expect(el.querySelector('.code-status')?.textContent).toContain('Ctrl+C')
  })

  it('할 일 체크 상자에 완료 여부와 내용을 이름으로 붙인다', () => {
    const el = body('<ul><li><input type="checkbox" checked disabled> 끝낸 일</li><li><input type="checkbox" disabled> 남은 일</li></ul>')
    labelTaskItems(el)
    const boxes = el.querySelectorAll('input')
    expect(boxes[0].getAttribute('aria-label')).toBe('완료: 끝낸 일')
    expect(boxes[1].getAttribute('aria-label')).toBe('할 일: 남은 일')
  })

  it('링크 안 사진은 크게 보기 대상이 아니다', () => {
    const el = body('<p><img src="a.png" alt="a"></p><a href="/x"><img src="b.gif" alt="b"></a>')
    document.body.append(el)
    const [plain, linked] = Array.from(el.querySelectorAll('img'))
    expect(zoomableImage(plain, el)).toBe(plain)
    expect(zoomableImage(linked, el)).toBeNull()
    el.remove()
  })
})
