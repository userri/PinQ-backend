---
name: quiz-audit
description: Use when asked to audit today's published quizzes — "오늘 퀴즈 검수", "일일 퀴즈 검수", "/quiz-audit", "발행분 품질 확인", or any daily quality check of the PinQ quiz pipeline output.
---

# 일일 퀴즈 검수 (Daily Quiz Audit)

## Overview

매일 발행분(정상 = 카테고리 5문항)을 전수 검수하고 `docs/quality-audit-log.md`에 기록하는 루틴.
판정 기준·기록 형식의 원천(SSOT)은 **audit 로그 상단의 "판정 기준" 섹션과 기존 일자별 항목** — 이 스킬은 절차 안내이며, 판정 전 반드시 로그 원문과 대조한다.

## 절차

1. **로그 부분 읽기 (전량 금지)**: `docs/quality-audit-log.md`의 **상단 판정 기준 섹션 + 최근 2~3개 일자 항목만** 읽는다 (Read offset/limit 또는 head+tail — 파일이 매일 자라므로 전량 읽기는 낭비). 추적 중인 관찰 카운트와 형식 파악이 목적.
2. **오늘 발행분 조회**: Claude가 SSH로 프로덕션 서버에 직접 접속해 아래 명령을 실행하고 출력을 받는다.
3. **전수 판정**: 문항별로 질문·해설·keyword·선지 4지를 기준별로 판정.
4. **기록 append**: 기존 형식 그대로 로그에 항목 추가.
5. **커밋 + push (항상 main, worktree 금지)**: 검수는 코드 변경이 없는 로그 append 이므로 **worktree 를 쓰지 말고 메인 레포(`/Users/iyr/SSAFY/PinQ-backend`, main 체크아웃)에서 직접 커밋·push** 한다 — cherry-pick 단계가 없어야 충돌이 원천 차단된다 (2026-07-23 스킬 파일 충돌 선례). `.md` 전용 push 는 CI 스킵. 만에 하나 worktree 세션이라면 [[quiz-audit-merge-to-main]] 절차로 폴백.

## 고정 조회 명령 (Claude가 SSH로 직접 실행)

**접속 정보는 레포에 커밋하지 않는다** — 프로덕션 IP·SSH 키 경로는 이 파일에 하드코딩하지 말 것(공개 레포 노출 방지). 셸 환경변수 `PINQ_SSH_KEY`(키 경로)·`PINQ_SSH_HOST`(`ubuntu@<ip>`)로 참조하며, 실제 값은 로컬 전용 설정(gitignore된 `~/.pinq-ops.env` 등)에서 `source` 한다.

```bash
# 로컬 전용 설정에서 접속 정보 로드 (레포에 없음)
[ -f ~/.pinq-ops.env ] && . ~/.pinq-ops.env
ssh -i "$PINQ_SSH_KEY" "$PINQ_SSH_HOST" 'cd ~/pinq_backend && set -a && . ./.env && set +a && docker exec mysql-container mysql --default-character-set=utf8mb4 -u"$DB_USERNAME" -p"$DB_PASSWORD" -D"$DB_NAME" -e "SELECT q.id, q.category, q.question, q.explanation, q.keyword FROM quiz q WHERE q.quiz_date=CURDATE()\G SELECT c.quiz_id, c.order_num, c.content, c.is_answer FROM choice c JOIN quiz q ON q.id=c.quiz_id WHERE q.quiz_date=CURDATE() ORDER BY c.quiz_id, c.order_num;"'
```

시크릿 원칙: `.env`의 `DB_PASSWORD` 등은 `set -a && . ./.env && set +a`로 **참조만** 하고 값을 출력에 노출하지 않는다. `mysql: [Warning] Using a password...`는 무해(무시). `PINQ_SSH_*`가 없거나 SSH 권한이 막혀 있으면 사용자에게 위 명령을 서버에서 직접 실행하도록 안내하고 출력을 받는 방식으로 폴백.

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

각 항목의 **현재 카운트·트리거 상태는 로그 최근 항목이 SSOT** — 이 스킬 파일에 날짜·횟수를 갱신해 적지 않는다 (스킬 커밋 반복 = 충돌 표면적).

- [ ] 발행 수 5 미만이면 원인 추적 (docker logs, 네이버 API, SKIP 사유 — 급변동일 기사 풀 퇴화 패턴 이력 있음)
- [ ] 당일 크로스 카테고리 소재(사건·원인·지표) 겹침 여부
- [ ] 같은 소재 재등장 간격 (예: 레버리지 ETF) — 재등장 시 이력 주입 포맷 개선 실험 트리거
- [ ] 해설 정합 누수 (검증 기준 4) — 명백 사례 반복 시 기준 강화 실험 트리거
- [ ] keyword "용어: 정의" 형식 준수 (나열형·주제 서술형이면 룰베이스 누수)
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
| 접속 정보(IP·키 경로)를 스킬/레포에 하드코딩 | `~/.pinq-ops.env`의 `PINQ_SSH_*` 참조 |
| docs 커밋 후 push 보류 | docs 전용 push는 CI 스킵 — 바로 push |
