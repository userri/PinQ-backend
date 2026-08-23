#!/usr/bin/env bash
# 서버에서 실행되는 전체 백업. 로컬에서 직접 돌리지 말 것.
#   ssh ... 'bash -s' < scripts/backup-all.sh
# 산출물: /home/ubuntu/pinq-backup-<날짜>.tar.gz (호출측이 scp 로 회수)
set -euo pipefail

APP_DIR=/home/ubuntu/pinq_backend
# 날짜는 호출측(로컬 KST)이 넘긴다. 서버는 UTC 라 자정 근처에서 하루가 어긋난다.
STAMP=${1:-$(date +%Y%m%d)}
WORK=$(mktemp -d)
OUT=/home/ubuntu/pinq-backup-${STAMP}.tar.gz
trap 'rm -rf "$WORK"' EXIT

cd "$APP_DIR"
set -a; . ./.env; set +a

# ── 1. DB ──────────────────────────────────────────────────────────
# --default-character-set=utf8mb4 없으면 한글이 ? 로 손실된다.
# --set-gtid-purged=OFF 없으면 복원 대상에서 GTID 충돌이 난다.
# 앱 계정에는 root 권한이 없으므로 접근 가능한 DB 만 이름으로 덤프한다.
DBS=$(docker exec mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" \
        -N -B -e "SHOW DATABASES" 2>/dev/null \
      | grep -Ev '^(information_schema|performance_schema|mysql|sys)$')

[ -n "$DBS" ] || { echo "FATAL: 덤프할 DB 가 없다"; exit 1; }
echo "DB: $(echo $DBS | tr '\n' ' ')"

docker exec mysql-container mysqldump \
    -u"$DB_USERNAME" -p"$DB_PASSWORD" \
    --default-character-set=utf8mb4 \
    --single-transaction --routines --triggers --events \
    --set-gtid-purged=OFF \
    --databases $DBS 2>/dev/null > "$WORK/db-${STAMP}.sql"

# 덤프가 비었거나 잘렸으면 여기서 죽는다 (조용히 빈 백업을 남기지 않는다)
grep -q "Dump completed" "$WORK/db-${STAMP}.sql" \
  || { echo "FATAL: 덤프가 완결되지 않았다"; exit 1; }
echo "DB 덤프: $(wc -c < "$WORK/db-${STAMP}.sql") bytes, 테이블 $(grep -c "^CREATE TABLE" "$WORK/db-${STAMP}.sql") 개"

# ── 2. 시크릿·설정 ─────────────────────────────────────────────────
cp "$APP_DIR/.env"            "$WORK/env"
cp "$APP_DIR/docker-compose.yml" "$WORK/" 2>/dev/null || true
crontab -l                    > "$WORK/crontab.txt" 2>/dev/null || echo "(none)" > "$WORK/crontab.txt"
docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}' > "$WORK/containers.txt"

# ── 3. 인증서 ──────────────────────────────────────────────────────
# 인증서는 호스트 /etc 가 아니라 $APP_DIR/certbot/conf 에 있고 root 소유다.
# sudo 는 비번을 물어 비대화 실행에서 막히므로, 이미 root 로 도는 certbot
# 컨테이너에게 tar 를 시켜 stdout 으로 받는다.
if docker exec pinq-certbot tar czf - -C /etc letsencrypt > "$WORK/letsencrypt.tar.gz" 2>/dev/null \
   && [ "$(stat -c %s "$WORK/letsencrypt.tar.gz")" -gt 1000 ]; then
  echo "인증서: $(wc -c < "$WORK/letsencrypt.tar.gz") bytes"
else
  echo "WARN: 인증서 백업 실패 — 재발급 가능한 자산이라 치명적이지 않다"
  rm -f "$WORK/letsencrypt.tar.gz"
fi

# nginx 설정은 레포에 있으므로(./nginx/*) 백업 대상이 아니다.

# ── 4. 묶기 ────────────────────────────────────────────────────────
tar czf "$OUT" -C "$WORK" .
echo "OK: $OUT ($(du -h "$OUT" | cut -f1))"
