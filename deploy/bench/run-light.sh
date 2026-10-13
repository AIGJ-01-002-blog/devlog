#!/usr/bin/env bash
# 가벼운 구성(Compose) 측정. 저장소 루트에서: deploy/bench/run-light.sh (blog-app:bench 이미지가 있어야 함)
set -euo pipefail
cd "$(dirname "$0")"
. ./lib.sh
P=light
log "## 가벼운 구성: Docker Compose (앱 1 + PostgreSQL + Redis + MinIO)"
set -a; . ../k8s/overlays/local/secret.local.env; set +a
t0=$(date +%s)
if ! timeout 400 docker compose -p $P -f compose.light.yaml up -d; then
  docker compose -p $P -f compose.light.yaml ps -a; docker compose -p $P -f compose.light.yaml logs --tail=40; exit 1
fi
until curl -sf http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1 || curl -sf http://127.0.0.1:8080/api/auth/me >/dev/null 2>&1; do
  sleep 2; [ $(( $(date +%s) - t0 )) -lt 600 ] || { docker compose -p $P -f compose.light.yaml logs app | tail -50; exit 1; }
done
log "- 시작부터 앱 응답까지: $(( $(date +%s) - t0 ))초"
NET="${P}_default"
docker run -d --name ${P}-client --network "$NET" alpine:3.20 sleep 3600 >/dev/null
docker exec ${P}-client apk add -q --no-cache curl apache2-utils >/dev/null
# 데워 두기: JIT·커넥션 풀이 데워진 뒤에 잰다
for p in "${PATHS[@]}"; do docker exec ${P}-client sh -c "$LOOP" _ 100 "http://app:8080$p" >/dev/null; done
sleep 20
CS=$(docker compose -p $P -f compose.light.yaml ps -q)
log "- 쉬는 동안 메모리 합계: $(mem_sum $CS) MiB, CPU 합계: $(cpu_sum $CS)%"
log ""
log "| 컨테이너 | 메모리 |"; log "|---|---|"
docker stats --no-stream --format '| {{.Name}} | {{.MemUsage}} |' $CS | tee -a "$OUT"
log ""
log "### 응답 시간 (ms, ${N}번, 한 번에 하나)"
log "| 경로 | 거치는 곳 | p50 | p95 | 평균 |"; log "|---|---|---|---|---|"
for p in "${PATHS[@]}"; do
  printf '| `%s` | 같은 네트워크 컨테이너 → 앱 ' "$p" | tee -a "$OUT"; docker exec ${P}-client sh -c "$LOOP" _ "$N" "http://app:8080$p" | stats | tee -a "$OUT"
  printf '| `%s` | 서버(호스트) → 포트 공개 → 앱 ' "$p" | tee -a "$OUT"; bash -c "$LOOP" _ "$N" "http://127.0.0.1:8080$p" | stats | tee -a "$OUT"
done
log ""
log "### 부하 (동시 20개, 2000건)"
log "| 경로 | 초당 처리 | p95 ms | 실패 |"; log "|---|---|---|---|"
for p in "/api/auth/me" "/api/posts"; do
  printf '| `%s` ' "$p" | tee -a "$OUT"; docker exec ${P}-client sh -c "ab -q -k -c 20 -n 2000 http://app:8080$p 2>/dev/null" | awk '/Requests per second/ {r=$4} /^  95%/ {q=$2} /Failed requests/ {f=$3} END {printf "| %s | %s | %s |\n", r, q, f}' | tee -a "$OUT"
done
log "- 부하 직후 메모리 합계: $(mem_sum $CS) MiB"
docker rm -f ${P}-client >/dev/null
docker compose -p $P -f compose.light.yaml down -v >/dev/null 2>&1
log ""
