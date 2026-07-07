-- ============================================================================
-- 감사 타임스탬프 +9h 오염 보정
--
-- 배경: b52ff22(감사 시각을 KST Clock 으로 변경)가 배포된 뒤, UTC JVM +
--       serverTimezone=Asia/Seoul 조합의 이중 변환으로 created_at/updated_at 이
--       실제보다 9시간 미래로 저장됐다 (예: KST 23:00 이벤트 → 익일 08:00 저장).
--       근본 수정은 docker-compose 의 TZ/JAVA_TOOL_OPTIONS=KST 고정이며,
--       이 스크립트는 오염된 기존 행을 -9h 되돌린다.
--
-- 멱등성: "미래 타임스탬프는 존재할 수 없다"를 판별 조건으로 쓴다.
--   NOW() 보다 5분 이상 미래인 값만 보정하므로, 보정 후 재실행하면 0행 매치.
--   (오염 발생 후 9시간 안에 실행되어야 전량 잡힌다 — CI가 배포마다 실행하므로 충족)
--
-- ⚠️ 세션 타임존 고정이 필수인 이유 (실제 사고 이력):
--   MySQL 컨테이너는 UTC 로 돌기 때문에 NOW() 가 UTC 로 평가된다. 그러면
--   KST 벽시계로 저장된 '정상' 값(예: 23:00)도 UTC NOW(14:10) 기준 "미래"로
--   보여 -9h 가 중복 적용된다 (2026-07-07 배포에서 실제 발생, +9h 오염이
--   -9h 과보정되어 UTC 로 저장됨). 아래 SET 으로 NOW() 를 KST 로 고정한다.
--
-- 실행: CI "Prepare EC2" 단계가 매 배포마다 자동 실행 (조건부 가드 불필요)
-- ============================================================================

SET time_zone = '+09:00';

UPDATE users             SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE users             SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE quiz              SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE quiz              SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE choice            SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE choice            SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE news_article      SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE news_article      SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE user_quiz_attempt SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE user_quiz_attempt SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE user_bookmark     SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE user_bookmark     SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE solved_history    SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE solved_history    SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE user_device_token SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE user_device_token SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;
UPDATE notification_log  SET created_at = created_at - INTERVAL 9 HOUR WHERE created_at > NOW() + INTERVAL 5 MINUTE;
UPDATE notification_log  SET updated_at = updated_at - INTERVAL 9 HOUR WHERE updated_at > NOW() + INTERVAL 5 MINUTE;

-- 확인용 (선택): 남아 있는 미래 타임스탬프가 0이어야 정상
-- SELECT 'users' t, COUNT(*) FROM users WHERE created_at > NOW() + INTERVAL 5 MINUTE;
