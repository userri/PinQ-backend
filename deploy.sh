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
HEALTH_RETRIES=20
HEALTH_INTERVAL=3  # 초

# ── 현재 라이브 슬롯 판별 ────────────────────────────────────────────────────
CURRENT=$(grep -o 'pinq-app-[a-z]*' nginx/upstream.conf 2>/dev/null | head -1 || echo "pinq-app-green")

if [[ "$CURRENT" == "pinq-app-blue" ]]; then
  LIVE="blue"
  NEXT="green"
else
  LIVE="green"
  NEXT="blue"
fi

echo "▶ 현재 라이브: $LIVE  →  배포 대상: $NEXT  (태그: $IMAGE_TAG)"

# ── 이미지 pull ────────────────────────────────────────────────────────────
echo "▶ 이미지 pull 중 (IMAGE_TAG=${IMAGE_TAG})..."
APP_IMAGE_TAG="${IMAGE_TAG}" docker compose pull app-${NEXT}

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
    docker compose stop app-${NEXT}
    exit 1
  fi

  echo "  대기 중... ($i/$HEALTH_RETRIES) 상태: $STATUS"
  sleep $HEALTH_INTERVAL
done

if [[ "$STATUS" != "healthy" ]]; then
  echo "✗ 헬스체크 타임아웃 — 배포 중단"
  docker compose stop app-${NEXT}
  exit 1
fi

# ── nginx upstream 교체 ───────────────────────────────────────────────────
echo "▶ nginx upstream → pinq-app-${NEXT} 전환 중..."
cp nginx/upstream-${NEXT}.conf nginx/upstream.conf
docker exec pinq-nginx nginx -s reload
echo "✓ nginx reload 완료 (무중단 전환)"

# ── 기존 슬롯 종료 ───────────────────────────────────────────────────────
echo "▶ pinq-app-${LIVE} 종료..."
docker compose stop app-${LIVE}

echo ""
echo "✅ 배포 완료: $LIVE → $NEXT (태그: $IMAGE_TAG)"
echo "   라이브: pinq-app-${NEXT}"
