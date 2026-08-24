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

# 노트북에서 도는 작업이라 회선이 흔들리거나 맥이 조는 일이 실제로 있다
# (2026-08-24 13:00 자동 실행이 회수 도중 Operation timed out 으로 끊겼다).
# ServerAlive* 로 죽은 연결을 빨리 포기하게 하고, 아래 retry 로 다시 시도한다.
SSH_OPTS=(-i "$PINQ_SSH_KEY" -o ConnectTimeout=15 -o ServerAliveInterval=10 -o ServerAliveCountMax=3)

# 실패를 조용히 넘기지 않는다 — 알림이 없으면 몇 주 뒤에나 알게 된다.
notify() {
  /usr/bin/osascript -e "display notification \"$1\" with title \"PinQ 백업 실패\"" 2>/dev/null || true
}
fail() { echo "FAIL: $1"; notify "$1"; exit 1; }

# 3회까지 재시도. 간격을 벌리는 건 회선이 잠깐 끊긴 경우를 노린 것이다.
retry() {
  local what=$1; shift
  local n
  for n in 1 2 3; do
    if "$@"; then return 0; fi
    echo "  ${what} 실패 (${n}/3)"
    [ "$n" -lt 3 ] && sleep $((n * 20))
  done
  fail "${what} 3회 실패"
}

# 검증 단계 등 retry 밖에서 죽는 경우도 알림이 뜨게 한다.
trap 'fail "예기치 못한 오류 (line $LINENO)"' ERR

echo "== 서버에서 백업 생성 =="
# stdin 으로 넘기지 않는다: 스크립트 안의 docker exec 가 stdin 을 먹어 본문이 잘린다.
retry "스크립트 전송" scp "${SSH_OPTS[@]}" "$HERE/backup-all.sh" "$PINQ_SSH_HOST:/tmp/pinq-backup-all.sh"
retry "백업 생성" ssh "${SSH_OPTS[@]}" "$PINQ_SSH_HOST" \
  "bash /tmp/pinq-backup-all.sh ${STAMP} && rm -f /tmp/pinq-backup-all.sh"

echo "== 회수 =="
retry "회수" scp "${SSH_OPTS[@]}" "$PINQ_SSH_HOST:/home/ubuntu/pinq-backup-${STAMP}.tar.gz" "$DEST/"
# 회수에 성공한 뒤에만 서버 사본을 지운다. 순서를 바꾸면 실패한 날 원본까지 잃는다.
# 옛 실패로 남은 사본도 같이 치운다(디스크 843MB VM).
ssh "${SSH_OPTS[@]}" "$PINQ_SSH_HOST" "rm -f /home/ubuntu/pinq-backup-*.tar.gz" || true

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
