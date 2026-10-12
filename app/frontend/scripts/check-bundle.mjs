// 첫 화면 무게 검사 (v1.65.1 속도 점검 뒤). vite build 뒤에 돌린다: node scripts/check-bundle.mjs
// index.html이 화면을 그리기 전에 받는 JS·CSS(gzip)를 더해 예산을 넘으면 실패한다.
// 예산은 v1.65.1 기준(JS·CSS 210KB, CSS 92KB)에 여유를 둔 값이다. 넘으면 무엇이 커졌는지 보고, 정말 필요하면 예산을 올리고 이유를 CHANGELOG에 적는다.
import { readFileSync, appendFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'

const BUDGET_TOTAL_KB = 250
const BUDGET_CSS_KB = 110
const dist = new URL('../dist/', import.meta.url).pathname

const html = readFileSync(join(dist, 'index.html'), 'utf8')
const files = [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+\.(?:js|css))"/g)].map((m) => m[1])
const rows = files.map((f) => {
  const body = readFileSync(join(dist, f))
  return { file: f, raw: body.length, gz: gzipSync(body, { level: 9 }).length, body }
}).sort((a, b) => b.gz - a.gz)

const kb = (n) => Math.round(n / 1024)
const total = rows.reduce((s, r) => s + r.gz, 0)
const css = rows.filter((r) => r.file.endsWith('.css')).reduce((s, r) => s + r.gz, 0)
const problems = []
if (kb(total) > BUDGET_TOTAL_KB) problems.push(`첫 화면 JS·CSS ${kb(total)}KB(gzip)가 예산 ${BUDGET_TOTAL_KB}KB를 넘었어요`)
if (kb(css) > BUDGET_CSS_KB) problems.push(`CSS ${kb(css)}KB(gzip)가 예산 ${BUDGET_CSS_KB}KB를 넘었어요`)
for (const r of rows) {
  // 글꼴이 CSS 안에 data: 주소로 들어가면 CSS가 수백 KB가 된다 (vite.config.ts assetsInlineLimit)
  if (r.file.endsWith('.css') && /url\(data:font\//.test(r.body.toString('utf8'))) problems.push(`${r.file}에 글꼴이 data: 주소로 들어갔어요`)
  // 다른 언어 사전은 고른 언어만 따로 받는다 (i18n.ts loadDictionary)
  if (/^assets\/(en|ja|zh)-/.test(r.file)) problems.push(`${r.file} 사전을 첫 화면에서 받아요`)
}

const table = ['| 파일 | 원본 | gzip |', '| --- | ---: | ---: |',
  ...rows.map((r) => `| ${r.file} | ${kb(r.raw)}KB | ${kb(r.gz)}KB |`),
  `| **합계** (예산 ${BUDGET_TOTAL_KB}KB, CSS ${BUDGET_CSS_KB}KB) | | **${kb(total)}KB** (CSS ${kb(css)}KB) |`].join('\n')
console.log(table)
if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## 첫 화면 무게\n\n${table}\n\n${problems.map((p) => `- ❌ ${p}`).join('\n')}\n`)
}
if (problems.length) {
  console.error(problems.map((p) => `✗ ${p}`).join('\n'))
  process.exit(1)
}
console.log(`✓ 첫 화면 무게 ${kb(total)}KB / ${BUDGET_TOTAL_KB}KB`)
