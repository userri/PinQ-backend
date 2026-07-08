-- ============================================================================
-- 오답 복습("잔디에 물 주기") 테이블 신설 + 기존 오답 백필
--
-- ⚠️ 운영은 ddl-auto=validate — 새 앱 배포 '전에' 실행돼야 한다 (CI Prepare 단계).
--
-- 멱등성:
--  - CREATE TABLE IF NOT EXISTS
--  - 백필 INSERT 는 NOT EXISTS 가드로 재실행 시 0행
--
-- 백필 정책: 기능 도입 전의 오답(user_quiz_attempt.first_correct=0)을
-- stage 0, due=오늘로 등록해 배포 즉시 복습거리가 생기게 한다.
-- 이미 삭제된 퀴즈를 가리키는 오답은 제외 (quiz 조인으로 존재 확인).
-- ============================================================================

SET time_zone = '+09:00';

CREATE TABLE IF NOT EXISTS review_item (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    quiz_id    BIGINT      NOT NULL,
    stage      INT         NOT NULL,
    due_date   DATE        NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_item UNIQUE (user_id, quiz_id),
    KEY idx_review_item_user_due (user_id, due_date),
    CONSTRAINT fk_review_item_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO review_item (user_id, quiz_id, stage, due_date, created_at, updated_at)
SELECT a.user_id, a.quiz_id, 0, CURDATE(), NOW(6), NOW(6)
FROM user_quiz_attempt a
JOIN quiz q ON q.id = a.quiz_id
WHERE a.first_correct = 0
  AND NOT EXISTS (
      SELECT 1 FROM review_item r
      WHERE r.user_id = a.user_id AND r.quiz_id = a.quiz_id
  );
