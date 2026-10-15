#!/usr/bin/env bash
# 학교 서버 클러스터 상태를 읽기 전용으로 본다. 아무것도 바꾸지 않는다.
# 실행: Actions → 클러스터 상태 (서버에서 bash -s로 돈다. k3d 노드 안의 kubectl을 쓴다)
set -u
CLUSTER="${CLUSTER:-devlog}"
NODE="k3d-${CLUSTER}-server-0"
k() { docker exec "$NODE" kubectl "$@"; }

echo "== 노드"; k get nodes -o wide
echo "== blog 파드"; k -n blog get pods -o wide
echo "== 자원 사용(파드)"; k -n blog top pods 2>&1 || true
echo "== 자원 사용(노드)"; k top nodes 2>&1 || true
echo "== PVC"; k -n blog get pvc
echo "== 배포된 앱 이미지"; k -n blog get deploy blog-app -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'

echo "== ollama-embed 파드"
k -n blog get pod -l app.kubernetes.io/name=ollama-embed -o wide
k -n blog describe pod -l app.kubernetes.io/name=ollama-embed | sed -n '/^Conditions:/,/^Volumes:/p;/^Events:/,$p'
echo "== ollama-embed 로그 (끝 30줄)"
k -n blog logs statefulset/ollama-embed --tail=30 2>&1 || true
echo "== ollama-embed 안의 모델"
k -n blog exec statefulset/ollama-embed -- ollama list 2>&1 || true

# 임베딩 작업은 앱 파드 중 하나(잠금을 잡은 쪽)에서만 돌아서, 파드마다 따로 본다
echo "== 앱 로그의 의미 검색·임베딩 줄 (파드별)"
for pod in $(k -n blog get pods -l app.kubernetes.io/name=blog-app -o name); do
  echo "-- $pod"
  k -n blog logs "$pod" --all-containers --tail=5000 2>&1 | grep -E "의미 검색|임베딩|[Ee]mbed" | tail -10 || true
done
# 서버가 500을 낸 원인을 찾을 때 본다. 토큰처럼 보이는 값(dvl_…)은 가린다
echo "== 앱 로그의 처리하지 못한 오류 (파드별, 최근 3건)"
for pod in $(k -n blog get pods -l app.kubernetes.io/name=blog-app -o name); do
  echo "-- $pod"
  k -n blog logs "$pod" --all-containers --tail=20000 2>&1 \
    | grep -A12 "처리하지 못한 오류" | grep -vE "^\s+at (org\.springframework|org\.apache|jakarta|java\.base|io\.micrometer)" \
    | sed -E 's/dvl_[A-Za-z0-9_-]+/dvl_***/g' | tail -45 || true
done
# 조회문은 표준 입력으로 넘긴다(따옴표가 겹치지 않게). 공개 조건은 PostAccessPolicy.PUBLIC_LIST_CONDITION과 같다
psql_read() {
  docker exec -i "$NODE" kubectl -n blog exec -i statefulset/postgres -- \
    sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -v ON_ERROR_STOP=1 -F " | "' 2>&1
}
echo "== 공개 글 수 (읽기 전용 조회)"
psql_read <<'SQL' || true
SELECT count(*) FROM post p JOIN member m ON m.id = p.author_id
WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL AND p.hidden_at IS NULL AND m.withdrawn_at IS NULL;
SQL
echo "== 모델별 임베딩 수: 모델 | 공개 글 중 최신 | 전체 (읽기 전용 조회)"
psql_read <<'SQL' || echo "post_embedding 표를 읽지 못했습니다(pgvector가 없거나 아직 만들어지지 않음)"
SELECT e.model,
       count(*) FILTER (WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL
                          AND p.hidden_at IS NULL AND m.withdrawn_at IS NULL AND e.source_version = p.edit_version),
       count(*)
FROM post_embedding e JOIN post p ON p.id = e.post_id JOIN member m ON m.id = p.author_id
GROUP BY e.model;
SQL
