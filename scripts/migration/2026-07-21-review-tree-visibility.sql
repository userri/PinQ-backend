-- ============================================================================
-- 복습 나무 가시화: 물 카운터 2종 + 졸업 보존
--
-- 배경: 복습의 성장 과정(물 준 횟수)과 졸업한 나무 목록이 어디에도 남지 않았다.
--       ① water_count/absorbed_count 로 물 준 횟수(총 시도/흡수)를 누적하고
--       ② 졸업 시 row 삭제 대신 graduated_at 을 기록해 "나무 목록"의 원천으로 삼는다.
--       기존에 이미 졸업(삭제)된 항목은 복원 불가 — users.graduated_review_count
--       카운터가 과거분을 포함한 유일한 총계로 유지된다.
--
-- ⚠️ 운영은 ddl-auto=validate — 새 앱 배포 '전에' 실행돼야 한다 (CI Prepare 단계).
--
-- 실행:
--   docker exec -i mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/migration/2026-07-21-review-tree-visibility.sql
--
-- 롤백:
--   DELETE FROM review_item WHERE graduated_at IS NOT NULL;
--   ALTER TABLE review_item DROP COLUMN water_count, DROP COLUMN absorbed_count, DROP COLUMN graduated_at;
-- ============================================================================

SET time_zone = '+09:00';

-- 기존 row 는 물 이력이 없으므로 0부터 시작 (과거 복습 횟수는 복원 불가, 정상)
ALTER TABLE review_item
    ADD COLUMN water_count    INT         NOT NULL DEFAULT 0,
    ADD COLUMN absorbed_count INT         NOT NULL DEFAULT 0,
    ADD COLUMN graduated_at   DATETIME(6) NULL;
