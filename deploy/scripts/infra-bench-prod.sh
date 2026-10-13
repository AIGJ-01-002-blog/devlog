#!/usr/bin/env bash
# 운영(학교 서버 k3d) 구성이 자원과 응답 시간을 얼마나 쓰는지 읽기 전용으로 잰다 (issue #172, 민서님 허락 2026-10-13).
# 아무것도 만들거나 바꾸지 않는다: docker stats·top, kubectl get·top·exec(읽기 요청)만 쓴다.
# 공용 서버라 다른 사람 컨테이너는 이름 없이 개수·합계만 적는다. GitHub Actions "운영 구성 측정"이 SSH로 실행한다.
set -uo pipefail
export PATH="$HOME/.local/bin:$PATH"
export KUBECONFIG="$HOME/devlog/kubeconfig.yaml"
NODE="k3d-devlog-server-0"
N="${N:-30}"
HANDLE="${HANDLE:-kim755030}"
PATHS=("/api/auth/me" "/api/members/${HANDLE}/posts" "/@${HANDLE}")

echo "## 1. 서버(호스트)"
echo "cpu=$(nproc) $(free -m | awk '/^Mem:/{printf "mem_total_mb=%d used_mb=%d available_mb=%d", $2, $3, $7}') load=$(cut -d' ' -f1-3 /proc/loadavg)"
echo "containers_total=$(docker ps -q | wc -l) containers_mine=$(docker ps -q --filter name=k3d-devlog | wc -l)"
echo "### 내 컨테이너 (docker stats)"
docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' $(docker ps --format '{{.Names}}' --filter name=k3d-devlog)
echo "### 다른 컨테이너 합계(공용 서버 부하, 이름은 적지 않음)"
others=$(docker ps --format '{{.Names}}' | grep -v '^k3d-devlog' || true)
if [ -n "$others" ]; then
  # shellcheck disable=SC2086
  docker stats --no-stream --format '{{.CPUPerc}}\t{{.MemUsage}}' $others \
    | awk -F'\t' '{c+=$1; split($2,a," "); v=a[1]; u=v; gsub(/[0-9.]/,"",u); gsub(/[A-Za-z]/,"",v); m=(u=="GiB")?v*1024:(u=="KiB")?v/1024:v; s+=m} END {printf "others_count=%d cpu_pct_sum=%.1f mem_mb_sum=%.0f\n", NR, c, s}'
fi
echo "### 노드 컨테이너 안 프로세스별 메모리(RSS MiB, 개수, 상위 20)"
docker top "$NODE" -eo rss,comm 2>/dev/null | awk 'NR>1 {m[$2]+=$1; n[$2]++} END {for (k in m) printf "%8.1f %3d %s\n", m[k]/1024, n[k], k}' | sort -rn | head -20
echo "### 이미지 크기"
docker images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep -E 'rancher/k3s' || true
docker exec "$NODE" crictl images 2>/dev/null | head -40

echo
echo "## 2. 클러스터"
kubectl get pods -A -o wide --no-headers 2>&1 | awk '{printf "%-22s %-52s %-8s restarts=%s\n", $1, $2, $4, $5}'
echo "### kubectl top pods (CPU, 메모리)"
kubectl top pods -A --no-headers 2>&1 | sort -k4 -h -r
kubectl top pods -A --no-headers 2>/dev/null | awk '{gsub(/m/,"",$3); gsub(/Mi/,"",$4); c+=$3; m+=$4; ns[$1]+=$4} END {printf "pods_total cpu_m=%d mem_mi=%d\n", c, m; for (k in ns) printf "  ns %-22s mem_mi=%d\n", k, ns[k]}'
kubectl top nodes --no-headers 2>&1
kubectl get hpa -A 2>&1

echo
echo "## 3. 요청 한 번이 거치는 단계별 응답 시간 (ms, ${N}번씩)"
APP=$(kubectl -n blog get pod -l app.kubernetes.io/name=blog-app --field-selector=status.phase=Running -o name | head -1)
ING=$(kubectl -n ingress-nginx get pod -l app.kubernetes.io/component=controller --field-selector=status.phase=Running -o name | head -1)
echo "app=$APP ingress=$ING"
for t in "blog $APP" "ingress-nginx $ING"; do
  set -- $t
  echo "tools $2: $(kubectl -n "$1" exec "$2" -- sh -c 'for x in curl wget; do command -v $x >/dev/null && printf "%s " $x; done' 2>/dev/null)"
done

# 그 자리에서 셸 하나로 N번 부르고 초 단위 값을 한 줄씩 낸다 ($1=N, $2=URL, $3=Host 머리)
LOOP='n=$1; url=$2; host=$3; i=0
while [ $i -lt $n ]; do
  if command -v curl >/dev/null 2>&1; then
    if [ -n "$host" ]; then curl -s -o /dev/null -H "Host: $host" -w "%{time_total}\n" "$url"; else curl -s -o /dev/null -w "%{time_total}\n" "$url"; fi
  else
    s=$(date +%s%N); if [ -n "$host" ]; then wget -q -O /dev/null --header "Host: $host" "$url"; else wget -q -O /dev/null "$url"; fi; e=$(date +%s%N)
    awk -v d=$((e-s)) "BEGIN {printf \"%.6f\\n\", d/1e9}"
  fi
  i=$((i+1))
done'
stats() { sort -n | awk '{a[NR]=$1*1000; s+=$1*1000} END {if (NR==0) {print "(값 없음)"; exit} i=int(NR*0.95); if (i<1) i=1; printf "p50=%7.1f p95=%7.1f avg=%7.1f n=%d\n", a[int((NR+1)/2)], a[i], s/NR, NR}'; }

for p in "${PATHS[@]}"; do
  echo "### $p"
  printf '  a) 앱만 (앱 파드 안 127.0.0.1:8080)         '; kubectl -n blog exec "$APP" -- sh -c "$LOOP" _ "$N" "http://127.0.0.1:8080$p" "" 2>/dev/null | stats
  printf '  b) 인그레스 파드 → 서비스(kube-proxy) → 앱  '; kubectl -n ingress-nginx exec "$ING" -- sh -c "$LOOP" _ "$N" "http://blog-app.blog.svc.cluster.local$p" "" 2>/dev/null | stats
  printf '  c) nginx 인그레스 → 서비스 → 앱             '; kubectl -n ingress-nginx exec "$ING" -- sh -c "$LOOP" _ "$N" "http://127.0.0.1$p" "devlog.life" 2>/dev/null | stats
  printf '  d) 서버에서 공개 주소 (Cloudflare 터널 왕복) '; bash -c "$LOOP" _ "$N" "https://devlog.life$p" "" 2>/dev/null | stats
done
IMG=$(curl -s "https://devlog.life/api/members/${HANDLE}/posts" | grep -o '/blog-images/[^"]*_thumb[^"]*' | head -1)
echo "### 사진 $IMG"
if [ -n "$IMG" ]; then
  printf '  c) nginx 인그레스 → MinIO                   '; kubectl -n ingress-nginx exec "$ING" -- sh -c "$LOOP" _ "$N" "http://127.0.0.1$IMG" "devlog.life" 2>/dev/null | stats
  printf '  d) 공개 주소 (Cloudflare 캐시)               '; bash -c "$LOOP" _ "$N" "https://devlog.life$IMG" "" 2>/dev/null | stats
fi
echo "### 서버 → Cloudflare 왕복 (캐시된 정적 파일)"
printf '  favicon.svg                                 '; bash -c "$LOOP" _ "$N" "https://devlog.life/favicon.svg" "" 2>/dev/null | stats

echo
echo "## 4. 서버 ↔ Cloudflare 구간 나눠 보기 (ms: DNS / TCP / TLS / 첫 바이트 / 전체, 10번 중앙값)"
W='%{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer} %{time_total}\n'
med() { sort -n -k5 | awk '{l[NR]=$0} END {split(l[int((NR+1)/2)], v, " "); printf "dns=%.0f tcp=%.0f tls=%.0f ttfb=%.0f total=%.0f\n", v[1]*1000, v[2]*1000, v[3]*1000, v[4]*1000, v[5]*1000}'; }
for u in https://devlog.life/favicon.svg https://devlog.life/api/auth/me https://www.cloudflare.com/cdn-cgi/trace https://www.google.com/generate_204; do
  printf '  %-44s ' "$u"; for i in $(seq 10); do curl -s -o /dev/null -w "$W" "$u"; done | med
done
echo "### 같은 연결을 다시 쓸 때(keep-alive, 10번째 요청)"
printf '  /api/auth/me x10 한 연결: '; curl -s -o /dev/null -w '%{time_total}\n' $(for i in $(seq 10); do echo https://devlog.life/api/auth/me; done) | sort -n | awk '{a[NR]=$1*1000} END {printf "p50=%.0f min=%.0f max=%.0f\n", a[5], a[1], a[NR]}'
echo "### 서버가 붙는 Cloudflare 위치"
for h in www.cloudflare.com devlog.life; do
  printf '  %-20s ip=%-16s ' "$h" "$(getent ahostsv4 $h | awk 'NR==1{print $1}')"; curl -s "https://$h/cdn-cgi/trace" | grep -E '^(colo|loc)=' | paste -sd' '
done
echo "### 경로(traceroute 대신 TCP 연결 시간 5번)"
for h in www.cloudflare.com devlog.life; do printf '  %-20s ' "$h"; for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{time_connect} ' "https://$h/cdn-cgi/trace"; done; echo; done
CF=$(kubectl -n blog get pod -l app.kubernetes.io/name=cloudflared -o name | head -1)
echo "### 터널(cloudflared) 연결 위치와 지연"
kubectl -n blog logs "$CF" --tail=400 2>/dev/null | grep -oE 'location=[a-z0-9]+' | sort | uniq -c
kubectl -n blog exec "$CF" -- sh -c 'wget -qO- http://127.0.0.1:2000/metrics 2>/dev/null || true' 2>/dev/null \
  | grep -E '^cloudflared_tunnel_(server_locations|ha_connections|request_errors|total_requests|concurrent_requests_per_tunnel)|^cloudflared_proxy_connect_latency|^quic_client_latest_rtt' | head -20
