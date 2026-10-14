#!/usr/bin/env bash
# 백업 폴더로 devlog를 집 컴퓨터(맥·리눅스)에 되살린다 (#241). Windows는 restore.ps1.
#
#   deploy/home/restore.sh <백업 폴더> [--force]
#
# 1) 백업 폴더 위치를 deploy/home/.env에 적고  2) PostgreSQL·Redis를 띄워 db.dump를 넣고
# 3) 옛 주소(https://devlog.life)를 이 컴퓨터 주소로 바꾸고  4) 주인 계정 비밀번호 로그인을 만들고  5) 앱을 빌드해 띄운다.
# 이미 되살린 DB가 있으면 멈춘다. 지우고 다시 넣으려면 --force.
set -euo pipefail

BACKUP="${1:?백업 폴더를 주세요 (db.dump와 media/가 있는 폴더)}"
FORCE="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SITE="${SITE_BASE_URL:-http://localhost:8080}"
OLD_SITE="${OLD_SITE_URL:-https://devlog.life}"
OLD_MEDIA="${OLD_IMAGE_BASE_URL:-https://devlog.life/blog-images}"

die() { printf '실패: %s\n' "$*" >&2; exit 1; }
[ -f "$BACKUP/db.dump" ] && [ -d "$BACKUP/media" ] || die "$BACKUP 안에 db.dump와 media/가 없어요."
BACKUP="$(cd "$BACKUP" && pwd)"
command -v docker >/dev/null || die "Docker Desktop을 먼저 설치해 주세요."
dc() { docker compose -f "$HERE/compose.yaml" --env-file "$HERE/.env" "$@"; }
# 묻는 말에 답할 표준 입력을 docker가 먹지 않게, SQL 파일을 넘길 때만 표준 입력을 잇는다
psql() { dc exec -T postgres psql -U blog -d blog -q "$@"; }
sql() { psql "$@" < /dev/null; }

cat > "$HERE/.env" <<ENV
DEVLOG_MEDIA=$BACKUP/media
SITE_BASE_URL=$SITE
OLD_IMAGE_BASE_URL=$OLD_MEDIA
ENV

echo "== 1/5 DB 띄우는 중"
dc up -d --wait postgres redis < /dev/null

tables="$(sql -tA -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
if [ "$tables" != "0" ]; then
  [ "$FORCE" = "--force" ] || die "이미 되살린 DB가 있어요. 지우고 다시 넣으려면: $0 '$BACKUP' --force"
  echo "   있던 DB를 지우고 다시 넣어요"
  sql -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
fi

echo "== 2/5 db.dump 넣는 중"
dc cp "$BACKUP/db.dump" postgres:/tmp/db.dump < /dev/null
dc exec -T postgres pg_restore -U blog -d blog --no-owner --no-privileges --exit-on-error /tmp/db.dump < /dev/null
dc exec -T postgres rm -f /tmp/db.dump < /dev/null

echo "== 3/5 옛 주소를 $SITE 로 바꾸는 중"
psql -v old_media="$OLD_MEDIA" -v new_media="$SITE/media" -v old_site="$OLD_SITE" -v new_site="$SITE" < "$HERE/rebase-urls.sql"

echo "== 4/5 주인 계정 비밀번호 로그인 만들기 (건너뛰려면 주소에서 그냥 Enter)"
handle=""
read -r -p "   내 블로그 주소(@ 뒤, 예: kim755030): " handle || true
if [ -n "$handle" ]; then
  read -r -p "   로그인할 이메일: " email
  while :; do
    read -r -s -p "   새 비밀번호(8자 이상): " pw; echo
    read -r -s -p "   한 번 더: " pw2; echo
    [ "$pw" = "$pw2" ] && [ "${#pw}" -ge 8 ] && break
    echo "   두 비밀번호가 다르거나 8자보다 짧아요."
  done
  # 비밀번호는 이 컴퓨터의 명령줄에 남기지 않고 환경 변수로만 컨테이너에 넘겨 bcrypt 해시로 바꾼다
  hash="$(DEVLOG_PW="$pw" docker run --rm -e DEVLOG_PW httpd:2.4-alpine sh -c 'htpasswd -nbBC 10 x "$DEVLOG_PW"' < /dev/null | cut -d: -f2 | tr -d '\r\n')"
  unset pw pw2
  case "$hash" in '$2'[aby]'$'*) ;; *) die "비밀번호 해시를 만들지 못했어요. Docker가 httpd 이미지를 받을 수 있는지 확인해 주세요." ;; esac
  psql -v handle="$handle" -v email="$email" -v hash="$hash" < "$HERE/owner-login.sql"
fi

echo "== 5/5 앱 빌드하고 띄우는 중 (처음에는 몇 분 걸려요)"
dc up -d --build --wait app < /dev/null
echo "== 끝: $SITE 에서 열어 보세요. 끄기: docker compose -f '$HERE/compose.yaml' down (데이터는 남아요)"
