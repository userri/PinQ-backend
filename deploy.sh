#!/bin/bash
# ────────────────────────────────────────────────────────────────────────────
# PinQ Blue/Green 배포 스크립트
#
# 사용법:
#   ./deploy.sh                 # DockerHub latest 이미지로 배포
#   ./deploy.sh v1.2.3          # 특정 태그로 배포
#
# 동작 순서:
#   1. 현재 라이브 슬롯(blue/green) 확인
#   2. 대기 슬롯에 새 컨테이너 실행
#   3. 헬스체크 통과 대기
#   4. nginx upstream 교체 → reload (무중단)
#   5. 기존 슬롯 컨테이너 종료
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

IMAGE_TAG="${1:-latest}"
HEALTH_RETRIES=36  # 36회 × 5초 = 180초 (start-period 60s + interval 10s × retries 3 충분 커버)
HEALTH_INTERVAL=5  # 초

REQUIRED_DEPLOY_FILES=(
  "docker-compose.yml"
  "nginx/nginx.conf.template"
  "nginx/entrypoint.sh"
  "nginx/upstream-blue.conf"
  "nginx/upstream-green.conf"
)

for file in "${REQUIRED_DEPLOY_FILES[@]}"; do
  if [ ! -f "$file" ]; then
    echo "✗ 배포 필수 파일이 없습니다: $file" >&2
    echo "  EC2 배포 디렉터리에 nginx 템플릿/엔트리포인트 파일까지 함께 복사됐는지 확인하세요." >&2
    exit 1
  fi
done

# ── 현재 라이브 슬롯 판별 ────────────────────────────────────────────────────
is_running() {
  local slot="$1"
  [[ "$(docker inspect --format='{{.State.Running}}' "pinq-app-${slot}" 2>/dev/null || echo false)" == "true" ]]
}

BLUE_RUNNING=false
GREEN_RUNNING=false
is_running blue && BLUE_RUNNING=true
is_running green && GREEN_RUNNING=true

if [[ "$BLUE_RUNNING" == "true" && "$GREEN_RUNNING" != "true" ]]; then
  LIVE="blue"
  NEXT="green"
elif [[ "$GREEN_RUNNING" == "true" && "$BLUE_RUNNING" != "true" ]]; then
  LIVE="green"
  NEXT="blue"
else
  CURRENT=$(grep -o 'pinq-app-[a-z]*' nginx/upstream.conf 2>/dev/null | head -1 || true)

  if [[ "$CURRENT" == "pinq-app-blue" ]]; then
    LIVE="blue"
    NEXT="green"
  elif [[ "$CURRENT" == "pinq-app-green" ]]; then
    LIVE="green"
    NEXT="blue"
  else
    LIVE="green"
    NEXT="blue"
  fi
fi

echo "▶ 현재 라이브: $LIVE  →  배포 대상: $NEXT  (태그: $IMAGE_TAG)"

# ── 외부 네트워크 보장 ──────────────────────────────────────────────────
docker network inspect resources_default >/dev/null 2>&1 \
  || docker network create resources_default

# ── 이미지 pull ────────────────────────────────────────────────────────────
echo "▶ 이미지 pull 중 (IMAGE_TAG=${IMAGE_TAG})..."
APP_IMAGE_TAG="${IMAGE_TAG}" docker compose pull app-${NEXT}

# ── 의존 서비스(redis) 보장 ──────────────────────────────────────────────────
echo "▶ redis 컨테이너 확인 및 시동..."
docker compose up -d --no-deps redis

# ── 대기 슬롯 컨테이너 교체 ──────────────────────────────────────────────────
echo "▶ pinq-app-${NEXT} 시작 (IMAGE_TAG=${IMAGE_TAG})..."
APP_IMAGE_TAG="${IMAGE_TAG}" docker compose up -d --no-deps app-${NEXT}

# ── 헬스체크 (nginx 통하지 않고 컨테이너 직접 확인) ──────────────────────────
echo "▶ 헬스체크 대기 중..."
for i in $(seq 1 $HEALTH_RETRIES); do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' pinq-app-${NEXT} 2>/dev/null || echo "none")

  if [[ "$STATUS" == "healthy" ]]; then
    echo "✓ pinq-app-${NEXT} 헬스체크 통과"
    break
  elif [[ "$STATUS" == "unhealthy" ]]; then
    echo "✗ pinq-app-${NEXT} unhealthy — 배포 중단"
    echo "▶ 앱 로그 (마지막 80줄):"
    docker logs --tail 80 pinq-app-${NEXT} 2>&1 || true
    docker compose stop app-${NEXT}
    exit 1
  fi

  echo "  대기 중... ($i/$HEALTH_RETRIES) 상태: $STATUS"
  sleep $HEALTH_INTERVAL
done

if [[ "$STATUS" != "healthy" ]]; then
  echo "✗ 헬스체크 타임아웃 — 배포 중단"
  echo "▶ 앱 로그 (마지막 50줄):"
  docker logs --tail 50 pinq-app-${NEXT} 2>&1 || true
  docker compose stop app-${NEXT}
  exit 1
fi

# ── nginx upstream 교체 ───────────────────────────────────────────────────
echo "▶ nginx upstream → pinq-app-${NEXT} 전환 중..."
cp nginx/upstream-${NEXT}.conf nginx/upstream.conf
docker compose up -d --no-deps nginx
docker exec pinq-nginx nginx -s reload
echo "✓ nginx reload 완료 (무중단 전환)"

# ── 기존 슬롯 종료 ───────────────────────────────────────────────────────
echo "▶ pinq-app-${LIVE} 종료..."
docker compose stop app-${LIVE}

echo ""
echo "✅ 배포 완료: $LIVE → $NEXT (태그: $IMAGE_TAG)"
echo "   라이브: pinq-app-${NEXT}"
