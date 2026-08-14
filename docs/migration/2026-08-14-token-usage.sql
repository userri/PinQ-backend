-- 토큰 사용량 영속 계측 테이블
--
-- 왜 필요한가
--   TokenUsageLogger 가 만든 `token-usage ...` 한 줄은 AuditLogBuffer(메모리 링버퍼)에만 남아
--   **배포·재시작마다 사라졌다.** 2026-08-14 검수에서 프롬프트 캐싱 적중(verify 12회 중 read 11)을
--   처음 계측했는데, 그 값을 다음 날과 비교할 방법이 없다는 것이 확인돼 테이블로 옮긴다.
--   로그 한 줄은 그대로 남는다(실시간 확인 경로).
--
-- 배포 순서 (docs/db-access-and-migration.md 규칙)
--   ① 이 스크립트 실행  →  ② 테이블 확인  →  ③ 새 이미지 배포
--   prod 는 DDL_AUTO=validate 라 **테이블이 없으면 앱이 기동하지 않는다.** 순서를 바꾸지 말 것.
--
-- NULL 허용
--   model 만 NULL 을 허용한다 — 응답에 model 필드가 없는 경우가 있고(구형 응답·오류 응답),
--   그 한 건 때문에 계측을 통째로 버리는 것보다 모델 미상으로 남기는 편이 낫다.
--   토큰 수는 전부 NOT NULL — 값이 없으면 애초에 저장하지 않는다(파싱 단계에서 걸러진다).

CREATE TABLE token_usage (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at       DATETIME(6)  NOT NULL,
    occurred_on       DATE         NOT NULL,
    kind              VARCHAR(16)  NOT NULL,
    model             VARCHAR(128) NULL,
    prompt_tokens     INT          NOT NULL,
    completion_tokens INT          NOT NULL,
    cache_write_tokens INT         NOT NULL,
    cache_read_tokens  INT         NOT NULL,
    total_tokens      INT          NOT NULL,
    PRIMARY KEY (id),
    -- 유일한 읽기 패턴이 "최근 N일을 날짜×kind 로 묶기"다. occurred_at 에 DATE() 를 씌우면
    -- 인덱스를 못 타므로 날짜 컬럼을 따로 두고 여기에 인덱스를 건다.
    KEY idx_token_usage_day_kind (occurred_on, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
