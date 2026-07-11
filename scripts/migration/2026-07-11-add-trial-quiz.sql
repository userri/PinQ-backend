-- ============================================================================
-- trial_quiz — 퀴즈 생성 dry-run 결과 아카이브 (프롬프트 실험 로그)
--
-- 실데이터(quiz)와 완전 분리. admin dry-run API 만 기록하며 서비스는 읽지 않음.
-- extra_*_rules 컬럼으로 어떤 실험 룰이 적용된 결과인지 추적 → 룰 전후 비교 분석.
--
-- 멱등: CREATE TABLE IF NOT EXISTS — CI 가 매 배포마다 무가드 실행.
-- 롤백: DROP TABLE trial_quiz;
-- ============================================================================

SET time_zone = '+09:00';

CREATE TABLE IF NOT EXISTS trial_quiz (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    category           VARCHAR(32)  NOT NULL,
    success            TINYINT(1)   NOT NULL,
    candidates_tried   INT          NOT NULL,
    question           VARCHAR(1000) NULL,
    choices_json       TEXT         NULL,
    explanation        VARCHAR(2000) NULL,
    keyword            VARCHAR(500) NULL,
    article_title      VARCHAR(500) NULL,
    article_url        VARCHAR(1000) NULL,
    extra_gen_rules    TEXT         NULL,
    extra_verify_rules TEXT         NULL,
    model              VARCHAR(64)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
