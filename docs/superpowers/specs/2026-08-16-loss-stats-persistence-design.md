# 손실 집계 영속화 — 설계 (2026-08-16)

퀴즈 생성 시도의 탈락 기록을 메모리 링버퍼에서 DB 로 옮긴다.

## 왜

발행 5개 뒤에는 탈락한 기사 수십 건이 있다(8/12 환율 슬롯: 53회 시도). 그 탈락 기록이
파이프라인 개선의 유일한 근거인데, **지금은 `AuditLogBuffer`(메모리 링버퍼)에만 있다.**
서버 재시작이면 통째로, 용량을 넘기면 오래된 것부터 조용히 사라진다. 밀려나는 쪽이
정기 06:04 회차라 "재시도가 줄었다"로 오독하기도 쉽다.

따라서 **사람이 그날 안에 떠야 남는다.** 2026-08-15 에 검수가 하루 밀렸고 그날 데이터는
영구 결손이다(`docs/data/scrape-stats.jsonl` 에 8/15 행 없음 — 0 으로 채우지 않았다).
품질 판정은 뒤늦게라도 되지만 손실 집계는 복구가 안 된다.

이 데이터에 걸린 미결 항목이 둘이다. 표본에 구멍이 나면 둘 다 판단이 늦어진다.

- **개념 포화 대응** — 1차 원인이 기사 풀 오염임을 계측으로 확인하는 중
- **후보 경쟁 수 ↔ 문항 품질 상관** — 8/14 가설, 8/16 2일차로 방향 일치

승격 조건은 이미 충족됐다. `docs/PENDING.md` 의 "손실 집계 영속화" 항목이 조건 ①(관측
기간 중 실제 결손일 발생)을 걸어 뒀고, 8/15 가 그 날이다.

## 무엇이 바뀌나

계측이 **시각과 사람에게서 분리된다.** 검수가 며칠 밀려도 데이터는 남는다.

부수적으로 **사유가 추측이 아니라 사실이 된다.** 현행 `scripts/scrape-stats.py` 는 로그
문자열을 사후에 분류한다(`"카테고리" in chunk` 류). 문구가 바뀌면 조용히 오분류되고,
분류 순서를 바꾸면 과거 행과 비교가 깨진다. 판정이 일어난 자리에서 값을 박으면 이 층이
통째로 사라진다.

## 결정 사항

| 축 | 결정 | 기각한 대안과 이유 |
|---|---|---|
| 행 단위 | **시도 1건 = 1행** | 날짜×카테고리×사유 집계 행 — 개별 기사 제목이 사라진다. 8/16 에 EXCHANGE_RATE 후보로 교보문고 베스트셀러·이치방쿠지 체험기가 들어온 것을 제목으로 확인한 관측이 불가능해지고, 분류 기준을 바꿨을 때 재집계도 못 한다 |
| 사유 표현 | **`stage` + `reason` 2컬럼** | 단일 컬럼에 `verify_off_category` 처럼 단계를 이름에 녹이기 — 단계별 합을 보려면 문자열 prefix 를 잘라야 해서, 지금 로그 파싱이 겪는 취약성을 규모만 줄여 재현한다 |
| 판정 등급 | **범위 밖** | 함께 구현 — admin 경로에 처음으로 쓰기 엔드포인트가 생기고 검수 절차까지 바뀌어 범위가 두 배. 발행 행에 `quiz_id` 가 박히므로 나중에 붙여도 과거분까지 소급된다(퀴즈는 DB 에 영구 보관) |
| 기존 jsonl | **동결, 이후는 DB** | 백필 — 집계 행을 시도 행으로 되돌릴 수 없고 사유 분류 기준도 달라, 모양만 같고 뜻이 다른 행이 섞인다 |
| 쓰기 방식 | **판정 지점에서 즉시 1건 저장** | 사이클 끝 배치 — 중간에 죽으면 그 회차가 통째로 날아간다. 링버퍼를 벗어나려는 작업이 "중간에 죽으면 유실"을 다시 들이는 건 자기모순 |

## 스키마

`quiz_generation_attempt`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK AI | |
| `occurred_at` | DATETIME(6) NOT NULL | |
| `occurred_on` | DATE NOT NULL | 인덱스용 별도 컬럼 — `occurred_at` 에 `DATE()` 를 씌우면 인덱스를 못 탄다(`token_usage` 와 같은 판단) |
| `category` | VARCHAR(32) NOT NULL | |
| `run_window` | VARCHAR(16) NOT NULL | `REGULAR` / `BACKFILL` |
| `search_keyword` | VARCHAR(64) NULL | 이 기사를 물어온 검색어. 검색 질의 축 개선의 근거가 된다 |
| `article_title` | VARCHAR(512) NULL | 기사 풀 오염을 눈으로 보는 축 |
| `article_url` | VARCHAR(512) NULL | |
| `stage` | VARCHAR(16) NOT NULL | 아래 표 |
| `reason` | VARCHAR(32) NULL | `stage=PUBLISHED` 면 NULL |
| `detail` | VARCHAR(255) NULL | 룰베이스 사유 원문, LLM skipReason 등. 255자 초과 시 자른다 |
| `quiz_id` | BIGINT NULL | 발행된 경우만. **판정 등급 소급 결합의 열쇠** |

인덱스: `KEY idx_attempt_day_category (occurred_on, category)` 하나. 유일한 읽기 패턴이
"최근 N일을 날짜×슬롯으로 묶기"다. FK 는 걸지 않는다 — 계측 테이블이 본 데이터의
삭제·수정을 막으면 안 된다.

`run_window` 는 **호출부가 명시적으로 넘긴다.** 현행 스크립트는 시각으로 추측하는데
(`hhmm < "06:10"`), 정기 회차가 늦어지면 그대로 오분류된다. 정기 생성과 백필 가드는
진입점이 다르므로 자기가 어느 쪽인지 알고 있다.

## stage × reason

| stage | reason | 현행 대비 |
|---|---|---|
| `PREFILTER` | `EDITORIAL` · `CROSS_CATEGORY_USED` · `EMPTY_CONTENT` | 문자열 매칭 → 정확해짐 |
| `GENERATE` | `LLM_SKIP` · `PARSE_FAILED` · `API_ERROR` | 셋 다 구분 불가였다 → 분리됨 |
| `VALIDATE` | `RULE_REJECTED` (+ `detail`) · `INVALID_RESPONSE` · `TERM_EQUALS_CATEGORY` · `TERM_REUSE_GUARD` · `LEXICAL_DUPLICATE` | 버려지던 `QuizRuleValidator.Result.reason()` 이 처음 남는다. 뒤 넷은 **생성 서비스의 저장 전 방어선**이고 현행 집계에서는 `duplicate`/`other` 로 섞여 들어간다 |
| `VERIFY` | `VERIFY_FAILED` | **정밀해지지 않는다 — 아래 한계** |
| `PUBLISHED` | NULL, `quiz_id` 채움 | 회차 요약 줄에서 세던 것을 행으로 |

### ⚠️ 한계 — 수동 재실행 날은 `PUBLISHED` 행이 중복되고 일부가 유령을 가리킨다

`generateTodayQuizzes()` 는 오늘 퀴즈를 지우고 다시 만든다. `PUBLISHED` 계측 행은 별도
트랜잭션으로 이미 커밋돼 있고 FK 도 없어 그 삭제에 함께 지워지지 않는다. 그래서 같은
날 수동 재실행이 있었다면 `PUBLISHED` 행이 두 벌 남고, 먼저 커밋된 쪽의 `quiz_id` 는
삭제된 퀴즈를 가리키는 유령 참조가 된다. "분모를 `PUBLISHED` 로 센다"는 집계 규칙이
그런 날에는 실제 발행 수보다 부풀어 나온다 — 집계를 읽는 쪽이 `quiz_id` 존재 여부로
걸러야 한다.

### ⚠️ 한계 — 기준 16 분리는 절반만 풀린다

`verifyAnswer` 는 boolean 만 돌려준다. 검증기가 기준 16(카테고리 이탈)으로 깠는지 복수
정답으로 깠는지는 **응답 형식 자체에 없다.** 알려면 검증 프롬프트의 응답 형식을 바꿔야
하는데, 그 고정부에는 캐시(`cache_control: ephemeral`)가 걸려 있어 별건으로 다룬다.

따라서 이 작업으로 `VERIFY` 단계와 앞 단계는 분리되지만 **`VERIFY` 안의 세부 사유는 여전히
뭉쳐 있다.** PENDING 의 "사유 분해로는 기준 16 의 기여를 분리할 수 없다"는 지적이 절반만
해소된다. 별건은 PENDING 에 등재한다.

## 쓰기 경로

### 반환 타입 변경이 이 작업의 핵심

`OpenAIQuizClient.generateQuiz()` 는 **다섯 가지 실패를 전부 `Optional.empty()` 하나로**
반환한다 — 파싱 실패 / 룰베이스 반려 / Claude 검증 실패 / API 예외 / LLM SKIP. 호출부 로그가
`"기사 건너뜀 (SKIP 또는 생성 실패)"` 로 뭉개진 것은 문구 문제가 아니라 **정보가 거기 없어서**다.
`scrape-stats.py` 가 앞 청크의 딴 줄을 긁어 사유를 추측하는 이유도 이것이다.

반환을 `GenerationOutcome`(성공 시 dto, 실패 시 stage·reason·detail)로 바꾼다.

### 저장은 한 곳에서

사유를 아는 쪽(`OpenAIQuizClient`)과 맥락을 아는 쪽(`QuizGenerationService` — 검색 키워드·
회차 구분을 안다)이 다르다. 그러므로 **사유는 반환값으로 올려보내고 저장은 생성 서비스에서**
한다. 클라이언트가 직접 쓰면 맥락 컬럼이 빈다.

저장 지점 8곳 — 앞 셋은 `PREFILTER`, 가운데 넷은 클라이언트가 올려보낸 사유와 서비스의 저장 전
방어선, 마지막이 `PUBLISHED`:

1. 사설·칼럼 제목 필터
2. 타 카테고리가 이미 쓴 URL
3. 빈 본문(스크래핑·description 둘 다 실패)
4. `generateQuiz()` 실패 — 반환된 stage·reason 을 그대로 기록
5. `isValidQuiz` 불통과
6. keyword 용어 = 카테고리명
7. 용어 재사용 가드 / 렉시컬 유사도
8. 퀴즈 저장 직후 (`PUBLISHED`, `quiz_id`)

### Recorder

`QuizGenerationAttemptRecorder` 는 `TokenUsageRecorder` 를 그대로 본뜬다.

- **예외를 삼킨다** — 계측을 못 남기는 것보다 퀴즈 생성이 멈추는 쪽이 훨씬 나쁘다
- **`REQUIRES_NEW` 로 트랜잭션을 분리한다** — 같은 트랜잭션에 태우면 저장 실패가
  rollback-only 를 찍어 **생성 트랜잭션까지 함께 죽는다.** 삼키기만 해서는 못 막는 유형이라
  전파 설정이 함께 필요하다

둘 다 실제로 겪은 함정이고 선례가 같은 레포에 있다 —
`src/main/java/com/example/pinq_backend/audit/TokenUsageRecorder.java` 의 주석 참조.

## 조회 — 명령어를 외우게 하지 않는다

**사용자가 보는 창구는 기존 집계 아티팩트 한 곳이다.** 북마크를 열면 최신 상태가 보이는 것이
목표이고, CLI 는 검수 세션(에이전트)이 쓰는 내부 경로다. 사용자가 명령을 기억해야 하는
설계는 채택하지 않는다.

- `GET /api/admin/audit/generation-attempts?days=N` — 읽기 전용, 기존 admin 시크릿.
  기본 응답은 **날짜×카테고리×stage×reason 집계**(원시 행은 하루 50~160건이라 그대로 뱉기엔 크다)
- `?date=YYYY-MM-DD&raw=true` — 그날 원시 행(기사 제목 포함). 오염을 눈으로 확인하는 경로
- `pinq-quiz-fetch.sh attempts [일수]` 서브커맨드 추가
- 검수 스킬 6단계의 `logs | scrape-stats.py >> jsonl` 파이프는 **아티팩트 갱신으로 교체**된다.
  파일 append 단계가 사라진다

아티팩트는 **동결된 jsonl 구간과 DB 구간을 이어 붙이되 경계에 선을 긋는다** — 사유 분류
기준이 다르므로 그냥 이으면 안 된다. 8/15 는 점선 결손 행으로 남는다.

## 기존 자산

`scripts/scrape-stats.py` 와 `docs/data/scrape-stats.jsonl` 은 **동결한다. 지우지 않는다.**
7/28~8/16 구간은 로그가 이미 소멸해 **어떤 방법으로도 시도 행 복원이 불가능**하고, jsonl 이
그 기간의 유일한 기록이다.

## 테스트

- `QuizGenerationAttemptRecorder` — 저장 실패가 생성을 죽이지 않는다(예외 주입) /
  바깥 트랜잭션이 함께 죽지 않는다. `TokenUsageRecorder` 테스트 선례를 따른다
- `GenerationOutcome` — 다섯 실패 경로가 각각 제 stage·reason 을 단다
- 발행 성공 행에 `quiz_id` 가 채워진다 (판정 등급 소급 결합의 전제)
- `run_window` 가 시각이 아니라 호출부 인자로 정해진다

## 배포

마이그레이션은 **`scripts/migration/` 에 두고 `scripts/prepare-server.sh` 에 존재 가드와 함께
등록한다.** `docs/migration/` 은 CI 가 보지 않는다 — 거기 뒀다가 8/14 배포가 통째로 실패했다
(`missing table [token_usage]`, prod 는 `ddl-auto=validate`). 규칙은
`docs/db-access-and-migration.md`.

## 안 하는 것 (YAGNI)

- 판정 등급 테이블 — 별건. `quiz_id` 만 남겨 두면 나중에 소급된다
- 검증 응답 형식 변경 — 별건. PENDING 등재
- 과거 jsonl 백필 — 원리적으로 불가
- 재시도(`ALREADY_TRIED`) 행 — `triedUrls` 가드가 이미 그런 시도 자체를 없앴다(8/12)
- 보존 기간·파티셔닝 — 연 4~6만 행이라 의미 없다

## 성공 기준

1. 배포 다음 날 정기 회차 후 `attempts` 가 그날 행을 돌려준다
2. 같은 날 `logs` 의 SKIP 줄 수와 대조해 누락이 없다 (**첫 날 1회만** — 로그가 살아 있는
   마지막 기회다)
3. 검수를 하루 걸러도 전날 데이터가 그대로 있다
4. `VERIFY` 단계와 앞 단계가 SQL 로 분리된다
