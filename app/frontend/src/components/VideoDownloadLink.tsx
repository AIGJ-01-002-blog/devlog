import { useState, type MouseEvent } from 'react'
import { t } from '../lib/i18n'
import { downloadVideo, type ShortsItem } from '../lib/shorts'

/**
 * [영상 받기] 링크. 직접 받기 주소(videoDownloadUrl)가 있으면 서버(도쿄)에서 바로 받아 제목 이름으로 저장하고,
 * 실패하면 Cloudflare 주소로 다시 받는다. 주소가 없거나 둘 다 실패하면 링크 원래 동작(같은 주소 download)에 맡긴다.
 */
export function VideoDownloadLink({ item, fileName, className, tip }: {
  item: Pick<ShortsItem, 'videoUrl' | 'videoDownloadUrl'>; fileName: string; className: string; tip?: string
}) {
  const [busy, setBusy] = useState(false)
  if (!item.videoUrl) return null
  const onClick = async (e: MouseEvent<HTMLAnchorElement>) => {
    if (!item.videoDownloadUrl) return
    e.preventDefault()
    if (busy) return
    setBusy(true)
    try {
      if (!(await downloadVideo(item, fileName))) window.location.assign(item.videoUrl!)
    } catch {
      // 두 주소 모두 fetch로 못 받았으면 브라우저 기본 내려받기로 한 번 더 시도한다
      window.location.assign(item.videoUrl!)
    } finally {
      setBusy(false)
    }
  }
  return (
    <a className={className} href={item.videoUrl} download={fileName} onClick={(e) => void onClick(e)} aria-busy={busy}
      data-tip={tip ?? t('MP4 파일로 받아 SNS 앱에서 직접 올려요')}>
      {busy ? t('받는 중…') : t('영상 받기')}
    </a>
  )
}
