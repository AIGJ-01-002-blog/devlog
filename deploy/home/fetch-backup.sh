#!/usr/bin/env bash
# 집 컴퓨터(맥·리눅스)에서 학교 서버 백업을 한 번에 받는다 (#241).
# 학교 서버에 SSH로 들어가 backup.sh를 돌리고, 만든 폴더를 내려받은 뒤 서버 쪽 사본을 지운다.
#
#   deploy/home/fetch-backup.sh <ssh 주소(아이디@서버)> [ssh 포트] [받을 폴더]
#   예) deploy/home/fetch-backup.sh student@school.example 2222 /Volumes/외장하드/devlog
#
# SSH 비밀번호는 ssh가 직접 묻는다(이 스크립트는 받지도 저장하지도 않는다). 비밀번호를 여러 번 묻는다.
set -euo pipefail

TARGET="${1:?ssh 주소(아이디@서버)를 주세요}"
PORT="${2:-22}"
DEST="${3:-.}"
NAME="devlog-backup-$(date +%Y%m%d-%H%M)"
HERE="$(cd "$(dirname "$0")" && pwd)"
REMOTE="/tmp/$NAME"

mkdir -p "$DEST"
echo "== 학교 서버에서 백업 만드는 중 (DB와 파일 저장소)"
ssh -p "$PORT" "$TARGET" "KUBECONFIG=\${KUBECONFIG:-\$HOME/devlog/kubeconfig.yaml} bash -s -- '$REMOTE'" < "$HERE/backup.sh"

echo "== 내려받는 중: $DEST/$NAME"
if command -v rsync >/dev/null; then
  rsync -a --partial --info=progress2 -e "ssh -p $PORT" "$TARGET:$REMOTE/" "$DEST/$NAME/" 2>/dev/null \
    || rsync -a --partial -e "ssh -p $PORT" "$TARGET:$REMOTE/" "$DEST/$NAME/"
else
  scp -r -P "$PORT" "$TARGET:$REMOTE" "$DEST/$NAME"
fi

echo "== 받은 파일 확인"
if command -v sha256sum >/dev/null; then got="$(sha256sum "$DEST/$NAME/db.dump" | cut -d' ' -f1)"; else got="$(shasum -a 256 "$DEST/$NAME/db.dump" | cut -d' ' -f1)"; fi
grep -q "$got" "$DEST/$NAME/MANIFEST.txt" || { echo "db.dump가 서버 것과 달라요. 다시 받아 주세요." >&2; exit 1; }
want="$(sed -n 's/^media: 파일 \([0-9]*\)개.*/\1/p' "$DEST/$NAME/MANIFEST.txt")"
have="$(find "$DEST/$NAME/media" -type f | wc -l | tr -d ' ')"
[ "$want" = "$have" ] || { echo "사진·파일 수가 달라요 (서버 $want, 받은 것 $have). 다시 받아 주세요." >&2; exit 1; }

ssh -p "$PORT" "$TARGET" "rm -rf '$REMOTE'"
echo "== 끝: $DEST/$NAME (DB와 파일 ${have}개). 이 폴더째 외장하드에 두면 돼요."
