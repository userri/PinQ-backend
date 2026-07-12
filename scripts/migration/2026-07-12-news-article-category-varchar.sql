-- news_article.category: ENUM → VARCHAR(32)
--
-- 사고(2026-07-12 06:00): INFLATION 카테고리 신설 후 첫 정기 생성에서
-- "Data truncated for column 'category'" — 이 컬럼만 ENUM('EXCHANGE_RATE',...)
-- 이라 새 enum 값을 몰랐고, 단일 트랜잭션이라 성공한 4개까지 통째 롤백돼
-- 그날 퀴즈가 0개가 됐다. quiz/trial_quiz 와 동일하게 VARCHAR(32)로 통일해
-- 이후 카테고리 추가가 DB 마이그레이션 없이 되도록 한다.
--
-- 멱등: prepare-server.sh 가 COLUMN_TYPE 검사로 1회만 실행.
ALTER TABLE news_article MODIFY category VARCHAR(32) NOT NULL;
