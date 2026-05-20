#!/bin/bash
# DuckDNS 도메인 IP 자동 갱신.
#
# EC2 가 elastic IP 가 아니거나, 다른 서버로 옮길 때 IP 가 바뀌면 호출.
# crontab 권장: */5 * * * * /path/to/duckdns-update.sh >/dev/null 2>&1
#
# .env 에 아래 두 변수 필요:
#   DUCKDNS_SUBDOMAIN=finq           # finq.duckdns.org 의 'finq' 부분
#   DUCKDNS_TOKEN=xxxx-xxxx-xxxx     # duckdns.org 대시보드의 token
set -euo pipefail

cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

: "${DUCKDNS_SUBDOMAIN:?DUCKDNS_SUBDOMAIN 이 .env 에 설정돼야 합니다}"
: "${DUCKDNS_TOKEN:?DUCKDNS_TOKEN 이 .env 에 설정돼야 합니다}"

# ip= 를 비워두면 DuckDNS 가 요청 출처 IP 를 자동 사용
RESPONSE=$(curl -sS "https://www.duckdns.org/update?domains=${DUCKDNS_SUBDOMAIN}&token=${DUCKDNS_TOKEN}&ip=")

if [ "$RESPONSE" = "OK" ]; then
  echo "[$(date -Is)] DuckDNS update: OK"
else
  echo "[$(date -Is)] DuckDNS update FAILED: $RESPONSE" >&2
  exit 1
fi
