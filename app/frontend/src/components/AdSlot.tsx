import { useEffect, useRef } from 'react'
import { t } from '../lib/i18n'

declare global {
  interface Window { adsbygoogle?: unknown[] }
}

/** 서버가 광고를 켜고 자리 ID를 정했을 때만 내는 메타(AdSense.slotMeta)와, 그 화면에 실린 광고 코드가 둘 다 있어야 자리를 그린다 */
export function adSlotConfig(doc: Document = document): { client: string; slot: string } | null {
  const content = doc.querySelector('meta[name="devlog-ad-slot"]')?.getAttribute('content') ?? ''
  const m = /^(ca-pub-\d{10,20})\/(\d{6,20})$/.exec(content)
  if (!m || !doc.querySelector('script[src*="adsbygoogle.js"]')) return null
  return { client: m[1], slot: m[2] }
}

/**
 * 글 아래 광고 자리 (spec 107). 광고 스위치(ADS_PROVIDER)가 none인 지금은 메타가 없어 아무것도 그리지 않는다.
 * 켜면 처음부터 높이를 잡아 두어 광고가 늦게 와도 본문 아래가 밀리지 않는다(CLS 0). 광고 코드는 인라인 스크립트 없이
 * 앱 번들에서 큐에 넣는다(CSP script-src 'self').
 * 광고 코드는 서버가 글 화면 문서에만 nonce와 함께 싣는다(spec 106). 그래서 홈·목록에서 화면 안 이동으로 글에 들어오면
 * 광고 코드가 없어 자리를 그리지 않는다. 검색에서 글로 바로 들어온 방문에만 광고가 뜨는 것은 의도한 동작이다.
 */
export function AdSlot() {
  const config = adSlotConfig()
  const pushed = useRef(false)
  useEffect(() => {
    if (!config || pushed.current) return
    pushed.current = true
    try {
      ;(window.adsbygoogle = window.adsbygoogle || []).push({})
    } catch {
      // 광고가 막혀도 글 읽기는 그대로
    }
  }, [config])
  if (!config) return null
  return (
    <aside className="ad-slot" aria-label={t('광고')}>
      <ins className="adsbygoogle" style={{ display: 'block' }} data-ad-client={config.client} data-ad-slot={config.slot}
        data-ad-format="auto" data-full-width-responsive="true" />
    </aside>
  )
}
