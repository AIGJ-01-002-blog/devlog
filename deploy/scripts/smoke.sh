#!/usr/bin/env bash
# 배포 뒤 공개 주소 연기 시험 (#374, 보안 점검 2026-10-16). 파드가 준비돼도 Cloudflare·ingress를 거친 바깥 주소가
# 안 열릴 수 있어, 사람이 처음 보는 화면 세 가지를 바깥에서 직접 연다. 하나라도 실패하면 1로 끝난다.
#   1) 첫 화면(/)이 200이고 HTML이다  2) /actuator/health가 UP이다  3) 첫 화면 목록의 맨 위 글을 읽을 수 있다(글이 없으면 건너뜀)
#
# 필요한 환경 변수: BASE_URL(예: overlay app.env의 SITE_BASE_URL)
# 선택: SMOKE_TRIES(기본 6), SMOKE_WAIT(초, 기본 10). 터널이 새 파드에 붙는 동안 잠깐 실패할 수 있어 몇 번 다시 시도한다
set -uo pipefail

base="${BASE_URL:?BASE_URL이 필요합니다}"
base="${base%/}"
tries="${SMOKE_TRIES:-6}"
wait="${SMOKE_WAIT:-10}"
body="$(mktemp)"
trap 'rm -f "$body"' EXIT

# get <경로> : 상태 코드를 찍고 본문은 $body에 둔다
get() {
  local c
  # 연결 실패도 curl이 000을 찍으므로 실패 코드는 따로 보지 않는다
  c=$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 -H 'Cache-Control: no-cache' "$base$1" 2>/dev/null) || true
  echo "${c:-000}"
}

check_once() {
  local code first
  code=$(get /)
  if [ "$code" != 200 ] || ! grep -qi '<html' "$body"; then
    echo "첫 화면: $code"; return 1
  fi
  code=$(get /actuator/health)
  if [ "$code" != 200 ] || ! grep -q '"status":"UP"' "$body"; then
    echo "health: $code $(head -c 200 "$body")"; return 1
  fi
  code=$(get /api/posts)
  if [ "$code" != 200 ]; then
    echo "글 목록: $code"; return 1
  fi
  first=$(python3 -c 'import json,sys
items=json.load(open(sys.argv[1])).get("items") or []
print(items[0]["id"] if items else "")' "$body" 2>/dev/null) || { echo "글 목록: JSON이 아닙니다"; return 1; }
  if [ -z "$first" ]; then
    echo "공개 글이 없어 글 확인은 건너뜁니다."
    return 0
  fi
  # 화면 주소는 어느 경로든 같은 index.html을 주므로, 글 내용은 API로 확인한다
  code=$(get "/api/posts/$first")
  if [ "$code" != 200 ] || ! grep -q '"title"' "$body"; then
    echo "글 $first: $code"; return 1
  fi
  echo "첫 화면·health·글($first) 모두 정상"
}

for i in $(seq 1 "$tries"); do
  if out=$(check_once); then
    echo "$out"
    exit 0
  fi
  echo "연기 시험 ${i}/${tries} 실패: $out"
  [ "$i" -lt "$tries" ] && sleep "$wait"
done
echo "::error::공개 주소(${base}) 연기 시험이 ${tries}번 모두 실패했습니다."
exit 1
