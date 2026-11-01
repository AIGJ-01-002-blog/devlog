#!/usr/bin/env bash
# 학교 서버 → 오라클 서버 이전 (2026-11). '서버 이전' 워크플로(.github/workflows/server-migrate.yml)가 부른다.
#
#   KS=<학교 kubeconfig> KO=<오라클 kubeconfig> [TUNNEL_TOKEN_FILE=<터널 토큰 파일>] deploy/scripts/server-migrate.sh <모드>
#
# 모드
#   rehearsal  학교는 그대로 두고 데이터만 오라클로 복사해 본다(도메인은 학교 그대로). 오라클에 있던 데이터는 지워진다.
#              오라클 앱은 멈춘 채로 둔다. 같은 데이터로 두 앱이 함께 돌면 예약 발행·알림·쇼츠 올리기가 두 번 일어날 수 있다
#   cutover    학교 앱을 멈추고(점검) 최종 복사 → 수 비교가 같으면 도메인을 오라클로 옮기고 바깥 주소를 확인한다.
#              중간에 실패하면 학교 앱과 도메인을 그대로 되살린다
#   rollback   도메인을 학교로 되돌리고 학교 앱을 다시 켠다. 이전 뒤 오라클에 새로 쓰인 데이터는 옮기지 않는다
#
# 복사하는 것: PostgreSQL 전체(pg_dump → pg_restore), MinIO 버킷 전체(사진·첨부·쇼츠 영상).
# Redis(로그인 세션)는 옮기지 않는다. 이전 뒤 한 번 다시 로그인하면 된다. 두 서버의 비밀값(BLOG_SECRET_ENV)이 같아야 한다.
# 접속 정보는 클러스터 Secret에서 그때그때 읽고 파일로 남기지 않는다(backup.sh와 같은 방식).
set -euo pipefail
umask 077

MODE="${1:?모드를 주세요: rehearsal | cutover | rollback}"
: "${KS:?학교 kubeconfig 경로(KS)가 필요합니다}"
: "${KO:?오라클 kubeconfig 경로(KO)가 필요합니다}"
NS="${NAMESPACE:-blog}"
SITE="${SITE_BASE_URL:-https://devlog.life}"
APP_REPLICAS="${APP_REPLICAS:-2}"
ORACLE_MINIO_PORT="${ORACLE_MINIO_PORT:-19001}"
HERE="$(cd "$(dirname "$0")/../.." && pwd)"

say() { printf '\n== %s\n' "$*"; }
die() { printf '::error::%s\n' "$*" >&2; exit 1; }
ks() { kubectl --kubeconfig "$KS" -n "$NS" "$@"; }
ko() { kubectl --kubeconfig "$KO" -n "$NS" "$@"; }

WORK="$(mktemp -d)"
PF=""
cleanup() {
  [ -n "$PF" ] && kill "$PF" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# 표마다 정확한 행 수. 두 서버 결과를 diff로 비교한다
COUNT_SQL="SELECT t.table_name || ' ' || (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', t.table_schema, t.table_name), false, true, '')))[1]::text
FROM information_schema.tables t WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE' ORDER BY 1;"
counts() { # $1 = ks | ko
  "$1" exec -i statefulset/postgres -c postgres -- \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -v ON_ERROR_STOP=1' <<<"$COUNT_SQL"
}

scale_app() { # $1 = ks | ko, $2 = 개수
  "$1" scale deploy/blog-app --replicas="$2"
  if [ "$2" = 0 ]; then
    "$1" wait --for=delete pod -l app.kubernetes.io/name=blog-app --timeout=180s || true
  else
    "$1" rollout status deploy/blog-app --timeout=600s
  fi
}

# $1 = ks | ko. 터널 토큰으로 cloudflared를 그 클러스터에 띄운다(deploy.yml 9단계와 같은 방식)
tunnel_on() {
  local k="$1" kc
  [ -s "${TUNNEL_TOKEN_FILE:-}" ] || die "Secret CLOUDFLARE_TUNNEL_TOKEN이 없어 도메인을 옮길 수 없습니다."
  [ "$k" = ks ] && kc="$KS" || kc="$KO"
  "$k" create secret generic cloudflared-token --from-file=token="$TUNNEL_TOKEN_FILE" --dry-run=client -o yaml | "$k" apply -f -
  kustomize build "$HERE/deploy/k8s/addons/cloudflare-tunnel" | kubectl --kubeconfig "$kc" apply --server-side --force-conflicts -f -
  "$k" rollout status deploy/cloudflared --timeout=300s
}
tunnel_off() { "$1" delete deploy/cloudflared --ignore-not-found --wait=true; }

smoke() { BASE_URL="$SITE" SMOKE_TRIES="${SMOKE_TRIES:-12}" bash "$HERE/deploy/scripts/smoke.sh"; }

# 학교 → 오라클 복사. 오라클 앱은 멈춘 채로 끝낸다(수 비교를 앱이 쓰기 전에 하려고). $1 = strict면 수가 다를 때 실패
copy_data() {
  local strict="${1:-}"
  ko get statefulset/postgres >/dev/null 2>&1 && ko get deploy/blog-app >/dev/null 2>&1 \
    || die "오라클에 블로그가 아직 없습니다. 먼저 '블로그 배포'를 target=oracle로 실행해 주세요."

  say "학교 데이터 받기 (backup.sh)"
  KUBECONFIG="$KS" bash "$HERE/deploy/home/backup.sh" "$WORK/bk"

  say "오라클 앱 잠시 멈춤"
  scale_app ko 0

  say "오라클 DB 비우고 학교 덤프 넣기"
  ko exec -i statefulset/postgres -c postgres -- \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q -v ON_ERROR_STOP=1 -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"'
  ko exec -i statefulset/postgres -c postgres -- \
    sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges --exit-on-error' < "$WORK/bk/db.dump"

  say "오라클 파일 저장소에 사진·첨부·영상 넣기"
  local secret_name config_name bucket ak sk
  secret_name="$(ko get deployment blog-app -o 'jsonpath={.spec.template.spec.containers[0].envFrom[*].secretRef.name}' | tr ' ' '\n' | grep '^blog-app-' | head -1)"
  config_name="$(ko get deployment blog-app -o 'jsonpath={.spec.template.spec.containers[0].envFrom[*].configMapRef.name}' | tr ' ' '\n' | grep '^blog-app-' | head -1)"
  bucket="$(ko get configmap "$config_name" -o 'jsonpath={.data.S3_BUCKET}' 2>/dev/null || true)"
  bucket="${bucket:-blog-images}"
  ak="$(ko get secret "$secret_name" -o 'jsonpath={.data.S3_ACCESS_KEY}' | base64 --decode)"
  sk="$(ko get secret "$secret_name" -o 'jsonpath={.data.S3_SECRET_KEY}' | base64 --decode)"
  case "$ak$sk" in *:*|*$'\n'*) die "MinIO 열쇠에 ':'나 줄바꿈이 있어 mc로 넘길 수 없습니다." ;; esac
  ko port-forward svc/minio "$ORACLE_MINIO_PORT:9000" >"$WORK/pf.log" 2>&1 &
  PF=$!
  for _ in $(seq 1 30); do curl -fs "http://127.0.0.1:$ORACLE_MINIO_PORT/minio/health/ready" >/dev/null 2>&1 && break; sleep 1; done
  curl -fs "http://127.0.0.1:$ORACLE_MINIO_PORT/minio/health/ready" >/dev/null || die "오라클 MinIO에 연결하지 못했습니다: $(cat "$WORK/pf.log")"
  printf 'MC_HOST_oracle=http://%s:%s@127.0.0.1:%s\n' "$ak" "$sk" "$ORACLE_MINIO_PORT" > "$WORK/mc.env"
  unset ak sk
  local mc_image
  mc_image="$(ko get deployment blog-app -o 'jsonpath={.spec.template.spec.initContainers[?(@.name=="ensure-bucket")].image}')"
  mkdir -p "$WORK/mc-config"
  # --remove: 시험 복사 때 남은 파일까지 지워 학교와 똑같이 맞춘다. 끝나면 오라클 쪽 파일 수를 센다
  docker run --rm --network host --user "$(id -u):$(id -g)" --env-file "$WORK/mc.env" \
    -e MC_CONFIG_DIR=/mc -e BUCKET="$bucket" -v "$WORK/mc-config:/mc" -v "$WORK/bk/media:/src:ro" \
    --entrypoint sh "$mc_image" -c \
    'mc mb --ignore-existing "oracle/$BUCKET" >/dev/null && mc mirror --overwrite --remove --quiet /src "oracle/$BUCKET" && mc ls --recursive "oracle/$BUCKET" | wc -l' \
    > "$WORK/oracle-media-count"
  rm -f "$WORK/mc.env"
  kill "$PF" 2>/dev/null || true; PF=""

  say "수 비교 (학교 ↔ 오라클)"
  # join·diff가 같은 순서로 읽게 DB 정렬 규칙과 관계없이 C 순서로 다시 정렬한다
  counts ks | LC_ALL=C sort > "$WORK/school.txt"
  counts ko | LC_ALL=C sort > "$WORK/oracle.txt"
  local school_media oracle_media
  school_media="$(find "$WORK/bk/media" -type f | wc -l | tr -d ' ')"
  oracle_media="$(tr -d ' \n' < "$WORK/oracle-media-count")"
  {
    echo "### 서버 이전 수 비교 ($MODE)"
    echo
    echo "| 표 | 학교 | 오라클 |"
    echo "|---|---:|---:|"
    LC_ALL=C join -a1 -a2 -e '-' -o 0,1.2,2.2 "$WORK/school.txt" "$WORK/oracle.txt" | awk '{printf "| %s | %s | %s |\n", $1, $2, $3}'
    echo "| (파일 저장소) | $school_media | $oracle_media |"
  } | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
  if diff -q "$WORK/school.txt" "$WORK/oracle.txt" >/dev/null && [ "$school_media" = "$oracle_media" ]; then
    echo "수 비교: 모두 같음"
  elif [ "$strict" = strict ]; then
    diff "$WORK/school.txt" "$WORK/oracle.txt" || true
    die "학교와 오라클의 수가 다릅니다(파일 저장소 ${school_media} ↔ ${oracle_media}). 도메인을 옮기지 않습니다."
  else
    echo "::warning::수가 다른 표가 있습니다. 시험 복사는 학교가 계속 쓰는 중이라 다를 수 있습니다."
  fi
}

case "$MODE" in
  rehearsal)
    copy_data
    echo "끝: 오라클 앱은 멈춰 둡니다(cutover가 켭니다). 도메인은 학교 그대로입니다."
    ;;
  cutover)
    [ -s "${TUNNEL_TOKEN_FILE:-}" ] || die "Secret CLOUDFLARE_TUNNEL_TOKEN이 없어 도메인을 옮길 수 없습니다."
    say "점검 시작: 학교 앱 멈춤 (도메인은 아직 학교)"
    scale_app ks 0
    # 여기서부터 실패하면 학교 앱과 도메인을 되살린다
    restore_school() {
      echo "::error::이전 중 실패해 학교 서버로 되돌립니다."
      tunnel_off ko || true
      ks get deploy/cloudflared >/dev/null 2>&1 || tunnel_on ks || true
      scale_app ks "$APP_REPLICAS" || true
    }
    trap 'rc=$?; [ $rc -ne 0 ] && restore_school; cleanup; exit $rc' EXIT
    copy_data strict
    say "오라클 앱 켜기"
    scale_app ko "$APP_REPLICAS"
    say "도메인 옮기기: 학교 cloudflared 지우고 오라클에 띄우기"
    tunnel_off ks
    tunnel_on ko
    say "바깥 주소 확인 ($SITE)"
    smoke || die "오라클로 옮긴 뒤 $SITE 가 열리지 않습니다."
    trap cleanup EXIT
    echo "끝: $SITE 는 이제 오라클 서버에서 열립니다. 학교 앱은 멈춰 둡니다(되돌리기: 모드 rollback)."
    ;;
  rollback)
    say "도메인을 학교로 되돌리기"
    tunnel_off ko
    scale_app ks "$APP_REPLICAS"
    tunnel_on ks
    smoke || die "학교로 되돌렸지만 $SITE 가 열리지 않습니다."
    echo "::warning::이전 뒤 오라클에 새로 쓰인 글·댓글은 학교로 옮기지 않았습니다. 앞으로 배포를 학교로 보내려면 변수 DEPLOY_TARGET=school을 넣어 주세요."
    ;;
  *) die "모르는 모드: $MODE (rehearsal | cutover | rollback)" ;;
esac
