-- ============================================================================
-- 푸시 알림 기능: users 컬럼 추가 + 디바이스 토큰 / 전송 로그 테이블 신설
--
-- ⚠️ 운영은 ddl-auto=validate 이므로 새 앱 배포 '전에' 이 스크립트를 먼저 실행할 것.
--    (2026-07-07-add-quiz-category.sql 과 함께 실행하면 됨)
--
-- 실행:
--   docker exec -i mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/migration/2026-07-07-add-notification.sql
--
-- 롤백:
--   ALTER TABLE users DROP COLUMN notification_enabled, DROP COLUMN notification_time;
--   DROP TABLE notification_log;
--   DROP TABLE user_device_token;
-- ============================================================================

-- 1) users: 알림 설정. 기존 사용자는 알림 꺼짐 + 기본 시각 09:00
ALTER TABLE users
    ADD COLUMN notification_enabled TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN notification_time TIME NULL DEFAULT '09:00:00';

-- 2) FCM 디바이스 토큰 (user 1 : N token, 토큰은 전역 유니크)
CREATE TABLE user_device_token (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token      VARCHAR(512) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_token UNIQUE (token),
    KEY idx_device_token_user (user_id),
    CONSTRAINT fk_device_token_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 알림 전송 로그 — (user, 날짜, 슬롯) 유니크가 중복 전송을 DB 레벨에서 차단
CREATE TABLE notification_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    sent_date  DATE        NOT NULL,
    slot_time  TIME        NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_log UNIQUE (user_id, sent_date, slot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
