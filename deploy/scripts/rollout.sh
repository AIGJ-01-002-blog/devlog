#!/usr/bin/env bash
# 새 이미지로 교체하고, 정해진 시간 안에 모든 파드가 준비(readiness = /actuator/health/readiness UP)되지 않으면
# 직전 버전으로 되돌린다. 민서님 이전 프로젝트 deploy.sh(헬스 체크 + 자동 롤백)를 쿠버네티스 방식으로 옮긴 것.
#
# 필요한 환경 변수: IMAGE(이미지 이름), GITHUB_SHA(태그), KUBECONFIG
# 선택: OVERLAY(기본 selfhosted, 학교 서버를 쓰면 nhn), ROLLOUT_TIMEOUT(초, 기본 300), ROLLBACK_TEST=true(없는 태그로 배포해 롤백을 시험), NAMESPACE(기본 blog)
set -euo pipefail

ns="${NAMESPACE:-blog}"
timeout="${ROLLOUT_TIMEOUT:-300}"
tag="${GITHUB_SHA:?GITHUB_SHA가 필요합니다}"
if [ "${ROLLBACK_TEST:-false}" = "true" ]; then
  tag="rollback-test-does-not-exist"
  echo "롤백 시험: 존재하지 않는 태그 ${tag}로 배포합니다."
fi

cd "$(dirname "$0")/../k8s/overlays/${OVERLAY:-selfhosted}"

before=$(kubectl -n "$ns" get deploy/blog-app -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}' 2>/dev/null || true)
# 뒤의 공개 주소 연기 시험(smoke.sh)이 실패하면 이 버전으로 되돌린다
[ -n "${GITHUB_ENV:-}" ] && echo "PREV_REVISION=${before}" >> "$GITHUB_ENV"

# base가 이미지 이름을 ghcr.io/aigj-01-002-blog/blog-app 으로 바꾼 뒤라 그 이름으로 맞춘다
kustomize edit set image "ghcr.io/aigj-01-002-blog/blog-app=${IMAGE:?IMAGE가 필요합니다}:${tag}"
kustomize build . | kubectl apply --server-side --force-conflicts -f -

if kubectl -n "$ns" rollout status deploy/blog-app --timeout="${timeout}s"; then
  echo "배포 완료: ${IMAGE}:${tag}"
  exit 0
fi

echo "::error::새 버전이 ${timeout}초 안에 준비되지 않았습니다. 직전 버전으로 되돌립니다."
kubectl -n "$ns" get pods -l app.kubernetes.io/name=blog-app -o wide || true
if [ -n "$before" ]; then
  kubectl -n "$ns" rollout undo deploy/blog-app --to-revision="$before"
  kubectl -n "$ns" rollout status deploy/blog-app --timeout="${timeout}s"
  echo "직전 버전(revision ${before})으로 되돌렸습니다."
else
  echo "::warning::첫 배포라 되돌릴 버전이 없습니다."
fi
exit 1
