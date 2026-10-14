import { count } from '../lib/admin'
import { t } from '../lib/i18n'

export interface BarPoint {
  /** 축에 쓰는 짧은 이름 (10.8) */
  label: string
  /** 툴팁·표에 쓰는 이름 (10월 8일) */
  fullLabel: string
  value: number
}

/**
 * 관리자 통계의 막대그래프 (062). 계열이 하나라 범례 없이 제목이 이름을 대신한다.
 * 막대마다 마우스를 올리면 날짜와 값이 툴팁으로 뜨고(막대보다 넓은 칸이 대상), 화면 읽기 프로그램은 같은 값을 표로 읽는다.
 */
export function BarChart({ title, unit, points }: { title: string; unit: string; points: BarPoint[] }) {
  const max = Math.max(0, ...points.map((p) => p.value))
  const total = points.reduce((s, p) => s + p.value, 0)
  // 축 이름은 처음·가운데·끝만 (좁은 화면에서 겹치지 않게)
  const marks = new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])
  return (
    <figure className="bar-chart">
      <figcaption className="bar-chart-head">
        <span className="bar-chart-title">{title}</span>
        <span className="muted small">{t('합계 {0}{1} · 가장 많은 날 {2}{1}', { 0: count(total), 1: unit, 2: count(max) })}</span>
      </figcaption>
      <div className="bar-chart-plot" aria-hidden="true">
        <span className="bar-chart-max">{count(max)}</span>
        <div className="bar-chart-bars">
          {points.map((p) => (
            <div key={p.fullLabel} className="bar-chart-col" data-tip={`${p.fullLabel} · ${count(p.value)}${unit}`}>
              <div className={`bar-chart-bar${p.value === 0 ? ' zero' : ''}`} style={{ height: max === 0 ? 0 : `${(p.value / max) * 100}%` }} />
            </div>
          ))}
        </div>
      </div>
      <div className="bar-chart-axis" aria-hidden="true">
        {points.map((p, i) => <span key={p.fullLabel}>{marks.has(i) ? p.label : ''}</span>)}
      </div>
      <table className="sr-only">
        <caption>{t('{0} 날짜별 표', { 0: title })}</caption>
        <thead><tr><th scope="col">{t('날짜')}</th><th scope="col">{title}</th></tr></thead>
        <tbody>{points.map((p) => <tr key={p.fullLabel}><th scope="row">{p.fullLabel}</th><td>{count(p.value)}{unit}</td></tr>)}</tbody>
      </table>
    </figure>
  )
}
