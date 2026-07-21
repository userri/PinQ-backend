-- ============================================================================
-- 복습 물 카운터 과거분 최소값 백필
--
-- 배경: 커밋 75ee22d 에서 review_item 에 water_count/absorbed_count 컬럼이
--       DEFAULT 0 으로 신설됐다. 그 이전부터 복습을 진행해온 항목들은 물 준
--       횟수가 0 으로 보여 "여태 푼 복습이 날아간 것처럼" 느껴진다.
--       정확한 과거 횟수는 복원 불가하지만, 최소값은 추론 가능하다:
--       현재 stage N 인 항목은 (마지막 리셋 이후) N 번 연속 맞혔다는 뜻이므로
--       absorbed_count ≥ N, water_count ≥ N 이 보장된다. 그 최소값을 채워준다.
--
-- 멱등성: WHERE water_count = 0 이 멱등 가드다. 한 번 적용되면 water_count=stage>0
--         이 되어 재실행 시 대상 0건. 마이그레이션 이후 실제로 물을 준 항목
--         (water_count>0)도 이 조건 덕분에 건드리지 않는다.
--
-- 한계: 리셋 이전에 준 물, 이미 졸업(graduated_at IS NOT NULL)한 나무는 복원 불가.
--       stage 기반 최소값이라 실제보다 과소추정될 수 있다(합의됨).
--
-- ⚠️ 운영은 ddl-auto=validate — 컬럼 신설 마이그레이션
--    (2026-07-21-review-tree-visibility.sql) '이후'에 실행돼야 한다.
--
-- 실행:
--   docker exec -i mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/migration/2026-07-21-review-water-backfill.sql
-- ============================================================================

SET time_zone = '+09:00';

UPDATE review_item
SET water_count    = stage,
    absorbed_count = stage
WHERE graduated_at IS NULL
  AND stage > 0
  AND water_count = 0;
