import { describe, expect, it } from 'vitest'
import { adSlotConfig } from './AdSlot'

function doc(head: string): Document {
  return new DOMParser().parseFromString(`<html><head>${head}</head><body></body></html>`, 'text/html')
}

describe('AdSlot', () => {
  const script = '<script async src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1234567890"></script>'

  it('광고를 끈 기본 화면(메타 없음)에서는 자리를 그리지 않는다', () => {
    expect(adSlotConfig(doc(''))).toBeNull()
    expect(adSlotConfig(doc(script))).toBeNull()
  })

  it('메타와 광고 코드가 둘 다 있을 때만 자리를 그린다', () => {
    const meta = '<meta name="devlog-ad-slot" content="ca-pub-1234567890/987654321">'
    expect(adSlotConfig(doc(meta))).toBeNull()
    expect(adSlotConfig(doc(meta + script))).toEqual({ client: 'ca-pub-1234567890', slot: '987654321' })
  })

  it('형식이 틀린 메타는 무시한다', () => {
    expect(adSlotConfig(doc('<meta name="devlog-ad-slot" content="x/1">' + script))).toBeNull()
  })
})
