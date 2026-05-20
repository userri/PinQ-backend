#!/bin/bash
# ────────────────────────────────────────────────────────────────────────────
# Let's Encrypt 초기 인증서 발급 (최초 1회만 실행).
#
# 사전 준비:
#   1. .env 에 DOMAIN, CERT_EMAIL 설정
#   2. DOMAIN 의 A 레코드가 이 서버 IP 를 가리키고 있어야 함 (DuckDNS 등)
#   3. 80/443 포트가 외부에서 접근 가능해야 함 (보안그룹/방화벽)
#
# 사용법:
#   ./init-letsencrypt.sh                # 운영 인증서 발급
#   STAGING=1 ./init-letsencrypt.sh      # 테스트 (Let's Encrypt staging, 신뢰 X)
#   FORCE_REISSUE=1 ./init-letsencrypt.sh # 기존 certbot 인증서가 있어도 재발급
#
# 동작:
#   1. 권장 TLS 설정 파일 다운로드
#   2. 더미 self-signed 인증서 생성 (nginx 가 일단 뜨도록)
#   3. nginx 시동
#   4. 더미 인증서 삭제
#   5. certbot 으로 실제 인증서 발급 (HTTP-01 webroot)
#   6. nginx reload
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

# .env 로드
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

: "${DOMAIN:?DOMAIN 이 .env 에 설정돼야 합니다 (예: finq.duckdns.org)}"
: "${CERT_EMAIL:?CERT_EMAIL 이 .env 에 설정돼야 합니다 (인증서 만료 알림 수신용)}"

STAGING="${STAGING:-0}"
FORCE_REISSUE="${FORCE_REISSUE:-0}"
DATA_PATH="./certbot"
RSA_KEY_SIZE=4096

if [ -e "$DATA_PATH/conf/renewal/$DOMAIN.conf" ] && [ "$FORCE_REISSUE" != "1" ]; then
  echo "이미 $DOMAIN 인증서 갱신 설정이 있습니다."
  echo "초기 발급을 건너뜁니다. 강제 재발급이 필요하면 FORCE_REISSUE=1 로 실행하세요."
  exit 0
fi

mkdir -p "$DATA_PATH/conf" "$DATA_PATH/www"

download_if_missing() {
  local url="$1"
  local target="$2"
  local tmp

  if [ -e "$target" ]; then
    return 0
  fi

  tmp="$(mktemp "${target}.XXXXXX")"
  if curl -fsSL "$url" -o "$tmp"; then
    mv "$tmp" "$target"
  else
    rm -f "$tmp"
    return 1
  fi
}

# ── 1) 권장 TLS 옵션/DH 파라미터 ───────────────────────────────────────────
if [ ! -e "$DATA_PATH/conf/options-ssl-nginx.conf" ] || [ ! -e "$DATA_PATH/conf/ssl-dhparams.pem" ]; then
  echo "▶ 권장 TLS 설정 다운로드..."
  download_if_missing \
    "https://raw.githubusercontent.com/certbot/certbot/master/certbot-nginx/certbot_nginx/_internal/tls_configs/options-ssl-nginx.conf" \
    "$DATA_PATH/conf/options-ssl-nginx.conf"
  download_if_missing \
    "https://raw.githubusercontent.com/certbot/certbot/master/certbot/certbot/ssl-dhparams.pem" \
    "$DATA_PATH/conf/ssl-dhparams.pem"
fi

# ── 2) 더미 인증서 생성 (nginx 가 일단 시동되도록) ─────────────────────────
echo "▶ 더미 인증서 생성: $DOMAIN ..."
DUMMY_PATH="/etc/letsencrypt/live/$DOMAIN"
mkdir -p "$DATA_PATH/conf/live/$DOMAIN"

docker compose run --rm --entrypoint "\
  openssl req -x509 -nodes -newkey rsa:${RSA_KEY_SIZE} -days 1 \
    -keyout '$DUMMY_PATH/privkey.pem' \
    -out '$DUMMY_PATH/fullchain.pem' \
    -subj '/CN=localhost'" certbot

# ── 3) nginx 시동 ─────────────────────────────────────────────────────────
echo "▶ nginx 시동..."
docker compose up -d nginx

# nginx 가 80 포트 listen 시작할 시간
sleep 3

# ── 4) 더미 인증서 삭제 ───────────────────────────────────────────────────
echo "▶ 더미 인증서 삭제..."
docker compose run --rm --entrypoint "\
  rm -Rf /etc/letsencrypt/live/$DOMAIN && \
  rm -Rf /etc/letsencrypt/archive/$DOMAIN && \
  rm -Rf /etc/letsencrypt/renewal/$DOMAIN.conf" certbot

# ── 5) 실제 인증서 요청 ───────────────────────────────────────────────────
echo "▶ Let's Encrypt 인증서 요청..."
STAGING_ARG=""
if [ "$STAGING" = "1" ]; then
  STAGING_ARG="--staging"
  echo "  (STAGING 모드 — 발급은 되지만 브라우저가 신뢰하지 않음)"
fi

docker compose run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    $STAGING_ARG \
    --email $CERT_EMAIL \
    -d $DOMAIN \
    --rsa-key-size $RSA_KEY_SIZE \
    --agree-tos \
    --non-interactive" certbot

# ── 6) nginx reload ───────────────────────────────────────────────────────
echo "▶ nginx reload..."
docker compose exec nginx nginx -s reload

echo ""
echo "✅ HTTPS 설정 완료!"
echo "   → https://$DOMAIN  에서 접속 가능"
echo ""
echo "  자동 갱신은 certbot 컨테이너가 12시간마다 시도합니다 (만료 30일 이내일 때만 실제 갱신)."
