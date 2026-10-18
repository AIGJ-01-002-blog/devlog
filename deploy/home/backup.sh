#!/usr/bin/env bash
# devlog 전체 백업 (#241): DB(pg_dump)와 파일 저장소(사진·첨부·쇼츠 영상)를 폴더 하나로 받는다.
# 클러스터에 kubectl이 닿는 곳(학교 서버)에서 실행한다. 집 컴퓨터에서는 fetch-backup.sh가 SSH로 이 스크립트를 대신 돌리고 폴더를 내려받는다.
#
#   KUBECONFIG=~/devlog/kubeconfig.yaml deploy/home/backup.sh [저장할 폴더]
#
# 접속 정보는 클러스터의 Secret에서 그때그때 읽고, 파일로 남기지 않는다(mc 설정은 임시 폴더에 두었다가 끝나면 지운다).
# 만들어지는 폴더: db.dump(pg_restore 형식), media/(저장소 키 그대로), MANIFEST.txt(크기·개수·SHA-256)
set -euo pipefail
# 덤프에는 회원 이메일·비밀번호 해시·토큰이 들어 있다. 여럿이 쓰는 서버에서 다른 계정이 읽지 못하게 만드는 파일·폴더를 나만 읽게 한다
umask 077

NS="${NAMESPACE:-blog}"
OUT="${1:-./devlog-backup-$(date +%Y%m%d-%H%M)}"
PORT="${MINIO_LOCAL_PORT:-19000}"

say() { printf '\n== %s\n' "$*"; }
die() { printf '실패: %s\n' "$*" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl이 없어요. 학교 서버에서 실행하거나 KUBECONFIG를 맞춰 주세요."
kubectl -n "$NS" get statefulset postgres >/dev/null 2>&1 || die "네임스페이스 $NS에서 postgres를 찾지 못했어요. KUBECONFIG를 확인해 주세요."
mkdir -p "$OUT/media"
# 예전에 만든 폴더를 다시 쓰면 umask가 닿지 않으니 들어 있던 것까지 나만 읽게 한다
chmod -R go-rwx "$OUT"
OUT="$(cd "$OUT" && pwd)"

WORK="$(mktemp -d)"
PF=""
cleanup() {
  [ -n "$PF" ] && kill "$PF" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

say "1/3 DB 받는 중 (pg_dump)"
kubectl -n "$NS" exec statefulset/postgres -c postgres -- \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges' > "$OUT/db.dump.part"
[ -s "$OUT/db.dump.part" ] || die "DB 백업이 비어 있어요."
# 중간에 끊겨 잘린 덤프를 걸러 내려고, 받은 파일을 서버의 pg_restore가 데이터까지 끝까지 읽을 수 있는지 본다 (--list는 목차만 읽어 잘린 데이터를 놓친다)
kubectl -n "$NS" exec -i statefulset/postgres -c postgres -- pg_restore -f /dev/null < "$OUT/db.dump.part" \
  || die "DB 백업이 온전하지 않아요. 다시 실행해 주세요."
mv "$OUT/db.dump.part" "$OUT/db.dump"

say "2/3 파일 저장소 받는 중 (MinIO → media/)"
# kustomize가 이름 뒤에 해시를 붙이므로 앱 Deployment가 쓰는 실제 이름을 읽는다
envfrom() { kubectl -n "$NS" get deployment blog-app -o "jsonpath={.spec.template.spec.containers[0].envFrom[*].$1.name}" | tr ' ' '\n' | grep "^blog-app-" | head -1 || true; }
CONFIG="$(envfrom configMapRef)"
SECRET="$(envfrom secretRef)"
[ -n "$SECRET" ] || die "앱 Secret 이름을 찾지 못했어요."
config() { kubectl -n "$NS" get configmap "$CONFIG" -o "jsonpath={.data.$1}" 2>/dev/null || true; }
secret() { kubectl -n "$NS" get secret "$SECRET" -o "jsonpath={.data.$1}" | base64 --decode; }
BUCKET="$(config S3_BUCKET)"
BUCKET="${BUCKET:-blog-images}"

# 같은 이름의 Midnight Commander와 헷갈리지 않게 MinIO 클라이언트인지 확인한다.
# 없으면 클러스터가 버킷을 만들 때 쓰는 mc 이미지(ensure-bucket)를 docker로 돌린다.
# MinIO가 공식 배포본(dl.min.io) 내려받기를 닫아서(410) 내려받기는 마지막 수단으로만 둔다.
MC="$(command -v mc || true)"
if [ -n "$MC" ] && ! "$MC" --version 2>/dev/null | grep -qi minio; then MC=""; fi
MC_IMAGE="${MC_IMAGE:-$(kubectl -n "$NS" get deployment blog-app -o 'jsonpath={.spec.template.spec.initContainers[?(@.name=="ensure-bucket")].image}' 2>/dev/null || true)}"
MC_IMAGE="${MC_IMAGE:-pgsty/mc:RELEASE.2026-09-16T00-00-00Z}"
if [ -z "$MC" ] && ! { command -v docker >/dev/null && docker info >/dev/null 2>&1; }; then
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; arm64|aarch64) arch=arm64 ;; *) die "지원하지 않는 CPU: $(uname -m)" ;; esac
  MC="$WORK/mc"
  curl -fsSL -o "$MC" "https://dl.min.io/client/mc/release/${os}-${arch}/mc" \
    || die "MinIO 클라이언트(mc)가 없고 docker도 쓸 수 없어요. 학교 서버에 mc를 설치하거나 docker 권한을 확인해 주세요."
  chmod +x "$MC"
fi

kubectl -n "$NS" port-forward svc/minio "$PORT:9000" >"$WORK/pf.log" 2>&1 &
PF=$!
for _ in $(seq 1 30); do
  curl -fs "http://127.0.0.1:$PORT/minio/health/ready" >/dev/null 2>&1 && break
  sleep 1
done
curl -fs "http://127.0.0.1:$PORT/minio/health/ready" >/dev/null || die "MinIO에 연결하지 못했어요: $(cat "$WORK/pf.log")"

# 열쇠는 명령줄에 두지 않는다(여럿이 쓰는 서버에서 ps로 보인다. docker 안에서 돈 프로세스도 서버의 ps에 보인다).
# mc alias set은 열쇠를 인자로 받으므로 쓰지 않고, mc가 읽는 MC_HOST_<별칭> 환경 변수로만 넘긴다.
# 환경 변수는 같은 계정과 root만 읽을 수 있다(/proc/<pid>/environ)
mkdir -p "$WORK/mc-config"
# 주소 안의 열쇠에 @ : / 같은 글자가 있어도 깨지지 않게 퍼센트 인코딩한다(mc가 풀어서 읽는다)
urlenc() {
  local s="$1" out="" c
  while [ -n "$s" ]; do
    c="${s:0:1}"; s="${s:1}"
    case "$c" in
      [A-Za-z0-9._~-]) out+="$c" ;;
      *) out+="$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}
# 값을 셸 문법으로 다시 읽지 않게 변수에 바로 담고, 파일은 docker --env-file용으로만 쓴다
MC_HOST_devlog="http://$(LC_ALL=C urlenc "$(secret S3_ACCESS_KEY)"):$(LC_ALL=C urlenc "$(secret S3_SECRET_KEY)")@127.0.0.1:$PORT"
printf 'MC_HOST_devlog=%s\n' "$MC_HOST_devlog" > "$WORK/mc.env"
if [ -n "$MC" ]; then
  ( export MC_CONFIG_DIR="$WORK/mc-config" MC_HOST_devlog
    "$MC" mirror --overwrite --quiet "devlog/$BUCKET" "$OUT/media" )
else
  echo "mc 이미지로 받는 중: $MC_IMAGE"
  # 포트 전달이 127.0.0.1에 열려 있어 host 네트워크로 붙고, 받은 파일이 내 것이 되게 내 사용자 번호로 돌린다
  docker run --rm --network host --user "$(id -u):$(id -g)" --env-file "$WORK/mc.env" \
    -e MC_CONFIG_DIR=/mc -e BUCKET="$BUCKET" \
    -v "$WORK/mc-config:/mc" -v "$OUT/media:/out" --entrypoint sh "$MC_IMAGE" -c \
    'mc mirror --overwrite --quiet "devlog/$BUCKET" /out'
fi
unset MC_HOST_devlog
rm -f "$WORK/mc.env"

say "3/3 확인 목록 만드는 중"
if command -v sha256sum >/dev/null; then sum() { sha256sum "$1" | cut -d' ' -f1; }; else sum() { shasum -a 256 "$1" | cut -d' ' -f1; }; fi
files="$(find "$OUT/media" -type f | wc -l | tr -d ' ')"
{
  echo "devlog 백업"
  echo "만든 때: $(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "사이트: $(config SITE_BASE_URL)"
  echo "사진 공개 주소: $(config IMAGE_PUBLIC_BASE_URL)"
  echo "db.dump: $(wc -c < "$OUT/db.dump" | tr -d ' ') bytes, sha256 $(sum "$OUT/db.dump")"
  echo "media: 파일 ${files}개, $(du -sh "$OUT/media" | cut -f1)"
} > "$OUT/MANIFEST.txt"
cat "$OUT/MANIFEST.txt"
say "끝: $OUT"
