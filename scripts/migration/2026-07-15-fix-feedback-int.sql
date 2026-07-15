-- feedback TINYINT → INT 보정
-- 2026-07-15 배포 실패: Hibernate validate 가 Integer 필드에 TINYINT 불일치 판정.
-- 컬럼은 방금 추가돼 전부 NULL 이므로 MODIFY 안전. 멱등: prepare-server.sh 타입 가드.
ALTER TABLE user_quiz_attempt MODIFY feedback INT NULL;
