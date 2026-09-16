-- quiz_generation_attempt.content_source — 기사 본문 출처 (SCRAPED | DESCRIPTION | NULL)
--
-- 왜 필요한가
--   생성은 항상 두 단계다: 네이버 검색 API 로 목록을 받고, link 가 news.naver.com 이면 본문을
--   스크래핑한다. 실패하면 API description(150자)으로 폴백한다. 그 비율(스크래핑 성공 : 폴백)이
--   어디에도 남지 않았다 — 스크래퍼 로그 줄이 AuditLogBuffer 의 키워드 패턴에 안 걸려 링버퍼에도
--   없었다(2026-09-17 확인). 링버퍼 패턴을 넓히는 대신 시도 행에 박아 영속시킨다.
--
-- NULL 허용
--   본문을 확보하기 전 단계(PREFILTER: 사설·교차 사용)의 행, 그리고 이 컬럼 이전의 모든 행.
--   백필하지 않는다 — 과거 행의 출처는 알 수 없고, 0 과 "모른다"는 다르다.
--
-- 배포 순서 (docs/db-access-and-migration.md 규칙)
--   ① 이 스크립트 실행 → ② 새 이미지 배포. prod 는 DDL_AUTO=validate 라 컬럼이 없으면 기동 실패.
--   ⚠️ scripts/prepare-server.sh 에 col_exists 가드로 등록해야 CI 가 실행한다.
--
-- 멱등: prepare-server.sh 가 col_exists 가드로 1회만 실행. 롤백: DROP COLUMN content_source.
ALTER TABLE quiz_generation_attempt
    ADD COLUMN content_source VARCHAR(16) NULL AFTER quiz_id;
