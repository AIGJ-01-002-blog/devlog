import { Link } from '../lib/router'
import { t } from '../lib/i18n'
import { monthDay } from '../lib/format'
import { PLAYLISTS, type ShortsBatch, type ShortsItem } from '../lib/shorts'

/**
 * 영상으로 만들 차례인 대본(상태 "대본")을 먼저 쓴 순서대로 줄 세운다 (spec 107 FR-024). 같은 시각이면 번호 순.
 * 쇼츠 탭 색 줄은 영상을 만든 순서로 정해지니, 쓴 순서대로 만들고 올리면 줄이 어긋나지 않는다.
 */
export function draftQueue(items: ShortsItem[]): ShortsItem[] {
  return items.filter((it) => it.status === 'DRAFT')
    .sort((a, b) => (a.createdAt < b.createdAt ? -1 : a.createdAt > b.createdAt ? 1 : a.id - b.id))
}

/** 쇼츠 스튜디오의 대본 큐 화면 (/studio?queue=1). 파이프라인은 건드리지 않고 목록 API 결과만 다시 줄 세운다 */
export function StudioQueue({ items, batches, onOpen }: { items: ShortsItem[]; batches: ShortsBatch[]; onOpen: () => void }) {
  const queue = draftQueue(items)
  const batchTitle = (id: number | null) => batches.find((b) => b.id === id)?.title ?? null
  return (
    <div className="studio-queue">
      <h2>{t('대본 큐')}</h2>
      <p className="muted small">
        {t('아직 영상으로 만들지 않은 대본 {0}편을 먼저 쓴 순서대로 놓았어요. 만든 영상은 만든 순서대로 올려야 쇼츠 탭 한 줄의 색이 맞아요.', { 0: queue.length })}
      </p>
      {queue.length === 0
        ? <p className="muted">{t('만들 차례인 대본이 없어요.')}</p>
        : (
          <ol className="studio-queue-list">
            {queue.map((it) => {
              const batch = batchTitle(it.batchId)
              const playlist = PLAYLISTS.find((p) => p.id === it.playlist)?.label
              return (
                <li key={it.id}>
                  <Link to={`/studio?id=${it.id}`} onClick={onOpen} data-tip={t('대본을 열어 다듬고 [영상 만들기]를 눌러요')}>{it.title}</Link>
                  <span className="muted small">
                    {playlist && <span className="badge" data-tip={t('이 편을 넣을 재생목록 묶음')}>{playlist}</span>}{playlist && ' '}
                    {batch && <>{batch}{it.topic !== null && ` · ${t('{0}번 주제', { 0: it.topic })}`} · </>}
                    {t('장면 {0}개', { 0: it.scenes.length })} · {t('약 {0}초', { 0: it.estimatedSeconds })}
                    {it.createdAt && <> · <span data-tip={t('대본을 쓴 날')}>{monthDay(it.createdAt)}</span></>}
                  </span>
                </li>
              )
            })}
          </ol>
        )}
    </div>
  )
}
