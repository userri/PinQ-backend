#!/bin/bash
# ────────────────────────────────────────────────────────────────────────────
# 배포 전 서버 준비: .env 시크릿 동기화 + 멱등 DB 마이그레이션
#
# CI "Prepare EC2" 스텝이 실행한다. 원래 ci.yml 인라인 스크립트였으나,
# appleboy/ssh-action 의 script_stop(실패 전파 필수)이 multi-line 구문을
# 깨뜨리는 문제가 있어 파일로 분리했다 — SSH 스텝은 이 파일을 한 줄로 호출.
#
# 필요 env: FIREBASE_SERVICE_ACCOUNT_BASE64 (선택 — 없으면 FCM 비활성 안내만)
# 실행 위치: ~/pinq_backend
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")/.."

# ── 1) FCM 서비스 계정 시크릿 → .env (멱등: 있으면 갱신, 없으면 추가) ──
if [ -n "${FIREBASE_SERVICE_ACCOUNT_BASE64:-}" ]; then
  if grep -q '^FIREBASE_SERVICE_ACCOUNT_BASE64=' .env; then
    sed -i "s|^FIREBASE_SERVICE_ACCOUNT_BASE64=.*|FIREBASE_SERVICE_ACCOUNT_BASE64=$FIREBASE_SERVICE_ACCOUNT_BASE64|" .env
  else
    printf 'FIREBASE_SERVICE_ACCOUNT_BASE64=%s\n' "$FIREBASE_SERVICE_ACCOUNT_BASE64" >> .env
  fi
  echo "OK: FIREBASE_SERVICE_ACCOUNT_BASE64 -> .env 동기화"
else
  echo "SKIP: FIREBASE_SERVICE_ACCOUNT_BASE64 시크릿 미설정 — FCM 푸시 비활성 상태로 배포됩니다"
fi

# ── 2) 대기 중인 DB 마이그레이션 (컬럼 존재 여부로 멱등 가드) ──
set -a; . ./.env; set +a
MYSQL_CONTAINER=$(docker ps --format '{{.Names}}' | grep -m1 -i mysql)
echo "MySQL 컨테이너: $MYSQL_CONTAINER"

col_exists() {
  docker exec "$MYSQL_CONTAINER" mysql -N -u"$DB_USERNAME" -p"$DB_PASSWORD" -e \
    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='$1' AND COLUMN_NAME='$2'" 2>/dev/null
}

run_sql() {
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" < "$1"
}

if [ "$(col_exists quiz category)" = "0" ]; then
  run_sql scripts/migration/2026-07-07-add-quiz-category.sql
  echo "OK: quiz-category 마이그레이션 적용"
else
  echo "SKIP: quiz.category 이미 존재"
fi

if [ "$(col_exists users notification_enabled)" = "0" ]; then
  run_sql scripts/migration/2026-07-07-add-notification.sql
  echo "OK: notification 마이그레이션 적용"
else
  echo "SKIP: users.notification_enabled 이미 존재"
fi

# 타임스탬프 +9h 오염 보정 — 쿼리 자체가 멱등(미래 값만 보정)이라 매 배포 실행
run_sql scripts/migration/2026-07-08-fix-timestamp-shift.sql
echo "OK: 타임스탬프 오염 보정 실행 (오염 행 없으면 no-op)"

# 오답 복습 테이블 + 기존 오답 백필 — CREATE IF NOT EXISTS / INSERT NOT EXISTS 로 멱등
run_sql scripts/migration/2026-07-08-add-review-item.sql
echo "OK: review_item 마이그레이션 실행"

# 복습 보상(졸업 카운터 + 일별 로그) — users 컬럼은 ADD COLUMN 이라 존재 가드 필요
if [ "$(col_exists users graduated_review_count)" = "0" ]; then
  run_sql scripts/migration/2026-07-08-add-review-rewards.sql
  echo "OK: review-rewards 마이그레이션 적용"
else
  echo "SKIP: users.graduated_review_count 이미 존재"
fi

# user_quiz_attempt 신호 컬럼(풀이 시간·피드백) — ADD COLUMN 이라 존재 가드 필요
if [ "$(col_exists user_quiz_attempt first_elapsed_ms)" = "0" ]; then
  run_sql scripts/migration/2026-07-15-attempt-signal-columns.sql
  echo "OK: attempt 신호 컬럼 마이그레이션 적용"
else
  echo "SKIP: user_quiz_attempt.first_elapsed_ms 이미 존재"
fi

# feedback TINYINT→INT 보정 — 타입 검사로 멱등 가드
FEEDBACK_TYPE=$(docker exec "$MYSQL_CONTAINER" mysql -N -u"$DB_USERNAME" -p"$DB_PASSWORD" -e \
  "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='user_quiz_attempt' AND COLUMN_NAME='feedback'" 2>/dev/null)
if [ "$FEEDBACK_TYPE" = "tinyint" ]; then
  run_sql scripts/migration/2026-07-15-fix-feedback-int.sql
  echo "OK: feedback TINYINT→INT 보정 적용"
else
  echo "SKIP: feedback 타입 정상"
fi

# dry-run 실험 로그 테이블 — IF NOT EXISTS 로 멱등, 매 배포 실행
run_sql scripts/migration/2026-07-11-add-trial-quiz.sql
echo "OK: trial_quiz 마이그레이션 실행"

# news_article.category ENUM → VARCHAR(32) — 타입 검사로 멱등 가드
CATEGORY_TYPE=$(docker exec "$MYSQL_CONTAINER" mysql -N -u"$DB_USERNAME" -p"$DB_PASSWORD" -e \
  "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='news_article' AND COLUMN_NAME='category'" 2>/dev/null)
if [ "$CATEGORY_TYPE" = "enum" ]; then
  run_sql scripts/migration/2026-07-12-news-article-category-varchar.sql
  echo "OK: news_article.category ENUM→VARCHAR 마이그레이션 적용"
else
  echo "SKIP: news_article.category 이미 VARCHAR"
fi

# trial_quiz.model (모델 A/B 비교) — ADD COLUMN 이라 존재 가드 필요
if [ "$(col_exists trial_quiz model)" = "0" ]; then
  run_sql scripts/migration/2026-07-11-add-trial-quiz-model.sql
  echo "OK: trial_quiz.model 마이그레이션 적용"
else
  echo "SKIP: trial_quiz.model 이미 존재"
fi

# 복습 나무 가시화(물 카운터 + 졸업 보존) — ADD COLUMN 이라 존재 가드 필요
if [ "$(col_exists review_item water_count)" = "0" ]; then
  run_sql scripts/migration/2026-07-21-review-tree-visibility.sql
  echo "OK: review-tree-visibility 마이그레이션 적용"
else
  echo "SKIP: review_item.water_count 이미 존재"
fi

echo "✅ 서버 준비 완료"
