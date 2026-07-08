-- ============================================================================
-- 복습 보상 체계: 졸업 카운터("나무") + 일별 복습 로그
--
-- 배경: 복습은 user_quiz_attempt 를 만들지 않으므로(첫 시도만 기록하는 정책)
--       ① 졸업해도 review_item 행이 삭제되어 누적 성과가 남지 않았고
--       ② 복습만 한 날은 잔디밭에 아무 흔적도 없었다.
--       users.graduated_review_count 로 ①을, review_daily_log 로 ②를 해결한다.
--
-- ⚠️ 운영은 ddl-auto=validate — 새 앱 배포 '전에' 실행돼야 한다 (CI Prepare 단계).
--
-- 실행:
--   docker exec -i mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/migration/2026-07-08-add-review-rewards.sql
--
-- 롤백:
--   ALTER TABLE users DROP COLUMN graduated_review_count;
--   DROP TABLE review_daily_log;
-- ============================================================================

SET time_zone = '+09:00';

-- 1) 졸업 카운터 — 기존 사용자는 0부터 시작 (과거 졸업 이력은 복원 불가, 정상)
ALTER TABLE users
    ADD COLUMN graduated_review_count INT NOT NULL DEFAULT 0;

-- 2) 일별 복습 로그 — (user, 날짜) 유니크로 하루 한 행 upsert
CREATE TABLE IF NOT EXISTS review_daily_log (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    review_date    DATE        NOT NULL,
    reviewed_count INT         NOT NULL,
    correct_count  INT         NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_daily_log UNIQUE (user_id, review_date),
    CONSTRAINT fk_review_daily_log_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
