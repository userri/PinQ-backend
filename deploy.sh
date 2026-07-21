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
  "nginx/nginx.conf"
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

NGINX_STATE=$(docker inspect --format='{{.State.Running}} {{.State.Restarting}}' pinq-nginx 2>/dev/null || echo "false false")
NGINX_RUNNING=$(awk '{print $1}' <<< "$NGINX_STATE")
NGINX_RESTARTING=$(awk '{print $2}' <<< "$NGINX_STATE")

if [[ "$NGINX_RUNNING" != "true" || "$NGINX_RESTARTING" == "true" ]]; then
  echo "▶ nginx 컨테이너 복구/시동 중..."
  docker compose up -d --no-deps nginx
fi

for i in $(seq 1 12); do
  NGINX_STATE=$(docker inspect --format='{{.State.Running}} {{.State.Restarting}}' pinq-nginx 2>/dev/null || echo "false false")
  NGINX_RUNNING=$(awk '{print $1}' <<< "$NGINX_STATE")
  NGINX_RESTARTING=$(awk '{print $2}' <<< "$NGINX_STATE")

  if [[ "$NGINX_RUNNING" == "true" && "$NGINX_RESTARTING" != "true" ]]; then
    break
  fi

  echo "  nginx 대기 중... ($i/12) 상태: running=$NGINX_RUNNING restarting=$NGINX_RESTARTING"
  sleep 1
done

if [[ "$NGINX_RUNNING" != "true" || "$NGINX_RESTARTING" == "true" ]]; then
  echo "✗ nginx 컨테이너가 실행 가능 상태가 아닙니다." >&2
  docker logs --tail 80 pinq-nginx 2>&1 || true
  exit 1
fi

docker exec pinq-nginx nginx -t
docker exec pinq-nginx nginx -s reload
echo "✓ nginx reload 완료 (무중단 전환)"

# ── 기존 슬롯 종료 ───────────────────────────────────────────────────────
echo "▶ pinq-app-${LIVE} 종료..."
docker compose stop app-${LIVE}

# ── 워밍업 (콜드스타트 액땜) ──────────────────────────────────────────────
# 옛 슬롯 종료 '후'(2-JVM 메모리 피크가 끝난 시점 — 843MB VM 스왑 안전)에 라이브
# 컨테이너의 핫 경로를 미리 호출한다. 실사용자 도착 전에 JIT 컴파일 / Hibernate
# 쿼리플랜 / HikariCP 커넥션 / 스왑에서 밀려난 페이지 복귀를 끝내 첫 요청 지연을 없앤다.
# 컨테이너 내장 curl(헬스체크가 쓰는 것) 재사용 — 포트 발행·외부 이미지·TLS 불필요.
# best-effort: 워밍업 실패가 이미 성공한 무중단 배포를 되돌리면 안 되므로 전부 || true.
echo "▶ 워밍업 (콜드스타트 액땜)..."
WARM_SECRET=""
if [ -f .env ]; then
  WARM_SECRET=$(grep -E '^ADMIN_SECRET=' .env | head -1 | cut -d= -f2- | tr -d '\r\n ')
fi
for i in 1 2 3; do
  # /actuator/health: 데이터소스 헬스 인디케이터가 커넥션을 데운다
  docker exec pinq-app-${NEXT} curl -fsS -o /dev/null http://localhost:8080/actuator/health 2>/dev/null || true
  # /api/admin/quizzes/stats: 시큐리티→컨트롤러→서비스→JPA→DB 전 스택 워밍 (JIT 핵심)
  if [ -n "$WARM_SECRET" ]; then
    docker exec -e WARM_SECRET="$WARM_SECRET" pinq-app-${NEXT} \
      sh -c 'curl -fsS -o /dev/null -H "X-Admin-Secret: $WARM_SECRET" http://localhost:8080/api/admin/quizzes/stats' 2>/dev/null || true
  fi
done
echo "✓ 워밍업 완료 (health$([ -n "$WARM_SECRET" ] && echo ' + stats') × 3)"

# ── 배포 태그를 .env 에 기록 ─────────────────────────────────────────────
# APP_IMAGE_TAG 는 위에서 이 스크립트 실행 중에만 env 로 주입되므로, 기록해두지
# 않으면 이후 수동 `docker compose up --force-recreate` (예: .env 변경 반영)가
# 기본값(:latest / 옛 태그)으로 컨테이너를 되살려 조용한 롤백이 된다.
# (2026-07-10 실사고: GOOGLE_WEB_CLIENT_ID 반영 재기동이 구버전 롤백을 유발)
if grep -q '^APP_IMAGE_TAG=' .env 2>/dev/null; then
  sed -i "s|^APP_IMAGE_TAG=.*|APP_IMAGE_TAG=${IMAGE_TAG}|" .env
else
  printf 'APP_IMAGE_TAG=%s\n' "${IMAGE_TAG}" >> .env
fi
echo "✓ .env APP_IMAGE_TAG=${IMAGE_TAG} 기록 (재기동 롤백 방지)"

echo ""
echo "✅ 배포 완료: $LIVE → $NEXT (태그: $IMAGE_TAG)"
echo "   라이브: pinq-app-${NEXT}"

# ── 배포 '후' 타임스탬프 보정 재실행 ─────────────────────────────────────
# 배포 전 보정과 새 컨테이너 기동 사이에 구버전 컨테이너가 쓴 오염 행(+9h)까지
# 마저 잡는다. 쿼리 자체가 멱등(미래 값만 보정)이라 안전. (기존 CI 인라인에서 이동)
if [ -f scripts/migration/2026-07-08-fix-timestamp-shift.sql ]; then
  set -a; . ./.env; set +a
  MYSQL_CONTAINER=$(docker ps --format '{{.Names}}' | grep -m1 -i mysql)
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
    < scripts/migration/2026-07-08-fix-timestamp-shift.sql 2>/dev/null \
    && echo "✓ 배포 후 타임스탬프 보정 완료" \
    || echo "⚠ 배포 후 타임스탬프 보정 실패 (배포 자체는 성공)"
fi
