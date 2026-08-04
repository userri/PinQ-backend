-- 하루 복습 상한을 "한 번에 5개"가 아니라 "하루 5개"로 만들기 위한 컬럼.
-- prod 는 spring.jpa.hibernate.ddl-auto=validate 이므로 배포 '전에' 실행해야 한다.
-- (컬럼이 없으면 애플리케이션이 기동 시 검증 실패로 뜨지 않는다.)
--
-- NULL 허용: 아직 한 번도 복습하지 않은 항목을 뜻한다. 기존 행은 전부 NULL 로 시작하며,
-- 오늘 이미 푼 항목이 있었다면 하루치 몫이 한 번은 넉넉하게 계산될 수 있으나 1회성이다.
ALTER TABLE review_item ADD COLUMN last_reviewed_on DATE NULL;
