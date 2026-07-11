-- trial_quiz.model — 생성 모델 A/B 비교용 오버라이드 기록 (null = 운영 기본 모델)
-- 멱등: prepare-server.sh 가 col_exists 가드로 1회만 실행. 롤백: DROP COLUMN model;
ALTER TABLE trial_quiz ADD COLUMN model VARCHAR(64) NULL AFTER extra_verify_rules;
