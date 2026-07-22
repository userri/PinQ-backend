---
name: quiz-audit
description: Use when asked to audit today's published quizzes — "오늘 퀴즈 검수", "일일 퀴즈 검수", "/quiz-audit", "발행분 품질 확인", or any daily quality check of the PinQ quiz pipeline output.
---

# 일일 퀴즈 검수 (Daily Quiz Audit)

## Overview

매일 발행분(정상 = 카테고리 5문항)을 전수 검수하고 `docs/quality-audit-log.md`에 기록하는 루틴.
판정 기준·기록 형식의 원천(SSOT)은 **audit 로그 상단의 "판정 기준" 섹션과 기존 일자별 항목** — 이 스킬은 절차 안내이며, 판정 전 반드시 로그 원문과 대조한다.

## 절차

1. **로그 원문 확인**: `docs/quality-audit-log.md` 전체(특히 상단 판정 기준 + 최근 2~3개 항목)를 읽는다. 추적 중인 관찰 항목과 형식을 파악.
2. **오늘 발행분 조회**: 아래 고정 SQL을 사용자가 프로덕션 서버에서 실행하도록 안내하고 출력을 받는다 (Claude가 서버에 직접 접속하지 않음).
3. **전수 판정**: 문항별로 질문·해설·keyword·선지 4지를 기준별로 판정.
4. **기록 append**: 기존 형식 그대로 로그에 항목 추가.
5. **커밋 + push**: docs 전용 커밋은 CI가 스킵되므로 바로 push 가능.

## 고정 SQL (서버에서 사용자가 실행 — 시크릿 비노출)

```bash
cd ~/pinq_backend && set -a && . ./.env && set +a
docker exec mysql-container mysql --default-character-set=utf8mb4 -u"$DB_USERNAME" -p"$DB_PASSWORD" -D"$DB_NAME" -e "SELECT q.id, q.category, q.question, q.explanation, q.keyword FROM quiz q WHERE q.quiz_date=CURDATE()\G SELECT c.quiz_id, c.order_num, c.content, c.is_answer FROM choice c JOIN quiz q ON q.id=c.quiz_id WHERE q.quiz_date=CURDATE() ORDER BY c.quiz_id, c.order_num;"
```

스키마 주의 — 추측 금지:
- 선지 테이블은 `choice` (~~quiz_choice~~), 정답 컬럼은 `is_answer` (~~is_correct~~), 순서는 `order_num`
- 발행일은 `quiz_date` (~~created_at~~), 오늘분 = `quiz_date=CURDATE()`
- `--default-character-set=utf8mb4`는 SSH 터미널 한글 깨짐 가드 — 생략 금지
- `.env` 로드는 `set -a && . ./.env && set +a` 패턴 (사고 이력: source 셸의 잔존 export가 배포를 덮은 적 있음 — 이 셸에서 `docker compose up` 금지, 로그 7/21 사고 1 참조)

## 판정 기준 (로그 상단 원문과 대조 후 적용)

- **치명 결함**: 복수 정답(참인 오답 포함), 전제-정답 내적 모순, 정답 방향 오류, 자립성 위반(기사 없이 성립 불가)
- **경계**: 오답이 논쟁적/부분 참 소지가 있으나 질문 정합 기준으로 정답이 유일 최선
- **관찰**: 결함 아니나 반복 시 조치할 패턴 (소재 중복, 필터 구멍 등)

임의로 다른 등급 체계(major/minor 등)를 만들지 않는다.

## 누적 추적 체크리스트 (매회 확인)

- [ ] 발행 수 5 미만이면 원인 추적 (docker logs, 네이버 API, SKIP 사유 — 급변동일 기사 풀 퇴화 패턴 이력 있음)
- [ ] 당일 크로스 카테고리 소재(사건·원인·지표) 겹침 여부
- [ ] 같은 소재 재등장 간격 — 특히 **레버리지 ETF 3회 재등장 추적 중** (7/15 · 7/19 · 7/21); 재등장 시 이력 주입 포맷 개선 실험 트리거
- [ ] 해설 정합 누수 (검증 기준 4) — 반복 시 기준 강화 실험 트리거 (7/20 id 325 선례)
- [ ] keyword "용어: 정의" 형식 준수 (나열형이면 룰베이스 누수)
- [ ] 정답률 대시보드 밴드(55~75%) 이탈 여부 (로그 최근 항목의 추적 문맥 참조)

## 기록 형식

로그 기존 항목 형식을 그대로 따른다:

```markdown
## YYYY-MM-DD — 발행분 (id NNN~NNN, 5/5) : 치명 N / 경계 N / 5 ✅ (N일 연속 치명 0)
- 우수 문항 요약 (id + 한 줄)
- 경계: id + 근거 (정답 유일성 유지 여부 명시)
- 관찰: 추적 항목 갱신 (연속 기록·재등장 카운트 포함)
```

- 연속 치명 0 기록은 직전 항목에서 이어 센다
- 표 형식·PASS/FAIL 등급표로 바꾸지 않는다 — 기존 산문형 유지

## 원칙

**룰 추가는 단발 사례로 하지 않는다.** 결함·패턴이 반복 확인될 때만 워크벤치 실험(trial_quiz)을 거쳐 채택한다 (확립된 운영 원칙 — 실험 #9 보류, #10 채택 선례).

## Common Mistakes

| 실수 | 교정 |
|---|---|
| 테이블/컬럼명 추측 (`quiz_choice`, `is_correct`) | 고정 SQL 그대로 사용 |
| 자체 등급 체계 발명 (major/minor) | 치명/경계/관찰 3단계만 |
| 표 형식으로 로그 기록 | 기존 산문형 항목 형식 준수 |
| 단발 결함에 즉시 룰 추가 | 반복 확인 → 실험 → 채택 |
| Claude가 직접 서버 SQL 실행 시도 | SQL을 사용자에게 제시하고 출력을 받는다 |
| docs 커밋 후 push 보류 | docs 전용 push는 CI 스킵 — 바로 push |
