# 복습 나무 가시화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 복습(간격 반복) 항목의 성장 과정(물 준 횟수·단계·졸업)을 저장·노출한다 — 졸업 row 보존, 물 카운터 2종, 정원(garden) API, 복습 풀이/채점 응답 확장(+기사), 오답노트 연동.

**Architecture:** `review_item` 에 `water_count`(총 복습 시도)·`absorbed_count`(맞힌 복습)·`graduated_at` 3컬럼 추가. 졸업 시 row 를 삭제하는 대신 `graduated_at` 을 세팅하고, due 조회 쿼리들에 `graduatedAt IS NULL` 조건을 붙인다. `users.graduated_review_count` 카운터는 유일한 진실로 유지(과거 졸업분은 row 가 없어 COUNT 대체 불가). 졸업한 문제는 어떤 경로로도 복습 큐에 재진입하지 않는다(영구 성취 — 기존 `enqueueWrongAnswer` 의 "존재하면 skip" 이 자동으로 보장).

**Tech Stack:** Spring Boot 3 / JPA / MySQL(운영 ddl-auto=validate) / JUnit5 + Mockito + AssertJ

## Global Constraints

- ⚠️ **락 순서 불변**: `ReviewService.answerReview` 에서 `reviewDailyLogRecorder.record(...)` 호출은 반드시 `incrementGraduatedReviewCount` **이전** (2026-07-10 자기 교착 실사고). 이 순서를 바꾸는 변경 금지. 물 카운터 증가는 review_item 자기 row 갱신이라 이 교착 축과 무관.
- 마이그레이션: `scripts/prepare-server.sh` 의 `col_exists` 멱등 가드 패턴 준수. 운영은 ddl-auto=validate — 엔티티와 컬럼 타입 일치 필수 (`LocalDateTime` ↔ `DATETIME(6)`).
- 확정 결정: 물 카운터 C안(둘 다 저장) / 졸업 보존 (a)안(`graduated_at` 컬럼) / 재오답 시 1-A(영구 성취, 재등록 안 함) / `graduatedTrees` 카운터 유지(COUNT 대체 안 함).
- 배포: push → CI 완료 확인 → 필요 시 서버에서 deploy.sh 경유. 수동 docker compose up 금지.
- 커밋 메시지·주석은 기존 파일들의 한국어 스타일 유지.

---

### Task 1: ReviewItem 엔티티 확장 + DB 마이그레이션

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/review/domain/ReviewItem.java`
- Test: `src/test/java/com/example/pinq_backend/review/domain/ReviewItemTest.java`
- Create: `scripts/migration/2026-07-21-review-tree-visibility.sql`
- Modify: `scripts/prepare-server.sh` (col_exists 가드 블록 추가)

**Interfaces:**
- Produces: `item.water(boolean correct)` — waterCount++ (correct 면 absorbedCount++), `item.graduate(LocalDateTime now)` — graduatedAt 세팅, `item.isGraduated()`, getter `getWaterCount() / getAbsorbedCount() / getGraduatedAt()`

- [ ] **Step 1: 실패하는 테스트 작성** — `ReviewItemTest` 에 추가 (기존 테스트 스타일 따름):

```java
@Test
@DisplayName("물 주기: 시도마다 waterCount 가 오르고, 정답이면 absorbedCount 도 오른다")
void water_countsAttemptsAndAbsorbs() {
    ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY);

    item.water(true);
    item.water(false);
    item.water(true);

    assertThat(item.getWaterCount()).isEqualTo(3);
    assertThat(item.getAbsorbedCount()).isEqualTo(2);
}

@Test
@DisplayName("졸업: graduate 는 graduatedAt 을 기록하고 isGraduated 가 true 가 된다")
void graduate_marksGraduatedAt() {
    ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY);
    LocalDateTime now = TODAY.atStartOfDay();

    assertThat(item.isGraduated()).isFalse();
    item.graduate(now);

    assertThat(item.isGraduated()).isTrue();
    assertThat(item.getGraduatedAt()).isEqualTo(now);
}
```

(`user`, `TODAY` 는 기존 테스트 픽스처 재사용. `LocalDateTime` import 추가.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests ReviewItemTest`
Expected: 컴파일 에러 (`water`, `graduate` 미정의)

- [ ] **Step 3: ReviewItem 구현** — 필드 3개 + 메서드 3개 추가, 클래스 javadoc 의 생명주기 서술 갱신:

```java
// 클래스 javadoc 의 "마지막 단계(stage 2)에서 맞히면 '졸업' — row 를 삭제한다." 항목을 다음으로 교체:
//  - 마지막 단계(stage 2)에서 맞히면 '졸업' — row 는 남기고 graduated_at 을 기록한다.
//    졸업한 항목은 due 조회에서 제외되며, 재오답해도 복습 큐에 재진입하지 않는다
//    (다 키운 나무는 시들지 않는다 — 영구 성취).

    /** 이 항목에 물을 준 총 횟수 (복습 시도 수). 정답/오답 무관하게 오른다. */
    @Column(name = "water_count", nullable = false)
    private int waterCount;

    /** 흡수된 물 — 복습에서 맞힌 횟수. */
    @Column(name = "absorbed_count", nullable = false)
    private int absorbedCount;

    /** 졸업 시각. null 이면 아직 자라는 중. 졸업 후에는 복습 대상에서 영구 제외. */
    @Column(name = "graduated_at")
    private LocalDateTime graduatedAt;

    /** 복습 시도 1회 기록 — "물 주기". 채점 결과와 무관하게 항상 호출한다. */
    public void water(boolean correct) {
        waterCount += 1;
        if (correct) {
            absorbedCount += 1;
        }
    }

    /** 졸업 처리 — row 를 남긴 채 시각만 기록한다 (나무 목록의 원천). */
    public void graduate(LocalDateTime now) {
        graduatedAt = now;
    }

    public boolean isGraduated() {
        return graduatedAt != null;
    }
```

`advanceOrGraduate` javadoc 의 "호출자가 row 를 삭제해야 함" → "호출자가 graduate() 를 호출해야 함" 으로 수정. `java.time.LocalDateTime` import.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests ReviewItemTest`
Expected: PASS (기존 테스트 포함 전체)

- [ ] **Step 5: 마이그레이션 SQL 작성** — `scripts/migration/2026-07-21-review-tree-visibility.sql`:

```sql
-- ============================================================================
-- 복습 나무 가시화: 물 카운터 2종 + 졸업 보존
--
-- 배경: 복습의 성장 과정(물 준 횟수)과 졸업한 나무 목록이 어디에도 남지 않았다.
--       ① water_count/absorbed_count 로 물 준 횟수(총 시도/흡수)를 누적하고
--       ② 졸업 시 row 삭제 대신 graduated_at 을 기록해 "나무 목록"의 원천으로 삼는다.
--       기존에 이미 졸업(삭제)된 항목은 복원 불가 — users.graduated_review_count
--       카운터가 과거분을 포함한 유일한 총계로 유지된다.
--
-- ⚠️ 운영은 ddl-auto=validate — 새 앱 배포 '전에' 실행돼야 한다 (CI Prepare 단계).
--
-- 실행:
--   docker exec -i mysql-container mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" \
--     < scripts/migration/2026-07-21-review-tree-visibility.sql
--
-- 롤백:
--   DELETE FROM review_item WHERE graduated_at IS NOT NULL;
--   ALTER TABLE review_item DROP COLUMN water_count, DROP COLUMN absorbed_count, DROP COLUMN graduated_at;
-- ============================================================================

SET time_zone = '+09:00';

-- 기존 row 는 물 이력이 없으므로 0부터 시작 (과거 복습 횟수는 복원 불가, 정상)
ALTER TABLE review_item
    ADD COLUMN water_count    INT         NOT NULL DEFAULT 0,
    ADD COLUMN absorbed_count INT         NOT NULL DEFAULT 0,
    ADD COLUMN graduated_at   DATETIME(6) NULL;
```

- [ ] **Step 6: prepare-server.sh 가드 추가** — `graduated_review_count` 블록과 같은 패턴으로, 기존 가드 블록들 뒤에:

```bash
# 복습 나무 가시화(물 카운터 + 졸업 보존) — ADD COLUMN 이라 존재 가드 필요
if [ "$(col_exists review_item water_count)" = "0" ]; then
  run_sql scripts/migration/2026-07-21-review-tree-visibility.sql
  echo "OK: review-tree-visibility 마이그레이션 적용"
else
  echo "SKIP: review_item.water_count 이미 존재"
fi
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/pinq_backend/review/domain/ReviewItem.java \
        src/test/java/com/example/pinq_backend/review/domain/ReviewItemTest.java \
        scripts/migration/2026-07-21-review-tree-visibility.sql scripts/prepare-server.sh
git commit -m "feat: review_item 물 카운터 2종 + 졸업 보존 컬럼 (나무 가시화 기반)"
```

---

### Task 2: 졸업 생명주기 전환 + 채점 응답 확장 (물 이력·기사·나무 총계)

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/review/repository/ReviewItemRepository.java`
- Modify: `src/main/java/com/example/pinq_backend/user/repository/UserRepository.java`
- Modify: `src/main/java/com/example/pinq_backend/quiz/dto/AnswerResponse.java` (ArticleResponse.from 을 public 으로)
- Modify: `src/main/java/com/example/pinq_backend/review/dto/ReviewAnswerResponse.java`
- Modify: `src/main/java/com/example/pinq_backend/review/service/ReviewService.java`
- Test: `src/test/java/com/example/pinq_backend/review/service/ReviewServiceTest.java`

**Interfaces:**
- Consumes: Task 1 의 `water/graduate/isGraduated`
- Produces:
  - `ReviewItemRepository.findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(Long, LocalDate)`
  - `ReviewItemRepository.findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(Long, LocalDate)`
  - `UserRepository.findGraduatedReviewCount(Long userId)` → `int`
  - `ReviewAnswerResponse(quizId, correct, correctChoiceId, explanation, keyword, article, graduated, nextDueDate, stage, waterCount, absorbedCount, totalGraduatedTrees)`

- [ ] **Step 1: 실패하는 테스트 작성/수정** — `ReviewServiceTest` 의 answerReview 관련 기존 테스트를 새 쿼리 메서드명으로 갱신하고, 다음을 추가:

```java
@Test
@DisplayName("복습 채점: 시도마다 물 카운터가 오른다 (정답이면 흡수도)")
void answer_watersItem() {
    ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(3));
    when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
    Quiz quiz = QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제");
    when(quizRepository.findById(1L)).thenReturn(Optional.of(quiz));

    ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, quiz.getAnswerChoice().getId());

    assertThat(item.getWaterCount()).isEqualTo(1);
    assertThat(item.getAbsorbedCount()).isEqualTo(1);
    assertThat(response.waterCount()).isEqualTo(1);
    assertThat(response.absorbedCount()).isEqualTo(1);
    assertThat(response.article()).isNotNull(); // 일반 채점 화면과 동일하게 기사 노출
}

@Test
@DisplayName("복습 채점: 졸업 시 row 를 삭제하지 않고 graduatedAt 을 기록하며, 나무 총계를 돌려준다")
void answer_graduation_preservesRow() {
    ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
    item.advanceOrGraduate(TODAY.minusDays(20)); // stage 1
    item.advanceOrGraduate(TODAY.minusDays(10)); // stage 2 (MAX)
    when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));
    Quiz quiz = QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제");
    when(quizRepository.findById(1L)).thenReturn(Optional.of(quiz));
    when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(4);

    ReviewAnswerResponse response = service.answerReview(USER_ID, 1L, quiz.getAnswerChoice().getId());

    assertThat(response.graduated()).isTrue();
    assertThat(response.totalGraduatedTrees()).isEqualTo(4);
    assertThat(item.isGraduated()).isTrue();
    verify(reviewItemRepository, never()).delete(any());
    verify(userRepository).incrementGraduatedReviewCount(USER_ID);
}

@Test
@DisplayName("복습 채점: 이미 졸업한 항목은 404 — 다시 복습할 수 없다")
void answer_graduatedItem_notFound() {
    ReviewItem item = ReviewItem.enqueue(user, 1L, TODAY.minusDays(30));
    item.graduate(TODAY.minusDays(1).atStartOfDay());
    when(reviewItemRepository.findByUserIdAndQuizId(USER_ID, 1L)).thenReturn(Optional.of(item));

    assertThatThrownBy(() -> service.answerReview(USER_ID, 1L, 100L))
            .isInstanceOf(QuizNotFoundException.class);
}
```

비졸업 채점 테스트에서 `totalGraduatedTrees` 는 `null` 임을 기존 테스트 하나에 assert 로 추가. `getTodayReviews` 테스트들의 stub 메서드명도 새 이름(`...GraduatedAtIsNull...`)으로 교체.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests ReviewServiceTest`
Expected: 컴파일 에러 (신규 메서드/필드 미정의)

- [ ] **Step 3: 구현**

`ReviewItemRepository` — 기존 due 쿼리 2종을 graduated 제외 파생 쿼리로 교체, 미사용 `countByUserIdAndDueDateLessThanEqual` 은 삭제 (main/test 어디서도 호출 없음 확인됨):

```java
    Optional<ReviewItem> findByUserIdAndQuizId(Long userId, Long quizId);

    /** 오늘 복습 대상 (due 가 오늘이거나 지난 것 — 밀린 복습 포함). 졸업한 나무는 제외. */
    List<ReviewItem> findAllByUserIdAndGraduatedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(
            Long userId, LocalDate date);

    /** 다음 예정 복습일 계산용 — 아직 due 가 안 된 것 중 가장 이른 것. 졸업 제외. */
    Optional<ReviewItem> findFirstByUserIdAndGraduatedAtIsNullAndDueDateAfterOrderByDueDateAsc(
            Long userId, LocalDate date);
```

`UserRepository` — 스칼라 조회 추가 (`incrementGraduatedReviewCount` 는 @Modifying 벌크라 영속성 컨텍스트를 우회하므로, 증가 직후 최신값은 DB 스칼라 쿼리로 읽는다):

```java
    /** 졸업 나무 총계 — increment 직후에도 DB 최신값을 읽도록 스칼라 쿼리 사용. */
    @Query("SELECT u.graduatedReviewCount FROM User u WHERE u.id = :userId")
    int findGraduatedReviewCount(@Param("userId") Long userId);
```

`AnswerResponse.ArticleResponse.from` — `static` → `public static` (복습 응답에서 재사용).

`ReviewAnswerResponse` — 전면 교체:

```java
/**
 * 복습 채점 결과.
 *
 * 일반 채점(AnswerResponse)과 달리 통계(스트릭/정답률)에는 반영되지 않으며,
 * 복습 주기 정보(graduated / nextDueDate)와 물 이력을 담는다.
 * article 은 일반 채점 후 화면과 동일한 기사 링크 노출용.
 *
 * @param graduated           true 면 이 문제는 복습 졸업 (더 이상 안 나옴)
 * @param nextDueDate         졸업이 아니면 다음 복습 예정일
 * @param stage               채점 반영 후 단계 (졸업이면 마지막 단계 그대로)
 * @param waterCount          이 문제에 물 준 총 횟수 (이번 시도 포함)
 * @param absorbedCount       그중 흡수된(맞힌) 횟수
 * @param totalGraduatedTrees 졸업 시에만 — 사용자의 나무 총계 (졸업 연출용). 비졸업이면 null
 */
public record ReviewAnswerResponse(
    Long quizId,
    boolean correct,
    Long correctChoiceId,
    String explanation,
    String keyword,
    AnswerResponse.ArticleResponse article,
    boolean graduated,
    LocalDate nextDueDate,
    int stage,
    int waterCount,
    int absorbedCount,
    Integer totalGraduatedTrees
) {
    public static ReviewAnswerResponse of(
        Quiz quiz,
        boolean correct,
        ReviewItem item,
        boolean graduated,
        LocalDate nextDueDate,
        Integer totalGraduatedTrees
    ) {
        return new ReviewAnswerResponse(
            quiz.getId(),
            correct,
            quiz.getAnswerChoice().getId(),
            quiz.getExplanation(),
            quiz.getKeyword(),
            AnswerResponse.ArticleResponse.from(quiz.getArticle()),
            graduated,
            nextDueDate,
            item.getStage(),
            item.getWaterCount(),
            item.getAbsorbedCount(),
            totalGraduatedTrees
        );
    }
}
```

`ReviewService.answerReview` — 변경 지점만 (⚠️ record 호출 위치·카운터 순서·기존 주석 절대 이동 금지):

```java
        ReviewItem item = reviewItemRepository.findByUserIdAndQuizId(userId, quizId)
                .orElseThrow(() -> new QuizNotFoundException(quizId));

        if (item.isGraduated()) {
            // 졸업한 나무는 영구 성취 — 어떤 경로로도 다시 복습되지 않는다 (404)
            throw new QuizNotFoundException(quizId);
        }
```

채점 분기(기존 `reviewDailyLogRecorder.record(...)` 호출 뒤)를 다음으로 교체:

```java
        item.water(correct); // 물 주기 — 시도 사실을 카운터에 누적 (자기 row 갱신, 락 축 무관)

        boolean graduated = false;
        Integer totalGraduatedTrees = null;
        if (correct) {
            graduated = item.advanceOrGraduate(today);
            if (graduated) {
                // 졸업 — row 는 나무 목록의 원천으로 보존하고 시각만 기록한다.
                item.graduate(LocalDateTime.now(clock));
                // 과거 졸업분(삭제된 row)을 포함한 총계는 카운터가 유일한 진실.
                // 원자적 UPDATE — 여러 기기에서 동시 졸업해도 카운트가 유실되지 않는다.
                userRepository.incrementGraduatedReviewCount(userId);
                totalGraduatedTrees = userRepository.findGraduatedReviewCount(userId);
            }
        } else {
            item.reset(today);
        }

        return ReviewAnswerResponse.of(
                quiz, correct, item, graduated,
                graduated ? null : item.getDueDate(), totalGraduatedTrees);
```

`getTodayReviews` 의 due 조회 2곳도 새 메서드명으로 교체. `java.time.LocalDateTime` import 추가. `answerReview` javadoc 의 "졸업 — 항목 삭제" → "졸업 — graduated_at 기록(보존)" 갱신.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests ReviewServiceTest --tests ReviewItemTest`
Expected: PASS

- [ ] **Step 5: 전체 테스트**

Run: `./gradlew test`
Expected: PASS (AnswerResponse 접근제어 변경·쿼리명 변경의 파급 확인)

- [ ] **Step 6: Commit**

```bash
git add -A src scripts
git commit -m "feat: 복습 졸업 보존 전환 + 채점 응답에 물 이력·기사·나무 총계"
```

---

### Task 3: 복습 풀이 화면에 물 이력 노출 (ReviewQuizResponse)

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/review/dto/ReviewQuizResponse.java`
- Test: `src/test/java/com/example/pinq_backend/review/service/ReviewServiceTest.java`

**Interfaces:**
- Produces: `ReviewQuizResponse(..., int stage, LocalDate dueDate, int waterCount, int absorbedCount)`

- [ ] **Step 1: 실패하는 테스트** — `todayReviews_returnsDueWithNextDate` 에 assert 추가:

```java
        assertThat(response.reviews().get(0).waterCount()).isEqualTo(0);
        assertThat(response.reviews().get(0).absorbedCount()).isEqualTo(0);
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests ReviewServiceTest` / Expected: 컴파일 에러

- [ ] **Step 3: 구현** — record 에 `int waterCount, int absorbedCount` 컴포넌트 추가, `of()` 에서 `item.getWaterCount(), item.getAbsorbedCount()` 전달. javadoc "복습 메타(stage, dueDate)" → "복습 메타(stage, dueDate, 물 이력)".

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests ReviewServiceTest` / Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pinq_backend/review/dto/ReviewQuizResponse.java \
        src/test/java/com/example/pinq_backend/review/service/ReviewServiceTest.java
git commit -m "feat: 복습 풀이 항목에 물 준 횟수 노출"
```

---

### Task 4: 정원 API — GET /api/reviews/garden

**Files:**
- Create: `src/main/java/com/example/pinq_backend/review/dto/GardenResponse.java`
- Modify: `src/main/java/com/example/pinq_backend/review/repository/ReviewItemRepository.java`
- Modify: `src/main/java/com/example/pinq_backend/review/service/ReviewService.java`
- Modify: `src/main/java/com/example/pinq_backend/review/controller/ReviewController.java`
- Test: `src/test/java/com/example/pinq_backend/review/service/ReviewServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository.findGraduatedReviewCount(Long)` (Task 2)
- Produces: `GardenResponse(List<GardenItem> growing, List<GardenItem> graduated, int graduatedTrees)`, `ReviewService.getGarden(Long userId)`, `ReviewItemRepository.findAllByUserId(Long userId)`

- [ ] **Step 1: 실패하는 테스트**

```java
    // ── getGarden ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정원: 자라는 항목은 due 오름차순, 나무는 졸업 최신순으로 나눠 반환한다")
    void garden_splitsGrowingAndGraduated() {
        ReviewItem growing = ReviewItem.enqueue(user, 1L, TODAY.minusDays(1)); // due=TODAY+2
        ReviewItem tree = ReviewItem.enqueue(user, 2L, TODAY.minusDays(30));
        tree.water(true);
        tree.graduate(TODAY.minusDays(1).atStartOfDay());
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(growing, tree));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L, 2L))).thenReturn(List.of(
                QuizFixtures.sampleQuiz(1L, Category.STOCK, "자라는 문제"),
                QuizFixtures.sampleQuiz(2L, Category.STOCK, "나무 문제")));
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(3);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.growing()).hasSize(1);
        assertThat(response.growing().get(0).quizId()).isEqualTo(1L);
        assertThat(response.graduated()).hasSize(1);
        assertThat(response.graduated().get(0).waterCount()).isEqualTo(1);
        assertThat(response.graduatedTrees()).isEqualTo(3); // 카운터 값 — 목록 길이와 다를 수 있음
    }

    @Test
    @DisplayName("정원: 퀴즈가 삭제된 고아 항목은 목록에서 제외한다 (정리는 today 경로가 담당)")
    void garden_skipsOrphans() {
        ReviewItem orphan = ReviewItem.enqueue(user, 9L, TODAY);
        when(reviewItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(orphan));
        when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(9L))).thenReturn(List.of());
        when(userRepository.findGraduatedReviewCount(USER_ID)).thenReturn(0);

        GardenResponse response = service.getGarden(USER_ID);

        assertThat(response.growing()).isEmpty();
        assertThat(response.graduated()).isEmpty();
    }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests ReviewServiceTest` / Expected: 컴파일 에러

- [ ] **Step 3: 구현**

`ReviewItemRepository` 에 추가:

```java
    /** 정원 조회용 — 자라는 항목 + 졸업한 나무 전부. */
    List<ReviewItem> findAllByUserId(Long userId);
```

`GardenResponse.java` 신규:

```java
package com.example.pinq_backend.review.dto;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.review.domain.ReviewItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 정원(복습 나무 현황) — "어떤 문제가 얼마나 자랐는가".
 *
 * growing 은 due 오름차순(급한 것 먼저), graduated 는 졸업 최신순.
 *
 * @param graduatedTrees 나무 총계 — users 카운터 값. 이 기능 배포 이전에 졸업한
 *                       나무는 row 가 없어 graduated 목록에는 없지만 총계에는 포함된다.
 *                       (목록 길이 ≠ 총계일 수 있음 — 프론트는 총계를 신뢰할 것)
 */
public record GardenResponse(
    List<GardenItem> growing,
    List<GardenItem> graduated,
    int graduatedTrees
) {
    /**
     * @param stage         현재 단계 (0~2). 졸업 항목은 마지막 단계 값 그대로
     * @param dueDate       다음 복습 예정일 (졸업 항목도 마지막 값이 남아 있으나 의미 없음)
     * @param waterCount    물 준 총 횟수
     * @param absorbedCount 흡수된(맞힌) 횟수
     * @param graduatedAt   졸업 시각. null 이면 자라는 중
     */
    public record GardenItem(
        Long quizId,
        String category,
        String categoryDisplayName,
        String question,
        String keyword,
        int stage,
        LocalDate dueDate,
        int waterCount,
        int absorbedCount,
        LocalDateTime graduatedAt
    ) {
        public static GardenItem of(ReviewItem item, Quiz quiz) {
            return new GardenItem(
                quiz.getId(),
                quiz.getCategory().name(),
                quiz.getCategory().getDisplayName(),
                quiz.getQuestion(),
                quiz.getKeyword(),
                item.getStage(),
                item.getDueDate(),
                item.getWaterCount(),
                item.getAbsorbedCount(),
                item.getGraduatedAt()
            );
        }
    }
}
```

(keyword 노출은 치팅 아님 — 복습 큐의 문제는 전부 사용자가 이미 틀려서 정답·해설을 본 문제다.)

`ReviewService` 에 추가:

```java
    /**
     * 정원 조회 — 자라는 항목과 졸업한 나무 전체.
     * 고아 항목(퀴즈 삭제됨)은 목록에서 제외만 한다 — 삭제 정리는 getTodayReviews 경로 담당.
     */
    @Transactional(readOnly = true)
    public GardenResponse getGarden(Long userId) {
        List<ReviewItem> items = reviewItemRepository.findAllByUserId(userId);

        Map<Long, Quiz> quizById = items.isEmpty() ? Map.of() : quizRepository
                .findAllWithChoicesAndArticleByIdIn(items.stream().map(ReviewItem::getQuizId).toList())
                .stream()
                .collect(Collectors.toMap(Quiz::getId, Function.identity()));

        List<GardenResponse.GardenItem> growing = new ArrayList<>();
        List<GardenResponse.GardenItem> graduated = new ArrayList<>();
        for (ReviewItem item : items) {
            Quiz quiz = quizById.get(item.getQuizId());
            if (quiz == null) continue;
            (item.isGraduated() ? graduated : growing).add(GardenResponse.GardenItem.of(item, quiz));
        }
        growing.sort(Comparator.comparing(GardenResponse.GardenItem::dueDate));
        graduated.sort(Comparator.comparing(GardenResponse.GardenItem::graduatedAt).reversed());

        return new GardenResponse(growing, graduated, userRepository.findGraduatedReviewCount(userId));
    }
```

(`java.util.Comparator` import, `GardenResponse` import 추가.)

`ReviewController` 에 추가 (클래스 javadoc 의 엔드포인트 목록에도 한 줄 추가):

```java
    @GetMapping("/garden")
    public GardenResponse getGarden() {
        Long userId = SecurityUtils.getCurrentUserId(userService);
        return reviewService.getGarden(userId);
    }
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests ReviewServiceTest` / Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "feat: 정원 API — 자라는 항목·나무 목록 GET /api/reviews/garden"
```

---

### Task 5: 오답노트 연동 — AttemptItemResponse 에 복습 상태

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/user/dto/AttemptItemResponse.java`
- Modify: `src/main/java/com/example/pinq_backend/user/service/AttemptHistoryService.java`
- Modify: `src/main/java/com/example/pinq_backend/review/repository/ReviewItemRepository.java`
- Test: `src/test/java/com/example/pinq_backend/user/service/AttemptHistoryServiceTest.java` (없으면 신규 — 기존 서비스 테스트 스타일로)

**Interfaces:**
- Consumes: Task 1 getter 들
- Produces: `AttemptItemResponse(..., ReviewStatus review)` — `ReviewStatus(int stage, int waterCount, int absorbedCount, boolean graduated, LocalDate dueDate)`, `ReviewItemRepository.findAllByUserIdAndQuizIdIn(Long, Collection<Long>)`
- 주의: 기존 3-인자 `AttemptItemResponse.of(quiz, attempt, bookmarked)` 는 **유지**하고 `review=null` 로 위임 — BookmarkService 등 다른 호출부는 무변경 (북마크 목록의 나무 표시는 요구사항 아님, YAGNI).

- [ ] **Step 1: 실패하는 테스트** — `AttemptHistoryServiceTest` (Mockito, ReviewServiceTest 스타일):

```java
@Test
@DisplayName("오답노트: 복습 큐에 있는 문제는 물 이력(review)이 붙고, 없으면 null 이다")
void wrongNotes_includesReviewStatus() {
    UserQuizAttempt attempt = /* 기존 픽스처 패턴으로 quizId=1L 오답 attempt 생성 */;
    when(userQuizAttemptRepository.findByUserIdAndFirstCorrectFalseOrderByCreatedAtDesc(USER_ID))
            .thenReturn(List.of(attempt));
    when(quizRepository.findAllWithChoicesAndArticleByIdIn(List.of(1L)))
            .thenReturn(List.of(QuizFixtures.sampleQuiz(1L, Category.STOCK, "문제")));
    when(userBookmarkRepository.findBookmarkedQuizIds(USER_ID, List.of(1L))).thenReturn(Set.of());
    ReviewItem item = ReviewItem.enqueue(user, 1L, LocalDate.of(2026, 7, 20));
    item.water(true);
    when(reviewItemRepository.findAllByUserIdAndQuizIdIn(USER_ID, List.of(1L)))
            .thenReturn(List.of(item));

    List<AttemptItemResponse> result = service.getWrongAttempts(USER_ID);

    assertThat(result.get(0).review()).isNotNull();
    assertThat(result.get(0).review().waterCount()).isEqualTo(1);
    assertThat(result.get(0).review().graduated()).isFalse();
}
```

(UserQuizAttempt 생성은 기존 테스트/픽스처의 실제 패턴을 확인해 맞출 것 — 없으면 builder/정적 팩토리 사용.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests AttemptHistoryServiceTest` / Expected: 컴파일 에러

- [ ] **Step 3: 구현**

`ReviewItemRepository` 에 추가:

```java
    /** 오답노트 화면의 복습 상태 join 용 — quizId 묶음 batch 조회. */
    List<ReviewItem> findAllByUserIdAndQuizIdIn(Long userId, Collection<Long> quizIds);
```

(`java.util.Collection` import.)

`AttemptItemResponse` — record 마지막에 `ReviewStatus review` 컴포넌트 추가, 내부 record 와 팩토리 추가:

```java
    /**
     * 복습("물 주기") 진행 상태 — 복습 큐에 등록된 적 없는 문제면 null.
     *
     * @param graduated true 면 다 키운 나무 (더 이상 복습 안 나옴)
     */
    public record ReviewStatus(
        int stage,
        int waterCount,
        int absorbedCount,
        boolean graduated,
        LocalDate dueDate
    ) {
        static ReviewStatus from(ReviewItem item) {
            if (item == null) return null;
            return new ReviewStatus(
                item.getStage(),
                item.getWaterCount(),
                item.getAbsorbedCount(),
                item.isGraduated(),
                item.getDueDate()
            );
        }
    }

    /** 기존 호출부(북마크 등) 호환 — 복습 상태 없이. */
    public static AttemptItemResponse of(Quiz quiz, UserQuizAttempt attempt, boolean bookmarked) {
        return of(quiz, attempt, bookmarked, null);
    }

    public static AttemptItemResponse of(
        Quiz quiz, UserQuizAttempt attempt, boolean bookmarked, ReviewItem reviewItem
    ) {
        // ... 기존 of 본문 그대로, 마지막 인자로 ReviewStatus.from(reviewItem) 추가
    }
```

(`java.time.LocalDate`, `com.example.pinq_backend.review.domain.ReviewItem` import. javadoc 의 항목 나열에 "- 복습 진행 상태(물 준 횟수/나무 여부)" 추가.)

`AttemptHistoryService.toResponse` — `ReviewItemRepository` 필드 주입 추가 후 batch 조회·매핑:

```java
        Map<Long, ReviewItem> reviewByQuizId = reviewItemRepository
            .findAllByUserIdAndQuizIdIn(userId, quizIds)
            .stream()
            .collect(Collectors.toMap(ReviewItem::getQuizId, Function.identity()));
```

기존 map 람다의 `AttemptItemResponse.of(q, att, ...)` 를 4-인자 호출로 교체: `AttemptItemResponse.of(q, att, bookmarkedIds.contains(q.getId()), reviewByQuizId.get(q.getId()))`.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests AttemptHistoryServiceTest` / Expected: PASS

- [ ] **Step 5: 전체 테스트 + 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (BookmarkService 등 3-인자 호출부 무변경 확인)

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "feat: 오답노트 응답에 복습 상태(물 이력·나무 여부) 연동"
```

---

## 배포 체크리스트 (구현 완료 후)

1. `./gradlew build` 통과 확인 → push (사용자 선호: 커밋·push 직접 실행)
2. CI 완료 확인 — prepare 단계가 `review-tree-visibility 마이그레이션 적용` 을 출력하는지
3. 컨테이너 재생성 필요 시 서버에서 `deploy.sh` 경유 (수동 docker compose up 금지)
4. 스모크: `GET /api/reviews/garden` 200, 복습 채점 1회 후 `waterCount` 증가 확인
