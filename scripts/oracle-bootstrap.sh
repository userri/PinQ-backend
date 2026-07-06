#!/bin/bash
# ────────────────────────────────────────────────────────────────────────────
# Oracle Cloud (Ubuntu, Ampere A1) 신규 서버 부트스트랩
#
# 사용법 (새 서버에 SSH 접속 후):
#   export DB_NAME=pinq_db DB_USERNAME=... DB_PASSWORD=... MYSQL_ROOT_PASSWORD=...
#   bash oracle-bootstrap.sh
#
# 하는 일:
#   1. 방화벽 80/443 오픈 — Oracle Ubuntu 이미지는 iptables 로 기본 차단돼 있음
#   2. Docker + compose 플러그인 설치
#   3. resources_default 외부 네트워크 생성 (docker-compose.yml 이 참조)
#   4. MySQL 8 컨테이너(mysql-container) 실행 — 기존 EC2 와 동일한 구성
#   5. ~/pinq_backend 배포 디렉토리 생성
#
# 전체 이전 절차는 docs/oracle-migration.md 참고.
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

: "${DB_NAME:?DB_NAME 환경변수를 설정하세요 (예: pinq_db)}"
: "${DB_USERNAME:?DB_USERNAME 환경변수를 설정하세요}"
: "${DB_PASSWORD:?DB_PASSWORD 환경변수를 설정하세요}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 환경변수를 설정하세요}"

echo "▶ [1/5] 방화벽 80/443 오픈"
# Oracle 의 Ubuntu 이미지는 /etc/iptables/rules.v4 의 REJECT 규칙으로
# 22 외 모든 인바운드를 차단한다. ACCEPT 를 앞쪽에 삽입하고 영속화한다.
# (OCI 콘솔의 VCN Security List 에서도 80/443 을 열어야 한다 — 가이드 2단계)
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq netfilter-persistent iptables-persistent >/dev/null
sudo netfilter-persistent save

echo "▶ [2/5] Docker 설치"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
  echo "  (docker 그룹 적용은 재로그인 후부터 — 이 스크립트 안에서는 sudo 로 계속 진행)"
fi

echo "▶ [3/5] docker 네트워크 resources_default"
sudo docker network inspect resources_default >/dev/null 2>&1 \
  || sudo docker network create resources_default

echo "▶ [4/5] MySQL 8 컨테이너 (mysql-container)"
if sudo docker inspect mysql-container >/dev/null 2>&1; then
  echo "  이미 존재 — 건너뜀"
else
  sudo docker run -d --name mysql-container \
    --network resources_default \
    --restart unless-stopped \
    -v mysql-data:/var/lib/mysql \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
    -e MYSQL_DATABASE="$DB_NAME" \
    -e MYSQL_USER="$DB_USERNAME" \
    -e MYSQL_PASSWORD="$DB_PASSWORD" \
    -e TZ=Asia/Seoul \
    mysql:8.0
  echo "  기동 대기 중..."
  for i in $(seq 1 30); do
    if sudo docker exec mysql-container mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent 2>/dev/null; then
      echo "  MySQL 준비 완료"
      break
    fi
    sleep 2
  done
fi

echo "▶ [5/5] 배포 디렉토리"
mkdir -p ~/pinq_backend/nginx

echo ""
echo "✅ 부트스트랩 완료. 다음 단계는 docs/oracle-migration.md 의 4단계(배포 파일 복사)부터."
