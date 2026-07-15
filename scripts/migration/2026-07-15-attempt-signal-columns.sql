-- user_quiz_attempt 신호 컬럼 2종 — 난이도 측정용 암묵/명시 피드백
--
-- first_elapsed_ms: 첫 시도 풀이 소요 시간(ms). 클라이언트가 포그라운드 시간만 측정해 전송.
--   이상치(방치)는 저장 시 자르지 않고 원본 보존 — 집계 시점에 절단한다(LEAST 캡).
-- feedback: INT 사용 — TINYINT 는 Hibernate validate 가 Integer 필드와 불일치 판정
--   (2026-07-15 배포 실패 사고, trial_quiz @Lob 건과 동일 클래스). 1=좋아요, -1=별로예요, NULL=미응답. 해설 화면의 선택적 1탭 입력.
--
-- 멱등: prepare-server.sh 가 col_exists 가드로 1회만 실행. 롤백: DROP COLUMN 2건.
ALTER TABLE user_quiz_attempt
    ADD COLUMN first_elapsed_ms INT NULL AFTER first_selected_choice_id,
    ADD COLUMN feedback INT NULL AFTER first_elapsed_ms;
