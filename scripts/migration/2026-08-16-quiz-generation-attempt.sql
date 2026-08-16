-- 퀴즈 생성 시도 1건 = 1행 (손실 집계 영속화)
--
-- 왜 필요한가
--   탈락 기록이 AuditLogBuffer(메모리 링버퍼)에만 남아 재시작·용량 초과로 사라졌다.
--   2026-08-15 에 검수가 하루 밀린 사이 그날 기록이 영구 소실됐고, 개념 포화·후보 경쟁
--   두 관측의 표본에 구멍이 났다. 사람이 그날 안에 떠야 남는 구조 자체를 없앤다.
--
-- 배포 순서 (docs/db-access-and-migration.md 규칙)
--   ① 이 스크립트 실행 → ② 테이블 확인 → ③ 새 이미지 배포
--   prod 는 DDL_AUTO=validate 라 테이블이 없으면 앱이 기동하지 않는다.
--   ⚠️ scripts/prepare-server.sh 에 등록해야 CI 가 실행한다. docs/migration/ 은 CI 가 보지 않는다.
--
-- NULL 허용
--   기사 정보(title·url·search_keyword)는 기사를 고르기 전 단계의 행이 있을 수 있어 NULL 허용.
--   reason 은 stage=PUBLISHED 일 때 NULL(탈락이 아니므로).
--   quiz_id 는 발행된 행에만 채운다. FK 를 걸지 않는 이유: 계측 테이블이 본 데이터의
--   삭제(재생성 시 그날 퀴즈 삭제)를 막으면 안 된다.

CREATE TABLE IF NOT EXISTS quiz_generation_attempt (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(6)  NOT NULL,
    occurred_on    DATE         NOT NULL,
    category       VARCHAR(32)  NOT NULL,
    run_window     VARCHAR(16)  NOT NULL,
    search_keyword VARCHAR(64)  NULL,
    article_title  VARCHAR(512) NULL,
    article_url    VARCHAR(512) NULL,
    stage          VARCHAR(16)  NOT NULL,
    reason         VARCHAR(32)  NULL,
    detail         VARCHAR(255) NULL,
    quiz_id        BIGINT       NULL,
    PRIMARY KEY (id),
    -- 유일한 읽기 패턴이 "최근 N일을 날짜×슬롯으로 묶기"다. occurred_at 에 DATE() 를 씌우면
    -- 인덱스를 못 타므로 날짜 컬럼을 따로 두고 여기에 인덱스를 건다(token_usage 와 같은 판단).
    KEY idx_attempt_day_category (occurred_on, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
