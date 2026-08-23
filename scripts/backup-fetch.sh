#!/usr/bin/env bash
# 로컬에서 실행. 서버 백업을 만들고 회수한다.
#   ./scripts/backup-fetch.sh
# 접속정보는 ~/.pinq-ops.env 의 PINQ_SSH_KEY / PINQ_SSH_HOST 에서만 읽는다 (레포에 하드코딩 금지).
set -euo pipefail

set -a; . "$HOME/.pinq-ops.env"; set +a
: "${PINQ_SSH_KEY:?~/.pinq-ops.env 에 PINQ_SSH_KEY 가 없다}"
: "${PINQ_SSH_HOST:?~/.pinq-ops.env 에 PINQ_SSH_HOST 가 없다}"

STAMP=$(date +%Y%m%d)
DEST="$HOME/backups/pinq/$STAMP"          # 레포 밖. 시크릿이 들어 있다.
mkdir -p "$DEST"; chmod 700 "$HOME/backups/pinq" "$DEST"

HERE=$(cd "$(dirname "$0")" && pwd)

echo "== 서버에서 백업 생성 =="
# stdin 으로 넘기지 않는다: 스크립트 안의 docker exec 가 stdin 을 먹어 본문이 잘린다.
scp -i "$PINQ_SSH_KEY" "$HERE/backup-all.sh" "$PINQ_SSH_HOST:/tmp/pinq-backup-all.sh"
ssh -i "$PINQ_SSH_KEY" "$PINQ_SSH_HOST" "bash /tmp/pinq-backup-all.sh ${STAMP} && rm -f /tmp/pinq-backup-all.sh"

echo "== 회수 =="
scp -i "$PINQ_SSH_KEY" "$PINQ_SSH_HOST:/home/ubuntu/pinq-backup-${STAMP}.tar.gz" "$DEST/"
ssh -i "$PINQ_SSH_KEY" "$PINQ_SSH_HOST" "rm -f /home/ubuntu/pinq-backup-${STAMP}.tar.gz"

echo "== 검증 (복원 가능한지 실제로 열어본다) =="
tar tzf "$DEST/pinq-backup-${STAMP}.tar.gz"
tar xzOf "$DEST/pinq-backup-${STAMP}.tar.gz" "./db-${STAMP}.sql" \
  | grep -c "INSERT INTO" | xargs echo "INSERT 문 수:"

chmod 600 "$DEST"/*
echo
echo "OK -> $DEST"
echo "⚠️ .env·인증서가 들어 있다. 레포·클라우드 공유 폴더에 올리지 말 것."
