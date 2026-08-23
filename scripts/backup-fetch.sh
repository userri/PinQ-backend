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

# ── 보관 정책 ──────────────────────────────────────────────────────
# 최근 30일은 전부, 그 이전은 매월 1일자만 남긴다.
# 용량 때문이 아니라(하루 300KB), 폴더가 수백 개가 되면 "어느 게 온전한지"를
# 사람이 못 고르게 되는 걸 막으려는 것이다.
# grep 을 파이프에 쓰지 않는다 — 지울 게 없는 날 exit 1 을 내고 pipefail 이 스크립트를 죽인다.
find "$HOME/backups/pinq" -mindepth 1 -maxdepth 1 -type d -name '20*' \
  | sort -r | tail -n +31 \
  | while read -r old; do
      case "$(basename "$old")" in
        *01) continue ;;                       # 매월 1일자는 남긴다
      esac
      echo "정리: $(basename "$old")"
      /bin/rm -rf "$old"
    done

echo
echo "OK -> $DEST"
echo "보관: $(find "$HOME/backups/pinq" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')개 / $(du -sh "$HOME/backups/pinq" | cut -f1)"
echo "⚠️ .env·인증서가 들어 있다. 레포·클라우드 공유 폴더에 올리지 말 것."
