-- ============================================================================
-- Quiz.category 컬럼 추가 + 기존 행 백필
--
-- 배경: quiz 는 그동안 카테고리를 news_article.category 로부터 파생했는데,
--       저장 시 findByUrl 로 기존 기사를 재사용하면 기사의 '최초' 카테고리가
--       따라와 실제 출제 카테고리와 어긋나는 라벨 오염 버그가 있었다.
--       이제 quiz.category 에 '출제 슬롯' 카테고리를 직접 저장해 신뢰 원천으로 삼는다.
--
-- ⚠️ 실행 순서 (중요):
--   운영은 spring.jpa.hibernate.ddl-auto=validate 이므로, 엔티티에 category 가
--   추가된 새 앱을 배포하기 '전에' 아래 1) ALTER TABLE 이 먼저 적용돼 있어야
--   앱이 정상 기동한다. 순서: ① 이 스크립트 실행 → ② 새 앱 배포.
--
-- 실행 방법 (프로덕션):
--   docker exec -i mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/migration/2026-07-07-add-quiz-category.sql
--
-- 롤백: ALTER TABLE quiz DROP COLUMN category;  (구 앱은 이 컬럼을 안 봄)
-- ============================================================================

-- 1) 컬럼 추가 (enum 을 STRING 으로 저장하므로 VARCHAR)
--    MySQL 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않으므로 한 번만 실행할 것.
ALTER TABLE quiz ADD COLUMN category VARCHAR(32) NULL;

-- 2) 기존 행 백필: 기사 카테고리로 채운다 (구 데이터에 대한 최선 근사).
--    신규 생성분은 애플리케이션이 출제 슬롯 카테고리를 직접 채우므로 대상 아님.
UPDATE quiz q
JOIN news_article a ON q.article_id = a.id
SET q.category = a.category
WHERE q.category IS NULL;

-- 3) 확인용 (선택): 카테고리별 개수
-- SELECT category, COUNT(*) FROM quiz GROUP BY category;
