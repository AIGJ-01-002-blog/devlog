#!/usr/bin/env bash
# 집 PC가 매일 받아 가는 백업 (#356). 학교 서버에서 SSH 강제 명령(authorized_keys의 command=)으로만 돈다.
# 집 PC의 백업 전용 키로 접속하면 이 스크립트만 실행되고, backup.sh로 만든 폴더를 tar로 표준 출력에 흘린 뒤 서버 사본을 지운다.
# 진행 기록은 표준 오류로 보낸다(표준 출력은 tar 내용만). 설치는 setup-backup.ps1이 한다.
set -euo pipefail
umask 077

HERE="$(cd "$(dirname "$0")" && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/devlog/kubeconfig.yaml}"

# 켤 때와 정해진 시각이 겹쳐 두 번 들어와도 하나만 돈다 (MinIO 임시 포트가 겹치지 않게)
exec 9>"$HERE/.lock"
flock -n 9 || { echo "다른 백업이 이미 돌고 있어요. 잠시 후 다시 받아 주세요." >&2; exit 75; }

WORK="$(mktemp -d "$HERE/run.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

bash "$HERE/backup.sh" "$WORK/devlog-backup" >&2
tar -C "$WORK/devlog-backup" -cf - .
