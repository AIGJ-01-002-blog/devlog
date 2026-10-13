#!/usr/bin/env bash
# 지금 구성(k3d 안 k3s + ingress-nginx + 앱 2개 + PostgreSQL·Redis·MinIO·Ollama 임베딩) 측정.
# 운영(school-setup.sh, overlays/selfhosted)과 같은 k3s·ingress-nginx 버전, 같은 부품을 쓴다. cloudflared만 토큰이 없어 뺐다.
set -euo pipefail
cd "$(dirname "$0")"
. ./lib.sh
K3S_IMAGE="${K3S_IMAGE:-rancher/k3s:v1.33.4-k3s1}"
INGRESS_NGINX_VERSION="${INGRESS_NGINX_VERSION:-1.13.3}"
C=bench
log "## 지금 구성: k3d(Docker 안 k3s) + ingress-nginx + 앱 2개 + PostgreSQL·Redis·MinIO·Ollama"
t0=$(date +%s)
trap 'k3d cluster delete $C >/dev/null 2>&1 || true' EXIT
k3d cluster create $C --image "$K3S_IMAGE" --no-lb --servers 1 --agents 0 --k3s-arg "--disable=traefik@server:0" --wait --timeout 300s >/dev/null
k3d image import blog-app:bench -c $C >/dev/null
kubectl apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v${INGRESS_NGINX_VERSION}/deploy/static/provider/baremetal/deploy.yaml" >/dev/null
kubectl -n ingress-nginx rollout status deploy/ingress-nginx-controller --timeout=300s >/dev/null
# 운영처럼 앱 2개(HPA 최소 2), 이미지는 방금 만든 것을 쓴다
kustomize build ../k8s/overlays/local \
  | sed -e 's#image: blog-app$#image: blog-app:bench#' -e 's#image: ghcr.io/[^ ]*blog-app[^ ]*#image: blog-app:bench#' \
  | kubectl apply -f - >/dev/null
kubectl -n blog patch deploy blog-app --type=json -p '[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"IfNotPresent"}]' >/dev/null
kubectl -n blog patch hpa blog-app -p '{"spec":{"minReplicas":2}}' >/dev/null
kubectl -n blog scale deploy blog-app --replicas=2 >/dev/null
kubectl -n blog rollout status deploy/blog-app --timeout=600s >/dev/null || { kubectl -n blog get pods; kubectl -n blog describe deploy blog-app | tail -20; kubectl -n blog logs deploy/blog-app --tail=60 || true; exit 1; }
log "- 클러스터 만들기부터 앱 2개 준비까지: $(( $(date +%s) - t0 ))초"
# 임베딩 모델(bge-m3)을 다 받을 때까지 기다린다(최대 10분). 운영에서도 늘 떠 있는 파드다
# 준비되지 않으면 운영과 다른 구성이라 비교에 쓸 수 없으니 측정을 멈춘다
kubectl -n blog rollout status statefulset/ollama-embed --timeout=600s >/dev/null 2>&1 || { log "- Ollama가 10분 안에 준비되지 않아 측정을 멈춤"; kubectl -n blog get pods; exit 1; }

kubectl -n ingress-nginx run client --image=alpine:3.20 --restart=Never --command -- sleep 3600 >/dev/null
kubectl -n ingress-nginx wait --for=condition=Ready pod/client --timeout=120s >/dev/null
kubectl -n ingress-nginx exec client -- apk add -q --no-cache curl apache2-utils >/dev/null
APP_IP=$(kubectl -n blog get pod -l app.kubernetes.io/name=blog-app -o jsonpath='{.items[0].status.podIP}')
ING=http://ingress-nginx-controller.ingress-nginx.svc.cluster.local
HOST=blog.localtest.me
X() { kubectl -n ingress-nginx exec client -- sh -c "$@"; }
for p in "${PATHS[@]}"; do X "$LOOP" _ 100 "$ING$p" "$HOST" >/dev/null; X "$LOOP" _ 50 "http://$APP_IP:8080$p" >/dev/null; done
sleep 30
NODE=k3d-$C-server-0
log "- 쉬는 동안 메모리: 노드 컨테이너 전체 $(mem_sum $NODE) MiB, CPU $(cpu_sum $NODE)%"
log ""
log "| 파드(네임스페이스) | CPU | 메모리 |"; log "|---|---|---|"
kubectl top pods -A --no-headers | sort -k4 -h -r | awk '{printf "| %s (%s) | %s | %s |\n", $2, $1, $3, $4}' | tee -a "$OUT"
kubectl top pods -A --no-headers | awk '{gsub(/Mi/,"",$4); s+=$4} END {printf "- 파드 합계 %d MiB (나머지는 k3s·containerd·kubelet 자체)\n", s}' | tee -a "$OUT"
log ""
log "노드 컨테이너 안 프로세스별 메모리(RSS 상위 12)"
log '```'
docker top $NODE -eo pid,rss,comm | awk 'NR>1 {m[$3]+=$2; n[$3]++} END {for (k in m) printf "%8.1f MiB  %2d개  %s\n", m[k]/1024, n[k], k}' | sort -rn | head -12 | tee -a "$OUT" || true
log '```'
log ""
log "### 응답 시간 (ms, ${N}번, 한 번에 하나)"
log "| 경로 | 거치는 곳 | p50 | p95 | 평균 | 실패 |"; log "|---|---|---|---|---|---|"
for p in "${PATHS[@]}"; do
  printf '| `%s` | 파드 → 앱 파드 IP (파드 네트워크만) ' "$p" | tee -a "$OUT"; X "$LOOP" _ "$N" "http://$APP_IP:8080$p" | stats | tee -a "$OUT"
  printf '| `%s` | 파드 → 서비스(kube-proxy) → 앱 ' "$p" | tee -a "$OUT"; X "$LOOP" _ "$N" "http://blog-app.blog.svc.cluster.local$p" | stats | tee -a "$OUT"
  printf '| `%s` | 파드 → 인그레스 서비스 → nginx → 서비스 → 앱 (운영 경로, 터널 앞까지) ' "$p" | tee -a "$OUT"; X "$LOOP" _ "$N" "$ING$p" "$HOST" | stats | tee -a "$OUT"
done
log ""
log "### 부하 (동시 20개, 2000건, 운영 경로 = nginx 거침)"
log "| 경로 | 초당 처리 | p95 ms | 실패 |"; log "|---|---|---|---|"
for p in "/api/auth/me" "/api/posts"; do
  printf '| `%s` ' "$p" | tee -a "$OUT"; X "ab -q -k -c 20 -n 2000 -H 'Host: $HOST' $ING$p 2>/dev/null" | awk "$AB_PARSE" | tee -a "$OUT"
done
log "- 부하 직후 노드 컨테이너 메모리: $(mem_sum $NODE) MiB"
log ""
