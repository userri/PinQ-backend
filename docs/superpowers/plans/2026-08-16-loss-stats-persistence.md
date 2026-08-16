# 손실 집계 영속화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 퀴즈 생성 시도의 탈락 기록을 메모리 링버퍼에서 MySQL 테이블로 옮겨, 검수가 며칠 밀려도 손실 집계가 남게 한다.

**Architecture:** 시도 1건 = 1행(`quiz_generation_attempt`). 탈락 사유는 `stage`(어느 단계) + `reason`(왜) 두 컬럼으로 직교 기록한다. `OpenAIQuizClient.generateQuiz()` 의 반환을 `Optional<GeneratedQuizDto>` 에서 `GenerationOutcome` 로 바꿔 다섯 실패 경로를 구분해 올려보내고, 저장은 맥락(검색어·회차)을 아는 `QuizGenerationService` 한 곳에서 한다. 계측 저장은 `REQUIRES_NEW` + 예외 삼킴으로 본 기능과 격리한다.

**Tech Stack:** Java 17, Spring Boot 4.0.6, Spring Data JPA (Hibernate 7), MySQL 8 (prod) / H2 (test), JUnit 5 + AssertJ + Mockito, Gradle.

**설계 SSOT:** `docs/superpowers/specs/2026-08-16-loss-stats-persistence-design.md`

## Global Constraints

- **prod 는 `ddl-auto=validate`** — 테이블이 없으면 앱이 기동하지 않는다. 마이그레이션 SQL 은 반드시 `scripts/migration/` 에 두고 `scripts/prepare-server.sh` 에 존재 가드와 함께 등록한다. `docs/migration/` 은 CI 가 보지 않는다(2026-08-14 배포 전체 실패 원인).
- **계측이 본 기능을 깨면 안 된다.** 저장 실패는 삼키고, 트랜잭션은 `Propagation.REQUIRES_NEW` 로 분리한다. `generateTodayQuizzes()` 전체가 `@Transactional` 이라 분리하지 않으면 계측 저장 실패가 rollback-only 를 찍어 **퀴즈 생성 트랜잭션까지 죽는다.**
- **커밋 메시지에 AI 흔적(`Co-Authored-By` 등)을 남기지 않는다.**
- 테스트 실행: `./gradlew test`. 현재 213개 통과가 기준선이다.
- 시각은 항상 주입된 `Clock` 을 쓴다 (`LocalDateTime.now(clock)`). `LocalDateTime.now()` 직접 호출 금지 — 테스트가 고정 시각을 넣는다.
- 문자열 컬럼 길이를 넘길 수 있는 값(`detail`, `article_title`, `article_url`)은 **저장 직전에 자른다.** 자르지 않으면 MySQL 이 예외를 던지고, 그 예외는 삼켜져 계측만 조용히 유실된다.

---

### Task 1: 마이그레이션 SQL + CI 등록

**Files:**
- Create: `scripts/migration/2026-08-16-quiz-generation-attempt.sql`
- Modify: `scripts/prepare-server.sh` (마지막 `echo "✅ 서버 준비 완료"` 바로 위)

**Interfaces:**
- Consumes: 없음
- Produces: 테이블 `quiz_generation_attempt` — Task 2 의 엔티티가 이 스키마에 `validate` 로 맞춰진다

- [ ] **Step 1: 마이그레이션 SQL 작성**

`scripts/migration/2026-08-16-quiz-generation-attempt.sql`:

```sql
-- 퀴즈 생성 시도 1건 = 1행 (손실 집계 영속화)
--
-- 왜 필요한가
--   탈락 기록이 AuditLogBuffer(메모리 링버퍼)에만 남아 재시작·용량 초과로 사라졌다.
--   2026-08-15 에 검수가 하루 밀린 사이 그날 기록이 영구 소실됐고, 개념 포화·후보 경쟁
--   두 관측의 표본에 구멍이 났다. 사람이 그날 안에 떠야 남는 구조 자체를 없앤다.
--
-- 배포 순서 (docs/db-access-and-migration.md 규칙)
--   ① 이 스크립트 실행 → ② 테이블 확인 → ③ 새 이미지 배포
--   prod 는 DDL_AUTO=validate 라 테이블이 없으면 앱이 기동하지 않는다.
--   ⚠️ scripts/prepare-server.sh 에 등록해야 CI 가 실행한다. docs/migration/ 은 CI 가 보지 않는다.
--
-- NULL 허용
--   기사 정보(title·url·search_keyword)는 기사를 고르기 전 단계의 행이 있을 수 있어 NULL 허용.
--   reason 은 stage=PUBLISHED 일 때 NULL(탈락이 아니므로).
--   quiz_id 는 발행된 행에만 채운다. FK 를 걸지 않는 이유: 계측 테이블이 본 데이터의
--   삭제(재생성 시 그날 퀴즈 삭제)를 막으면 안 된다.

CREATE TABLE IF NOT EXISTS quiz_generation_attempt (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(6)  NOT NULL,
    occurred_on    DATE         NOT NULL,
    category       VARCHAR(32)  NOT NULL,
    run_window     VARCHAR(16)  NOT NULL,
    search_keyword VARCHAR(64)  NULL,
    article_title  VARCHAR(512) NULL,
    article_url    VARCHAR(512) NULL,
    stage          VARCHAR(16)  NOT NULL,
    reason         VARCHAR(32)  NULL,
    detail         VARCHAR(255) NULL,
    quiz_id        BIGINT       NULL,
    PRIMARY KEY (id),
    -- 유일한 읽기 패턴이 "최근 N일을 날짜×슬롯으로 묶기"다. occurred_at 에 DATE() 를 씌우면
    -- 인덱스를 못 타므로 날짜 컬럼을 따로 두고 여기에 인덱스를 건다(token_usage 와 같은 판단).
    KEY idx_attempt_day_category (occurred_on, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: prepare-server.sh 에 등록**

`echo "✅ 서버 준비 완료"` 바로 위에 삽입:

```bash
# 퀴즈 생성 시도 계측 — CREATE TABLE IF NOT EXISTS 라 SQL 자체가 멱등이나,
# 불필요한 실행을 피하려 존재 가드를 둔다.
if [ "$(table_exists quiz_generation_attempt)" = "0" ]; then
  run_sql scripts/migration/2026-08-16-quiz-generation-attempt.sql
  echo "OK: quiz_generation_attempt 마이그레이션 적용"
else
  echo "SKIP: quiz_generation_attempt 이미 존재"
fi
```

`table_exists` 헬퍼는 이미 파일에 있다(2026-08-16 `b51dcfe` 에서 추가). 다시 정의하지 말 것.

- [ ] **Step 3: 문법 검사**

Run: `bash -n scripts/prepare-server.sh`
Expected: 출력 없이 종료(성공). 오류가 나면 삽입 위치의 따옴표를 확인한다.

- [ ] **Step 4: 커밋**

```bash
git add scripts/migration/2026-08-16-quiz-generation-attempt.sql scripts/prepare-server.sh
git commit -m "feat: 퀴즈 생성 시도 계측 테이블 마이그레이션"
```

---

### Task 2: 엔티티 + 리포지토리 + 롤업 쿼리

**Files:**
- Create: `src/main/java/com/example/pinq_backend/audit/domain/QuizGenerationAttempt.java`
- Create: `src/main/java/com/example/pinq_backend/audit/domain/AttemptStage.java`
- Create: `src/main/java/com/example/pinq_backend/audit/domain/AttemptReason.java`
- Create: `src/main/java/com/example/pinq_backend/audit/repository/QuizGenerationAttemptRepository.java`
- Test: `src/test/java/com/example/pinq_backend/audit/QuizGenerationAttemptRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 의 테이블 스키마
- Produces:
  - `AttemptStage` enum: `PREFILTER, GENERATE, VALIDATE, VERIFY, PUBLISHED`
  - `AttemptReason` enum: `EDITORIAL, CROSS_CATEGORY_USED, EMPTY_CONTENT, LLM_SKIP, PARSE_FAILED, API_ERROR, INVALID_RESPONSE, RULE_REJECTED, TERM_EQUALS_CATEGORY, TERM_REUSE_GUARD, LEXICAL_DUPLICATE, VERIFY_FAILED`
  - 생성자 `QuizGenerationAttempt(LocalDateTime occurredAt, String category, String runWindow, String searchKeyword, String articleTitle, String articleUrl, AttemptStage stage, AttemptReason reason, String detail, Long quizId)`
  - `QuizGenerationAttemptRepository.rollupSince(LocalDate from)` → `List<DailyRow>`
  - `QuizGenerationAttemptRepository.findByOccurredOnOrderByOccurredAtAsc(LocalDate day)` → `List<QuizGenerationAttempt>`
  - `DailyRow` projection: `getDay(), getCategory(), getStage(), getReason(), getAttempts()`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/pinq_backend/audit/QuizGenerationAttemptRepositoryTest.java`:

```java
package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 시도 행이 검수가 쓰는 롤업 모양(날짜×카테고리×stage×reason)으로 읽히는지 고정한다.
 *
 * 이 축이 깨지면 "어디서 걸렸나"와 "왜"의 분리가 무너져, 이 작업의 목적인
 * 기준 16 기여 분리가 불가능해진다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizGenerationAttemptRepositoryTest {

    @Autowired
    private QuizGenerationAttemptRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 날짜_카테고리_단계_사유로_롤업된다() {
        repository.deleteAll();
        LocalDateTime now = LocalDateTime.now(clock);

        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "교보문고 베스트셀러", "https://example.com/1",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "이치방쿠지 체험기", "https://example.com/2",
                AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null, null));
        repository.save(new QuizGenerationAttempt(now, "EXCHANGE_RATE", "REGULAR",
                "환율", "엔화 방어 국채 매각", "https://example.com/3",
                AttemptStage.PUBLISHED, null, null, 463L));

        List<QuizGenerationAttemptRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        assertThat(rows).hasSize(2);

        var failed = rows.stream()
                .filter(r -> r.getStage().equals("VERIFY"))
                .findFirst().orElseThrow();
        assertThat(failed.getReason()).isEqualTo("VERIFY_FAILED");
        assertThat(failed.getAttempts()).isEqualTo(2);

        var published = rows.stream()
                .filter(r -> r.getStage().equals("PUBLISHED"))
                .findFirst().orElseThrow();
        assertThat(published.getAttempts()).isEqualTo(1);
        // 발행 행은 탈락이 아니므로 reason 이 없다 — 분모를 이 행으로 센다.
        assertThat(published.getReason()).isNull();
    }

    @Test
    void 하루치_원시_행을_시각순으로_돌려준다() {
        repository.deleteAll();
        LocalDate today = LocalDate.now(clock);
        LocalDateTime base = today.atTime(6, 0);

        repository.save(new QuizGenerationAttempt(base.plusMinutes(2), "STOCK", "REGULAR",
                "주식", "나중 기사", "https://example.com/b",
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null));
        repository.save(new QuizGenerationAttempt(base, "STOCK", "REGULAR",
                "주식", "먼저 기사", "https://example.com/a",
                AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null));

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(today);

        // 기사 제목을 눈으로 보는 경로다 — 순서가 흐트러지면 회차 흐름을 못 읽는다.
        assertThat(rows).extracting(QuizGenerationAttempt::getArticleTitle)
                .containsExactly("먼저 기사", "나중 기사");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*QuizGenerationAttemptRepositoryTest*'`
Expected: 컴파일 실패 — `QuizGenerationAttempt`, `AttemptStage`, `AttemptReason`, `QuizGenerationAttemptRepository` 를 찾을 수 없음.

- [ ] **Step 3: enum 두 개 작성**

`AttemptStage.java`:

```java
package com.example.pinq_backend.audit.domain;

/**
 * 시도가 어느 단계에서 끝났는가.
 *
 * "왜"({@link AttemptReason})와 직교하게 둔다. 한 값에 뭉치면
 * (예: {@code verify_off_category}) 단계별 합을 보려고 문자열을 자르게 되고,
 * 그것이 종전 로그 파싱이 겪던 취약성이다.
 */
public enum AttemptStage {
    /** 기사를 LLM 에 넘기기 전 룰베이스 필터 */
    PREFILTER,
    /** 생성 LLM 호출 (SKIP 판정·파싱 실패·API 오류) */
    GENERATE,
    /** 저장 전 방어선 (룰베이스 검증 + 용어·유사도 가드) */
    VALIDATE,
    /** cross-model 검증 (Claude) */
    VERIFY,
    /** 발행 성공 — 탈락이 아니다. 분모를 이 값으로 센다 */
    PUBLISHED
}
```

`AttemptReason.java`:

```java
package com.example.pinq_backend.audit.domain;

/**
 * 시도가 끝난 사유. {@link AttemptStage} 와 짝을 이룬다.
 *
 * 발행({@code PUBLISHED})은 사유가 없다 — null 로 둔다.
 */
public enum AttemptReason {
    /** 사설·칼럼 제목 룰 */
    EDITORIAL,
    /** 이번 사이클의 다른 카테고리가 이미 쓴 기사 */
    CROSS_CATEGORY_USED,
    /** 본문 스크래핑과 description 폴백이 모두 비었다 */
    EMPTY_CONTENT,
    /** 생성 LLM 이 "이 기사로는 못 만든다"고 판정 (skipReason 은 detail 에) */
    LLM_SKIP,
    /** 응답 JSON 파싱 실패 */
    PARSE_FAILED,
    /** API 호출 자체가 예외 */
    API_ERROR,
    /** 필수 필드 누락 등 응답 형태 불량 */
    INVALID_RESPONSE,
    /** 룰베이스 검증 반려 (사유 원문은 detail 에) */
    RULE_REJECTED,
    /** keyword 용어가 카테고리 표시명과 글자까지 같다 */
    TERM_EQUALS_CATEGORY,
    /** 최근 N일 내 같은 keyword 용어 재출제 */
    TERM_REUSE_GUARD,
    /** 최근 이력과 렉시컬 유사 */
    LEXICAL_DUPLICATE,
    /**
     * cross-model 검증 반려.
     *
     * ⚠️ 세부 사유(기준 16 인지 복수 정답인지)는 <b>알 수 없다</b> — verifyAnswer 가
     * boolean 만 돌려주기 때문이다. 응답 형식 변경은 캐시된 고정부를 건드리는 일이라
     * 별건으로 분리했다(docs/PENDING.md).
     */
    VERIFY_FAILED
}
```

- [ ] **Step 4: 엔티티 작성**

`QuizGenerationAttempt.java`:

```java
package com.example.pinq_backend.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 퀴즈 생성 시도 1건 — 영속 계측.
 *
 * 발행 5개 뒤에는 탈락한 기사 수십 건이 있다(2026-08-12 환율 슬롯: 53회 시도).
 * 그 기록이 AuditLogBuffer(메모리 링버퍼)에만 있어 재시작·용량 초과로 사라졌고,
 * 8/15 에 검수가 하루 밀린 사이 그날치가 영구 소실됐다. 사람이 그날 안에 떠야
 * 남는 구조를 없애려고 테이블로 옮긴다.
 *
 * 사용자 노출 없음. 서비스 로직 어디서도 읽지 않으며 admin 조회 API 만 읽는다.
 *
 * <p>{@code occurredOn} 을 따로 두는 이유는 {@code TokenUsage} 와 같다 —
 * {@code DATE(occurred_at)} 로 묶으면 인덱스를 못 탄다.
 *
 * <p>{@code quizId} 는 발행 행에만 채운다. 검수 판정 등급(치명/경계/우수)을 나중에
 * 붙일 때 <b>이 컬럼이 결합의 열쇠</b>다 — 퀴즈는 DB 에 영구 보관되므로 과거분까지
 * 소급된다. FK 는 걸지 않는다: 재생성 시 그날 퀴즈를 지우는데 계측이 그걸 막으면 안 된다.
 */
@Entity
@Table(name = "quiz_generation_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizGenerationAttempt {

    /** 문자열 컬럼 상한 — 넘기면 저장이 예외로 죽고, 그 예외는 삼켜져 계측만 조용히 사라진다 */
    private static final int DETAIL_MAX = 255;
    private static final int TEXT_MAX = 512;
    private static final int KEYWORD_MAX = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 집계 키 — occurredAt 의 날짜 부분 */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    /** 출제 슬롯. 기사의 카테고리가 아니라 '어느 슬롯을 채우려던 시도인가' */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    /**
     * REGULAR | BACKFILL.
     *
     * 호출부가 명시적으로 넘긴다 — 시각으로 추측하면(종전 스크립트의 {@code hhmm < "06:10"})
     * 정기 회차가 늦어질 때 그대로 오분류된다.
     */
    @Column(name = "run_window", nullable = false, length = 16)
    private String runWindow;

    /** 이 기사를 물어온 검색어 — 검색 질의 축 개선의 근거 */
    @Column(name = "search_keyword", length = KEYWORD_MAX)
    private String searchKeyword;

    /** 기사 풀 오염을 눈으로 보는 축 (8/16 EXCHANGE_RATE 후보의 교보문고·이치방쿠지) */
    @Column(name = "article_title", length = TEXT_MAX)
    private String articleTitle;

    @Column(name = "article_url", length = TEXT_MAX)
    private String articleUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private AttemptStage stage;

    /** 발행 행은 null */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private AttemptReason reason;

    /** 룰베이스 사유 원문, LLM skipReason 등 */
    @Column(name = "detail", length = DETAIL_MAX)
    private String detail;

    /** 발행된 경우만 — 판정 등급 소급 결합의 열쇠 */
    @Column(name = "quiz_id")
    private Long quizId;

    public QuizGenerationAttempt(LocalDateTime occurredAt, String category, String runWindow,
                                 String searchKeyword, String articleTitle, String articleUrl,
                                 AttemptStage stage, AttemptReason reason, String detail,
                                 Long quizId) {
        this.occurredAt = occurredAt;
        this.occurredOn = occurredAt.toLocalDate();
        this.category = category;
        this.runWindow = runWindow;
        this.searchKeyword = truncate(searchKeyword, KEYWORD_MAX);
        this.articleTitle = truncate(articleTitle, TEXT_MAX);
        this.articleUrl = truncate(articleUrl, TEXT_MAX);
        this.stage = stage;
        this.reason = reason;
        this.detail = truncate(detail, DETAIL_MAX);
        this.quizId = quizId;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
```

- [ ] **Step 5: 리포지토리 작성**

`QuizGenerationAttemptRepository.java`:

```java
package com.example.pinq_backend.audit.repository;

import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizGenerationAttemptRepository
        extends JpaRepository<QuizGenerationAttempt, Long> {

    /**
     * 날짜 × 카테고리 × stage × reason 롤업 — 검수 회차의 조회 패턴 그대로다.
     *
     * stage 와 reason 을 따로 묶는 것이 핵심이다. 한 축으로 뭉치면
     * "VERIFY 단계 손실이 전체의 몇 %인가"를 문자열 조작 없이는 못 센다.
     */
    @Query("""
            select a.occurredOn as day, a.category as category,
                   a.stage as stage, a.reason as reason, count(a) as attempts
            from QuizGenerationAttempt a
            where a.occurredOn >= :from
            group by a.occurredOn, a.category, a.stage, a.reason
            order by a.occurredOn asc, a.category asc, a.stage asc
            """)
    List<DailyRow> rollupSince(@Param("from") LocalDate from);

    /** 하루치 원시 행 — 기사 제목으로 기사 풀 오염을 눈으로 확인하는 경로 */
    List<QuizGenerationAttempt> findByOccurredOnOrderByOccurredAtAsc(LocalDate day);

    interface DailyRow {
        LocalDate getDay();
        String getCategory();
        String getStage();
        String getReason();
        long getAttempts();
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests '*QuizGenerationAttemptRepositoryTest*'`
Expected: 2개 테스트 PASS.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/pinq_backend/audit/domain/ \
        src/main/java/com/example/pinq_backend/audit/repository/QuizGenerationAttemptRepository.java \
        src/test/java/com/example/pinq_backend/audit/QuizGenerationAttemptRepositoryTest.java
git commit -m "feat: 생성 시도 계측 엔티티 — stage/reason 2축 롤업"
```

---

### Task 3: Recorder — 계측이 본 기능을 죽이지 않는다

**Files:**
- Create: `src/main/java/com/example/pinq_backend/audit/QuizGenerationAttemptRecorder.java`
- Test: `src/test/java/com/example/pinq_backend/audit/QuizGenerationAttemptRecorderTest.java`

**Interfaces:**
- Consumes: Task 2 의 `QuizGenerationAttempt`, `AttemptStage`, `AttemptReason`, `QuizGenerationAttemptRepository`
- Produces:
  - `QuizGenerationAttemptRecorder.record(String category, String runWindow, String searchKeyword, String articleTitle, String articleUrl, AttemptStage stage, AttemptReason reason, String detail, Long quizId)` — void, 예외를 던지지 않는다

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/pinq_backend/audit/QuizGenerationAttemptRecorderTest.java`:

```java
package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 계측 저장이 본 기능(퀴즈 생성)을 깨지 않는지 고정한다.
 *
 * 이 테스트가 지키는 것은 데이터가 아니라 <b>가용성</b>이다 — 계측을 못 남기는 것보다
 * 퀴즈 생성이 멈추는 쪽이 훨씬 나쁘다. TokenUsageRecorder 와 같은 계약이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizGenerationAttemptRecorderTest {

    @Autowired
    private QuizGenerationAttemptRecorder recorder;

    @MockitoSpyBean
    private QuizGenerationAttemptRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 시도_한_건이_저장된다() {
        repository.deleteAll();

        recorder.record("INFLATION", "REGULAR", "물가", "8월 소비자물가 발표",
                "https://example.com/x", AttemptStage.VALIDATE,
                AttemptReason.TERM_REUSE_GUARD, "term=기준금리", null);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        assertThat(rows).hasSize(1);
        QuizGenerationAttempt row = rows.get(0);
        assertThat(row.getCategory()).isEqualTo("INFLATION");
        assertThat(row.getRunWindow()).isEqualTo("REGULAR");
        assertThat(row.getStage()).isEqualTo(AttemptStage.VALIDATE);
        assertThat(row.getReason()).isEqualTo(AttemptReason.TERM_REUSE_GUARD);
        assertThat(row.getDetail()).isEqualTo("term=기준금리");
    }

    @Test
    void 저장이_터져도_예외가_호출부로_새지_않는다() {
        doThrow(new RuntimeException("DB down"))
                .when(repository).save(any(QuizGenerationAttempt.class));

        // 여기서 예외가 새면 퀴즈 생성 루프가 통째로 죽는다.
        assertThatCode(() -> recorder.record("STOCK", "REGULAR", "주식", "제목",
                "https://example.com/y", AttemptStage.GENERATE,
                AttemptReason.API_ERROR, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void 상한을_넘는_문자열은_잘라서_저장한다() {
        repository.deleteAll();
        String longDetail = "가".repeat(400);

        // 자르지 않으면 MySQL 이 예외를 던지고, 그 예외는 삼켜져 계측만 조용히 사라진다.
        recorder.record("STOCK", "REGULAR", "주식", "제목", "https://example.com/z",
                AttemptStage.VALIDATE, AttemptReason.RULE_REJECTED, longDetail, null);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDetail()).hasSize(255);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*QuizGenerationAttemptRecorderTest*'`
Expected: 컴파일 실패 — `QuizGenerationAttemptRecorder` 를 찾을 수 없음.

- [ ] **Step 3: Recorder 구현**

`QuizGenerationAttemptRecorder.java`:

```java
package com.example.pinq_backend.audit;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 생성 시도 1건을 남긴다.
 *
 * 두 가지를 지킨다 — {@code TokenUsageRecorder} 와 같은 계약이다:
 *  ① <b>계측이 본 기능을 깨지 않는다.</b> 저장 실패는 잡아서 삼킨다.
 *  ② <b>바깥 트랜잭션을 오염시키지 않는다.</b> REQUIRES_NEW 로 분리한다.
 *     {@code generateTodayQuizzes()} 전체가 @Transactional 이라, 같은 트랜잭션에 태우면
 *     계측 저장 실패가 rollback-only 를 찍어 <b>그날 퀴즈 생성이 통째로 롤백된다.</b>
 *     삼키기만 해서는 막을 수 없는 유형이라 전파 설정이 함께 필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizGenerationAttemptRecorder {

    private final QuizGenerationAttemptRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String category, String runWindow, String searchKeyword,
                       String articleTitle, String articleUrl,
                       AttemptStage stage, AttemptReason reason, String detail, Long quizId) {
        try {
            repository.save(new QuizGenerationAttempt(
                    LocalDateTime.now(clock), category, runWindow,
                    searchKeyword, articleTitle, articleUrl, stage, reason, detail, quizId));
        } catch (Exception e) {
            log.warn("생성 시도 계측 저장 실패 — 계측만 유실된다. category={}, stage={}, error={}",
                    category, stage, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*QuizGenerationAttemptRecorderTest*'`
Expected: 3개 테스트 PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/pinq_backend/audit/QuizGenerationAttemptRecorder.java \
        src/test/java/com/example/pinq_backend/audit/QuizGenerationAttemptRecorderTest.java
git commit -m "feat: 생성 시도 Recorder — REQUIRES_NEW + 예외 삼킴"
```

---

### Task 4: `GenerationOutcome` — 다섯 실패를 구분해 올려보낸다

**Files:**
- Create: `src/main/java/com/example/pinq_backend/news/client/GenerationOutcome.java`
- Modify: `src/main/java/com/example/pinq_backend/news/client/OpenAIQuizClient.java` (`generateQuiz` 및 `parseQuiz`)
- Test: `src/test/java/com/example/pinq_backend/news/client/GenerationOutcomeTest.java`

**Interfaces:**
- Consumes: Task 2 의 `AttemptStage`, `AttemptReason`
- Produces:
  - `GenerationOutcome` record: `GeneratedQuizDto quiz`, `AttemptStage stage`, `AttemptReason reason`, `String detail`
  - 팩토리: `GenerationOutcome.success(GeneratedQuizDto)`, `GenerationOutcome.failure(AttemptStage, AttemptReason, String detail)`
  - `boolean isSuccess()`
  - `OpenAIQuizClient.generateQuiz(...)` 반환 타입이 `Optional<GeneratedQuizDto>` → `GenerationOutcome`

**왜 이 Task 가 필요한가:** 현재 `generateQuiz()` 는 파싱 실패·룰베이스 반려·검증 실패·API 예외·LLM SKIP 다섯 가지를 **전부 `Optional.empty()` 하나로** 반환한다. 호출부 로그가 `"SKIP 또는 생성 실패"` 로 뭉개진 것은 문구 문제가 아니라 정보가 거기 없어서다. 이 Task 가 이번 작업의 핵심이다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/pinq_backend/news/client/GenerationOutcomeTest.java`:

```java
package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import org.junit.jupiter.api.Test;

/**
 * 실패 사유가 호출부까지 살아서 올라가는지 고정한다.
 *
 * 종전에는 다섯 경로가 전부 Optional.empty() 였고, 그래서 손실 집계가
 * 로그 문자열 추측에 의존했다. 이 구분이 무너지면 그 시절로 돌아간다.
 */
class GenerationOutcomeTest {

    @Test
    void 성공은_퀴즈를_들고_있다() {
        GeneratedQuizDto dto = new GeneratedQuizDto();
        GenerationOutcome outcome = GenerationOutcome.success(dto);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.quiz()).isSameAs(dto);
        assertThat(outcome.stage()).isEqualTo(AttemptStage.PUBLISHED);
        assertThat(outcome.reason()).isNull();
    }

    @Test
    void 실패는_단계와_사유를_들고_있다() {
        GenerationOutcome outcome = GenerationOutcome.failure(
                AttemptStage.GENERATE, AttemptReason.LLM_SKIP, "경제 기사가 아님");

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.quiz()).isNull();
        assertThat(outcome.stage()).isEqualTo(AttemptStage.GENERATE);
        assertThat(outcome.reason()).isEqualTo(AttemptReason.LLM_SKIP);
        assertThat(outcome.detail()).isEqualTo("경제 기사가 아님");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*GenerationOutcomeTest*'`
Expected: 컴파일 실패 — `GenerationOutcome` 을 찾을 수 없음.

- [ ] **Step 3: `GenerationOutcome` 작성**

```java
package com.example.pinq_backend.news.client;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;

/**
 * 퀴즈 생성 시도 1건의 결과 — 성공이면 퀴즈, 실패면 어느 단계에서 왜 끝났는지.
 *
 * 종전 반환 타입 {@code Optional<GeneratedQuizDto>} 는 파싱 실패·룰베이스 반려·
 * 검증 실패·API 예외·LLM SKIP 다섯 가지를 전부 {@code empty()} 하나로 뭉쳤다.
 * 그래서 호출부는 "무언가 실패했다"까지만 알았고, 손실 집계는 로그 문자열을
 * 사후에 추측 분류해야 했다.
 *
 * @param quiz   성공 시 생성된 퀴즈, 실패 시 null
 * @param stage  끝난 단계 (성공이면 PUBLISHED)
 * @param reason 실패 사유 (성공이면 null)
 * @param detail 사유 원문 — 룰베이스 reason, LLM skipReason 등 (없으면 null)
 */
public record GenerationOutcome(
        GeneratedQuizDto quiz,
        AttemptStage stage,
        AttemptReason reason,
        String detail
) {

    public static GenerationOutcome success(GeneratedQuizDto quiz) {
        return new GenerationOutcome(quiz, AttemptStage.PUBLISHED, null, null);
    }

    public static GenerationOutcome failure(AttemptStage stage, AttemptReason reason,
                                            String detail) {
        return new GenerationOutcome(null, stage, reason, detail);
    }

    public boolean isSuccess() {
        return quiz != null;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*GenerationOutcomeTest*'`
Expected: 2개 테스트 PASS.

- [ ] **Step 5: `parseQuiz` 가 사유를 돌려주게 고친다**

`OpenAIQuizClient.java` 의 `parseQuiz` 를 `Optional<GeneratedQuizDto>` → `GenerationOutcome` 으로 바꾼다. 시그니처와 세 반환 지점만 바뀌고 파싱 로직은 그대로다:

```java
    private GenerationOutcome parseQuiz(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("choices").get(0).path("message").path("content").asText();

            // 코드블록이 붙을 수 있어 제거
            String json = text.trim()
                    .replaceAll("^```json\\s*", "")
                    .replaceAll("^```\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            GeneratedQuizDto quiz = objectMapper.readValue(json, GeneratedQuizDto.class);

            if (quiz.isSkip()) {
                log.info("OpenAI가 기사 SKIP 판정. 이유: {}", quiz.getSkipReason());
                return GenerationOutcome.failure(
                        AttemptStage.GENERATE, AttemptReason.LLM_SKIP, quiz.getSkipReason());
            }

            return GenerationOutcome.success(quiz);
        } catch (Exception e) {
            log.error("OpenAI 응답 파싱 실패. response={}", rawResponse, e);
            return GenerationOutcome.failure(
                    AttemptStage.GENERATE, AttemptReason.PARSE_FAILED, e.getMessage());
        }
    }
```

- [ ] **Step 6: `generateQuiz` 반환 타입 변경**

같은 파일의 `generateQuiz` 본문에서 `try` 블록 이후를 아래로 교체한다. 요청 조립부(`requestBody` 까지)는 손대지 않는다:

```java
        try {
            String rawResponse = restClient.post()
                    .uri(OPENAI_API_URL)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            logTokenUsage("generate", rawResponse);

            GenerationOutcome parsed = parseQuiz(rawResponse);
            if (!parsed.isSuccess()) return parsed;

            GeneratedQuizDto quiz = parsed.quiz();

            // 1차: 룰베이스 검증 — Claude 호출보다 먼저 돌려서 비용 절감.
            QuizRuleValidator.Result ruleResult = ruleValidator.validate(quiz);
            if (!ruleResult.valid()) {
                log.warn("룰베이스 검증 실패, 퀴즈 폐기. reason={} question={}",
                        ruleResult.reason(), quiz.getQuestion());
                return GenerationOutcome.failure(
                        AttemptStage.VALIDATE, AttemptReason.RULE_REJECTED, ruleResult.reason());
            }

            // 2차: Claude cross-model 검증 (정답 정합성 + 이력과의 의미적 중복).
            // ⚠️ verifyAnswer 는 boolean 만 돌려준다 — 기준 16 인지 복수 정답인지는 알 수 없다.
            //    응답 형식 변경은 캐시된 고정부를 건드리는 일이라 별건이다(docs/PENDING.md).
            if (!verifyAnswer(quiz, category, recentQuestions, extraVerifyRules, verifyModelOverride)) {
                return GenerationOutcome.failure(
                        AttemptStage.VERIFY, AttemptReason.VERIFY_FAILED, null);
            }

            return GenerationOutcome.success(quiz);
        } catch (Exception e) {
            log.error("OpenAI API 퀴즈 생성 실패. title={}", title, e);
            return GenerationOutcome.failure(
                    AttemptStage.GENERATE, AttemptReason.API_ERROR, e.getMessage());
        }
```

메서드 선언의 반환 타입도 `Optional<GeneratedQuizDto>` → `GenerationOutcome` 으로 바꾸고, javadoc 의 `@return` 을 아래로 교체한다:

```java
     * @return 성공 시 퀴즈를 담은 GenerationOutcome, 실패 시 단계·사유를 담은 GenerationOutcome.
     *         (종전에는 다섯 실패 경로가 전부 Optional.empty() 로 뭉개져 손실 집계가 불가능했다)
```

- [ ] **Step 7: 컴파일해 호출부 깨짐 확인**

Run: `./gradlew compileJava`
Expected: **실패** — `QuizGenerationService` 와 워크벤치 dry-run 경로가 `Optional` 을 기대하고 있다. 이 실패 목록이 Task 5 에서 고칠 지점의 정확한 목록이다. 목록을 적어 둘 것.

- [ ] **Step 8: 커밋하지 않는다**

컴파일이 깨진 상태이므로 Task 5 와 함께 커밋한다. 여기서 멈추고 Task 5 로 넘어간다.

---

### Task 5: 생성 서비스에 저장 지점 8곳 심기

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/quiz/service/QuizGenerationService.java`
- Test: `src/test/java/com/example/pinq_backend/quiz/service/QuizGenerationAttemptRecordingTest.java`

**Interfaces:**
- Consumes: Task 3 의 `QuizGenerationAttemptRecorder.record(...)`, Task 4 의 `GenerationOutcome`
- Produces: 없음 (최종 소비 지점)

- [ ] **Step 1: 회차 구분 상수와 의존성 추가**

`QuizGenerationService` 에 필드를 추가한다(생성자 주입 — 클래스가 `@RequiredArgsConstructor` 를 쓰므로 `private final` 선언만 하면 된다):

```java
    private final QuizGenerationAttemptRecorder attemptRecorder;

    /**
     * 회차 구분. 시각으로 추측하지 않는다 — 정기 회차가 늦어지면 오분류된다.
     * 진입점이 다르므로 자기가 어느 쪽인지 알고 있다.
     */
    private static final String RUN_REGULAR = "REGULAR";
    private static final String RUN_BACKFILL = "BACKFILL";
```

`generateQuizForCategory` 시그니처에 회차를 받는 파라미터를 추가한다:

```java
    private boolean generateQuizForCategory(
            Category category,
            LocalDate today,
            Set<String> usedUrls,
            DedupHistory history,
            String runWindow
    ) {
```

호출부 두 곳을 고친다 — `generateTodayQuizzes()` 는 `RUN_REGULAR`, 백필 경로는 `RUN_BACKFILL` 을 넘긴다.

- [ ] **Step 2: PREFILTER 3곳에 기록 추가**

기존 `log.info` 는 지우지 않는다(실시간 확인 경로). 그 아래에 `record` 를 덧붙인다.

```java
                if (usedUrls.contains(url)) {
                    log.info("중복 기사 건너뜀. category={}, url={}", category, url);
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.PREFILTER, AttemptReason.CROSS_CATEGORY_USED, null, null);
                    continue;
                }
```

```java
                if (isEditorialTitle(title)) {
                    log.info("사설·칼럼 기사 건너뜀. category={}, title={}", category, title);
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.PREFILTER, AttemptReason.EDITORIAL, null, null);
                    continue;
                }
```

```java
                if (content.isBlank()) {
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.PREFILTER, AttemptReason.EMPTY_CONTENT, null, null);
                    continue;
                }
```

⚠️ 세 번째는 종전에 `if (content.isBlank()) continue;` 한 줄이었다 — 블록으로 바꾼다.

- [ ] **Step 3: GENERATE/VERIFY 결과 기록**

`generateQuiz` 호출부를 아래로 교체한다:

```java
                GenerationOutcome outcome =
                        openAIQuizClient.generateQuiz(title, content, category, promptHistory);
                if (!outcome.isSuccess()) {
                    log.info("기사 건너뜀. category={}, title={}, stage={}, reason={}",
                            category, title, outcome.stage(), outcome.reason());
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            outcome.stage(), outcome.reason(), outcome.detail(), null);
                    continue;
                }

                GeneratedQuizDto dto = outcome.quiz();
```

종전 로그 문구 `"기사 건너뜀 (SKIP 또는 생성 실패)"` 는 이제 거짓이다 — 단계와 사유를 알기 때문이다. 위처럼 교체한다.

- [ ] **Step 4: VALIDATE 4곳에 기록 추가**

```java
                if (!isValidQuiz(dto)) {
                    log.warn("OpenAI 응답 유효성 검증 실패. title={}", title);
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.VALIDATE, AttemptReason.INVALID_RESPONSE, null, null);
                    continue;
                }
```

```java
                if (term != null && term.equals(category.getDisplayName())) {
                    log.info("keyword 용어가 카테고리명과 동일해 폐기. category={}, term={}, question={}",
                            category, term, dto.getQuestion());
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.VALIDATE, AttemptReason.TERM_EQUALS_CATEGORY,
                            "term=" + term, null);
                    continue;
                }
```

```java
                if (term != null && !TERM_GUARD_EXEMPT.contains(term)
                        && history.isRecentTerm(category, term)) {
                    log.info("최근 {}일 내 동일 keyword 용어 재출제로 폐기. category={}, term={}, question={}",
                            TERM_GUARD_DAYS, category, term, dto.getQuestion());
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.VALIDATE, AttemptReason.TERM_REUSE_GUARD,
                            "term=" + term, null);
                    continue;
                }
```

렉시컬 유사도 블록의 `continue;` 바로 위:

```java
                    attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                            AttemptStage.VALIDATE, AttemptReason.LEXICAL_DUPLICATE,
                            "jaccard=%.2f dice=%.2f".formatted(
                                    match.tokenJaccard(), match.bigramDice()), null);
```

- [ ] **Step 5: PUBLISHED 기록**

`quizRepository.save(...)` 의 반환을 받아 id 를 넘긴다. 종전에는 반환을 버렸다:

```java
                Quiz saved = quizRepository.save(
                        Quiz.builder()
                                .article(article)
                                .category(category)
                                .quizDate(today)
                                .question(dto.getQuestion())
                                .explanation(dto.getExplanation())
                                .keyword(dto.getKeyword())
                                .choices(choices)
                                .build()
                );

                attemptRecorder.record(category.name(), runWindow, keyword, title, url,
                        AttemptStage.PUBLISHED, null, null, saved.getId());
```

- [ ] **Step 6: 테스트 작성**

`src/test/java/com/example/pinq_backend/quiz/service/QuizGenerationAttemptRecordingTest.java`:

```java
package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.audit.QuizGenerationAttemptRecorder;
import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 회차 구분이 시각 추측이 아니라 호출부 인자로 정해지는지 고정한다.
 *
 * 종전 집계 스크립트는 06:10 을 경계로 시각을 보고 정기/백필을 갈랐고,
 * 정기 회차가 늦어지면 그대로 오분류됐다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizGenerationAttemptRecordingTest {

    @Autowired
    private QuizGenerationAttemptRecorder recorder;

    @Autowired
    private QuizGenerationAttemptRepository repository;

    @Autowired
    private Clock clock;

    @Test
    void 회차_구분이_시각과_무관하게_기록된다() {
        repository.deleteAll();

        // 같은 시각에 두 회차를 남긴다 — 시각으로 갈랐다면 구분되지 않는다.
        recorder.record("STOCK", "REGULAR", "주식", "정기 기사", "https://example.com/a",
                AttemptStage.PUBLISHED, null, null, 1L);
        recorder.record("STOCK", "BACKFILL", "주식", "백필 기사", "https://example.com/b",
                AttemptStage.PUBLISHED, null, null, 2L);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        assertThat(rows).extracting(QuizGenerationAttempt::getRunWindow)
                .containsExactlyInAnyOrder("REGULAR", "BACKFILL");
    }

    @Test
    void 발행_행에는_quiz_id_가_박힌다() {
        repository.deleteAll();

        recorder.record("STOCK", "REGULAR", "주식", "발행된 기사", "https://example.com/c",
                AttemptStage.PUBLISHED, null, null, 463L);

        List<QuizGenerationAttempt> rows =
                repository.findByOccurredOnOrderByOccurredAtAsc(LocalDate.now(clock));

        // 이 값이 없으면 검수 판정 등급을 나중에 결합할 수 없다 — 별건의 전제다.
        assertThat(rows).singleElement()
                .extracting(QuizGenerationAttempt::getQuizId)
                .isEqualTo(463L);
    }
}
```

- [ ] **Step 7: 전체 테스트 실행**

Run: `./gradlew test`
Expected: 전부 PASS. 기존 테스트가 `generateQuiz` 의 `Optional` 반환을 스텁하고 있으면 여기서 깨진다 — `GenerationOutcome.success(dto)` / `GenerationOutcome.failure(...)` 로 고친다. 워크벤치 dry-run 경로(`test-generate`)도 같은 방식으로 맞춘다.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/example/pinq_backend/news/client/ \
        src/main/java/com/example/pinq_backend/quiz/service/QuizGenerationService.java \
        src/test/java/com/example/pinq_backend/news/client/GenerationOutcomeTest.java \
        src/test/java/com/example/pinq_backend/quiz/service/QuizGenerationAttemptRecordingTest.java
git commit -m "feat: 생성 시도 사유를 호출부까지 올려보내고 8개 지점에서 기록

종전에는 파싱 실패·룰베이스 반려·검증 실패·API 예외·LLM SKIP 다섯 가지가
전부 Optional.empty() 로 뭉개져, 손실 집계가 로그 문자열 추측에 의존했다."
```

---

### Task 6: 조회 API + 스크립트 서브커맨드

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/audit/AuditController.java`
- Modify: `~/bin/pinq-quiz-fetch.sh` (레포 밖 — 사용자 로컬)
- Test: `src/test/java/com/example/pinq_backend/audit/AuditControllerTest.java`

**Interfaces:**
- Consumes: Task 2 의 `QuizGenerationAttemptRepository`
- Produces:
  - `GET /api/admin/audit/generation-attempts?days=N` → `List<DailyRow>`
  - `GET /api/admin/audit/generation-attempts?date=YYYY-MM-DD&raw=true` → `List<QuizGenerationAttempt>`

- [ ] **Step 1: 컨트롤러 테스트 추가**

`AuditControllerTest.java` 의 기존 테스트 형식을 그대로 따라(같은 파일의 `token-usage` 테스트를 본뜰 것) 두 개를 추가한다:

```java
    @Test
    void 생성_시도_롤업을_돌려준다() throws Exception {
        given(quizGenerationAttemptRepository.rollupSince(any()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/admin/audit/generation-attempts")
                        .header("X-Admin-Secret", ADMIN_SECRET)
                        .param("days", "7"))
                .andExpect(status().isOk());
    }

    @Test
    void raw_는_그날_원시_행을_돌려준다() throws Exception {
        given(quizGenerationAttemptRepository
                .findByOccurredOnOrderByOccurredAtAsc(LocalDate.of(2026, 8, 16)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/admin/audit/generation-attempts")
                        .header("X-Admin-Secret", ADMIN_SECRET)
                        .param("date", "2026-08-16")
                        .param("raw", "true"))
                .andExpect(status().isOk());
    }
```

`quizGenerationAttemptRepository` 는 기존 `tokenUsageRepository` 와 같은 방식으로 목 빈 선언을 추가한다(파일 상단의 `@MockitoBean` 선언부 확인).

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*AuditControllerTest*'`
Expected: 404 또는 컴파일 실패.

- [ ] **Step 3: 엔드포인트 구현**

`AuditController` 에 필드와 메서드를 추가한다. `MAX_TOKEN_DAYS` 옆에 상한 상수를 함께 둔다:

```java
    private static final int MAX_ATTEMPT_DAYS = 90;

    /**
     * 퀴즈 생성 시도의 손실 집계.
     *
     * 기본은 날짜×카테고리×stage×reason 롤업이다 — 원시 행은 하루 50~160건이라
     * 그대로 뱉으면 읽을 수 없다. {@code raw=true} 는 그날 원시 행을 돌려주며,
     * <b>기사 제목으로 기사 풀 오염을 눈으로 확인하는 경로</b>다(2026-08-16 에
     * EXCHANGE_RATE 후보로 교보문고 베스트셀러가 들어온 것을 이렇게 봤다).
     *
     * 종전에는 이 데이터가 AuditLogBuffer(메모리)에만 있어 검수가 하루만 밀려도
     * 영구 결손이었다(8/15).
     */
    @GetMapping("/generation-attempts")
    public List<?> generationAttempts(
        @RequestParam(name = "days", defaultValue = "30") int days,
        @RequestParam(name = "date", required = false) String date,
        @RequestParam(name = "raw", defaultValue = "false") boolean raw
    ) {
        if (raw) {
            LocalDate day = (date != null) ? LocalDate.parse(date) : LocalDate.now(clock);
            return quizGenerationAttemptRepository.findByOccurredOnOrderByOccurredAtAsc(day);
        }
        int window = clamp(days, 1, MAX_ATTEMPT_DAYS);
        return quizGenerationAttemptRepository.rollupSince(
                LocalDate.now(clock).minusDays(window - 1L));
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*AuditControllerTest*'`
Expected: PASS.

- [ ] **Step 5: 전체 테스트**

Run: `./gradlew test`
Expected: 전부 PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/pinq_backend/audit/AuditController.java \
        src/test/java/com/example/pinq_backend/audit/AuditControllerTest.java
git commit -m "feat: 생성 시도 조회 API — 롤업 + 하루치 원시 행"
```

- [ ] **Step 7: 조회 스크립트에 서브커맨드 추가**

`~/bin/pinq-quiz-fetch.sh` 는 레포 밖이다. `tokens` 케이스를 본떠 추가한다:

```bash
  attempts)
    case "$ARG" in
      "") DAYS=30 ;;
      [0-9]|[0-9][0-9]) DAYS="$ARG" ;;
      *) echo "오류: 일수는 1~90 정수만 허용한다 (받은 값: $ARG)" >&2; exit 2 ;;
    esac
    call "/api/admin/audit/generation-attempts?days=${DAYS}"
    ;;
```

사용법 줄(`echo "사용법: ..."`)에도 `attempts [일수]` 를 추가한다.

---

### Task 7: 배포 후 대조 검증 (로그가 살아 있는 마지막 기회)

**Files:** 없음 (운영 확인)

**Interfaces:**
- Consumes: Task 1~6 전부

⚠️ **이 Task 는 배포 다음 날 정기 회차(06:04) 이후에만 할 수 있다.** 그리고 **그날 한 번만** 가능하다 — 대조군인 `logs` 는 링버퍼라 곧 사라진다.

- [ ] **Step 1: main 에 push 해 CI 배포**

```bash
git push origin main
```

CI 가 ① `Prepare EC2` 에서 `OK: quiz_generation_attempt 마이그레이션 적용` 을 찍고 ② 무중단 배포까지 통과해야 한다. 실패하면 `gh run view <id> --log-failed` 로 확인한다.

- [ ] **Step 2: 다음 날 06:04 이후 행 확인**

```bash
~/bin/pinq-quiz-fetch.sh attempts 2
```

Expected: 그날 날짜의 행이 카테고리×stage×reason 으로 나온다. 비어 있으면 배포가 회차보다 늦었는지 확인한다.

- [ ] **Step 3: 로그와 대조**

```bash
~/bin/pinq-quiz-fetch.sh logs 30 | python3 scripts/scrape-stats.py $(date +%F)
```

두 출력의 **카테고리별 시도 총합**을 비교한다. 분류 기준이 달라 사유별 수치는 일치하지 않는 것이 정상이고, **총합이 맞아야 한다.** 어긋나면 저장 지점이 빠진 것이다 — 8곳을 다시 확인한다.

이 대조는 **오늘만 가능하다.** 결과를 `docs/quality-audit-log.md` 그날 항목에 남긴다.

- [ ] **Step 4: 검수 스킬 절차 교체**

`.claude/skills/quiz-audit/SKILL.md` 6단계의 `logs | scrape-stats.py >> jsonl` 파이프를 `attempts` 조회 + 아티팩트 갱신으로 교체한다. `scrape-stats.py`·`scrape-stats.jsonl` 은 **동결**이라고 명시한다 — 지우지 말 것. 7/28~8/16 구간은 로그가 소멸해 복원 불가이고 jsonl 이 유일한 기록이다.

- [ ] **Step 5: PENDING 갱신**

`docs/PENDING.md` 의 "손실 집계 영속화" 항목을 완료로 옮기고, 남은 것을 적는다: ⓐ `VERIFY` 세부 사유는 여전히 뭉쳐 있다(별건 항목 참조) ⓑ 판정 등급 결합은 `quiz_id` 로 언제든 가능 ⓒ 아티팩트의 jsonl↔DB 경계선.

- [ ] **Step 6: 커밋**

```bash
git add docs/PENDING.md docs/quality-audit-log.md .claude/skills/quiz-audit/SKILL.md
git commit -m "docs: 손실 집계 DB 전환 완료 — 로그 대조 결과와 절차 교체"
```

---

## Self-Review

**스펙 커버리지**

| 스펙 항목 | Task |
|---|---|
| 스키마 (12컬럼, 인덱스, FK 없음) | 1, 2 |
| stage × reason 값 집합 | 2 |
| `run_window` 를 호출부가 넘긴다 | 5 |
| 반환 타입 변경 (`GenerationOutcome`) | 4 |
| 저장 지점 8곳 | 5 |
| Recorder (REQUIRES_NEW + 삼킴) | 3 |
| 조회 API + `raw=true` | 6 |
| 스크립트 서브커맨드 | 6 |
| 검수 절차 교체 / jsonl 동결 | 7 |
| 마이그레이션 CI 등록 | 1 |
| 성공 기준 1~4 | 7 |

**미커버 (의도적)**: 판정 등급 테이블, 검증 응답 형식 변경 — 스펙의 "안 하는 것".

**주의 지점**: Task 4 는 컴파일이 깨진 채 끝난다(의도). Task 5 와 한 묶음으로 실행할 것.
