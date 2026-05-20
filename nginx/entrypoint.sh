#!/bin/sh
# nginx 컨테이너 진입점.
# 1) DOMAIN 환경변수로 템플릿 치환. DOMAIN 이 없으면 legacy HTTP 설정으로 실행.
# 2) 6시간마다 nginx reload (certbot 이 갱신한 인증서 반영)
# 3) nginx 포그라운드 실행
set -e

if [ -z "${DOMAIN:-}" ]; then
  echo "WARN: DOMAIN 환경변수가 없어 legacy HTTP nginx 설정으로 실행합니다." >&2

  if [ ! -f /etc/nginx/nginx.conf.legacy ]; then
    echo "ERROR: /etc/nginx/nginx.conf.legacy 가 없습니다." >&2
    exit 1
  fi

  cp /etc/nginx/nginx.conf.legacy /etc/nginx/nginx.conf
else
  # 인증서가 아직 없으면 친절히 안내 (init-letsencrypt.sh 가 더미를 만들어 둠)
  if [ ! -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]; then
    echo "WARN: /etc/letsencrypt/live/${DOMAIN}/fullchain.pem 가 없습니다." >&2
    echo "      ./init-letsencrypt.sh 를 먼저 실행하세요." >&2
  fi

  if [ ! -f /etc/letsencrypt/options-ssl-nginx.conf ] || [ ! -f /etc/letsencrypt/ssl-dhparams.pem ]; then
    echo "ERROR: certbot TLS 옵션 파일이 없습니다. ./init-letsencrypt.sh 를 먼저 실행하세요." >&2
    exit 1
  fi

  # 템플릿 → 실제 nginx.conf
  envsubst '${DOMAIN}' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf
fi

# 인증서 갱신 반영을 위한 주기적 reload (백그라운드)
( while :; do
    sleep 6h
    nginx -s reload 2>/dev/null || true
  done ) &

exec nginx -g 'daemon off;'
