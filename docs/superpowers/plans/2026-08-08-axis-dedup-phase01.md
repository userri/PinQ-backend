# axis 재등장 차단 0·1단계 (토큰 계측 + 명명 수렴성 dry-run) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenAI/Anthropic 호출의 토큰 사용량을 로그로 남기고(0단계), 과거 발행분에 axis 라벨링을 돌려 명명 수렴성을 측정하는 읽기 전용 admin 엔드포인트를 만든다(1단계).

**Architecture:** 스펙 `docs/superpowers/specs/2026-08-08-axis-dedup-design.md` 의 0·1단계만. 2단계(axis 컬럼+가드)는 1단계 go/no-go 결과가 나온 뒤 별도 계획으로 — 가드 기간·크로스 카테고리 여부가 1단계 산출물에 의존하므로 지금 계획하면 전부 가정이 된다. DB 무변경: 라벨은 응답으로만 반환하고 저장하지 않는다.

**Tech Stack:** Spring Boot 3, Java 21, JUnit5 + Mockito (기존 테스트 관례), OpenAI chat completions (기존 `OpenAIQuizClient` 의 RestClient 재사용).

## Global Constraints

- 커밋 메시지에 AI 흔적(Co-Authored-By 등) 금지 (전역 CLAUDE.md)
- 커밋 후 push 까지 실행 (사용자 선호). 단 **서버 컨테이너 재생성은 CI 완료 후 사용자 확인** 하에
- 라벨링 엔드포인트는 DB 무변경·이력 미등록 (스펙 1단계)
- 토큰 로그는 grep 가능한 고정 포맷: `token-usage kind=<generate|verify|label-axis> prompt=<n> completion=<n> total=<n>` (스펙 0단계)
- admin 인증은 기존 `AdminAuthFilter` (`X-Admin-Secret`) — `/api/admin/**` 하위면 자동 적용, 추가 설정 불필요

---

### Task 1: 토큰 사용량 로깅 (0단계)

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/news/client/OpenAIQuizClient.java` (rawResponse 파싱 직후)
- Modify: `src/main/java/com/example/pinq_backend/news/client/AnthropicVerifyClient.java` (응답 파싱 지점)
- Create: `src/main/java/com/example/pinq_backend/news/client/TokenUsageLogger.java`
- Test: `src/test/java/com/example/pinq_backend/news/client/TokenUsageLoggerTest.java`

**Interfaces:**
- Produces: `TokenUsageLogger.format(String kind, JsonNode responseRoot)` → `String` (usage 노드가 없으면 `null` 반환 — 호출부는 null 이면 로그 생략). OpenAI(`usage.prompt_tokens/completion_tokens/total_tokens`)와 Anthropic(`usage.input_tokens/output_tokens`) 양쪽 키를 인식한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TokenUsageLoggerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void openAI_usage_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"prompt_tokens": 5000, "completion_tokens": 300, "total_tokens": 5300}}
                """);
        assertThat(TokenUsageLogger.format("generate", root))
                .isEqualTo("token-usage kind=generate prompt=5000 completion=300 total=5300");
    }

    @Test
    void anthropic_usage_포맷() throws Exception {
        var root = om.readTree("""
                {"usage": {"input_tokens": 4200, "output_tokens": 120}}
                """);
        assertThat(TokenUsageLogger.format("verify", root))
                .isEqualTo("token-usage kind=verify prompt=4200 completion=120 total=4320");
    }

    @Test
    void usage_노드_없으면_null() throws Exception {
        assertThat(TokenUsageLogger.format("generate", om.readTree("{}"))).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests TokenUsageLoggerTest`
Expected: 컴파일 실패 (TokenUsageLogger 없음)

- [ ] **Step 3: 구현**

```java
package com.example.pinq_backend.news.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM 응답의 usage(토큰 수)를 grep 가능한 한 줄로 만든다.
 *
 * 목적: axis 실험(스펙 2026-08-08-axis-dedup-design.md 0단계)의 토큰 전후 비교 기준선.
 * OpenAI(prompt_tokens/completion_tokens)와 Anthropic(input_tokens/output_tokens)
 * 양쪽 키를 인식한다. usage 가 없으면 null — 호출부는 로그를 생략한다.
 */
final class TokenUsageLogger {

    private TokenUsageLogger() {}

    static String format(String kind, JsonNode responseRoot) {
        JsonNode usage = responseRoot.path("usage");
        if (usage.isMissingNode() || usage.isNull()) return null;

        int prompt = usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(-1));
        int completion = usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(-1));
        if (prompt < 0 || completion < 0) return null;

        int total = usage.path("total_tokens").asInt(prompt + completion);
        return "token-usage kind=%s prompt=%d completion=%d total=%d"
                .formatted(kind, prompt, completion, total);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests TokenUsageLoggerTest`
Expected: PASS

- [ ] **Step 5: 호출부 연결**

`OpenAIQuizClient.generateQuiz` — `parseQuiz(rawResponse)` 는 내부에서 root 를 다시 읽으므로, 호출부에서 한 번 읽어 로그만 남긴다 (파싱 실패해도 로그는 남도록 parseQuiz 앞에):

```java
// rawResponse 수신 직후 (String rawResponse = restClient...body(String.class); 다음 줄)
logTokenUsage("generate", rawResponse);
```

`OpenAIQuizClient` 에 private 헬퍼 추가:

```java
/** 응답 usage 를 로그로 남긴다. 파싱 실패는 무시 — 계측이 본 기능을 깨면 안 된다. */
private void logTokenUsage(String kind, String rawResponse) {
    try {
        String line = TokenUsageLogger.format(kind, objectMapper.readTree(rawResponse));
        if (line != null) log.info(line);
    } catch (Exception e) {
        log.debug("token-usage 파싱 실패. kind={}", kind, e);
    }
}
```

`AnthropicVerifyClient.verify` — 응답 문자열을 받는 지점에 동일 패턴 (`objectMapper` 필드 이미 있음). kind 는 `"verify"`. `verify()` 안에서 응답을 JsonNode 로 이미 읽고 있으면 그 노드를 재사용해 `TokenUsageLogger.format("verify", root)` 호출로 갈음한다 (중복 파싱 금지).

- [ ] **Step 6: 전체 테스트 + 커밋**

Run: `./gradlew test`
Expected: 전체 PASS (기존 182개 + 신규 3개)

```bash
git add src/main/java/com/example/pinq_backend/news/client/ src/test/java/com/example/pinq_backend/news/client/TokenUsageLoggerTest.java
git commit -m "feat: LLM 호출 토큰 사용량 로깅 (axis 실험 기준선)"
```

---

### Task 2: axis 라벨링 클라이언트 메서드 (1단계)

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/news/client/OpenAIQuizClient.java`
- Test: `src/test/java/com/example/pinq_backend/news/client/AxisLabelPromptTest.java`

**Interfaces:**
- Produces: `OpenAIQuizClient.labelAxis(String question, String keyword, Category category, List<String> knownAxes)` → `Optional<String>` (라벨 문자열, 실패 시 empty). `static String axisLabelPrompt(String question, String keyword, Category category, List<String> knownAxes)` (테스트 가능하게 package-private static).

- [ ] **Step 1: 프롬프트 빌더 테스트 작성**

```java
package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
import java.util.List;
import org.junit.jupiter.api.Test;

class AxisLabelPromptTest {

    @Test
    void 기존_라벨이_재사용_목록으로_들어간다() {
        String p = OpenAIQuizClient.axisLabelPrompt(
                "엔화 약세가 지속될 때 …?", "엔 캐리트레이드: …",
                Category.EXCHANGE_RATE, List.of("엔화 약세", "미국 금리 인하 기대"));
        assertThat(p).contains("엔화 약세");
        assertThat(p).contains("미국 금리 인하 기대");
        assertThat(p).contains("반드시 그 표기를 그대로");
    }

    @Test
    void 기존_라벨이_없으면_재사용_섹션_생략() {
        String p = OpenAIQuizClient.axisLabelPrompt(
                "질문", "용어: 정의", Category.STOCK, List.of());
        assertThat(p).doesNotContain("기존 축 목록");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests AxisLabelPromptTest`
Expected: 컴파일 실패 (axisLabelPrompt 없음)

- [ ] **Step 3: 구현**

`OpenAIQuizClient` 에 추가:

```java
/**
 * axis(뉴스 사건 축) 라벨링 프롬프트 — 명명 수렴성 dry-run 전용 (스펙 1단계).
 *
 * 핵심: 사건은 고유명사가 아니라 자유 명명이면 같은 사건도 날마다 다르게 적힌다.
 * 그래서 이미 부여된 라벨 목록을 보여주고 "같은 사건이면 반드시 그 표기 재사용"을
 * 지시한다 — 2단계 결정적 가드(문자열 동일 비교)가 성립하는지가 이 실험의 판정 대상.
 */
static String axisLabelPrompt(String question, String keyword, Category category,
        List<String> knownAxes) {
    String reuseSection = "";
    if (knownAxes != null && !knownAxes.isEmpty()) {
        reuseSection = """

                [기존 축 목록 — 재사용 우선]
                %s
                ※ 이 문항의 축이 위 목록의 사건과 같으면 반드시 그 표기를 그대로 쓰세요.
                   정말 새로운 사건일 때만 새 이름을 붙이세요.
                """.formatted(String.join("\n", knownAxes.stream().map(a -> "- " + a).toList()));
    }
    return """
            다음 경제 퀴즈 문항이 딛고 선 "뉴스 사건 축"을 한 구절로 명명하세요.

            축은 문항이 가르치는 개념(keyword)이 아니라, 그 배경이 된 현실 세계의
            사건·상황입니다. 예: 문항의 keyword 가 "엔 캐리트레이드"여도 배경 사건이
            엔화 가치 하락이면 축은 "엔화 약세"입니다.

            카테고리: %s (%s)
            질문: %s
            keyword: %s
            %s
            응답은 JSON 한 줄: {"axis": "축 이름"}
            축 이름은 15자 이내 명사구. 설명·마크다운 금지.
            """.formatted(category.name(), category.getDisplayName(), question, keyword, reuseSection);
}

/**
 * 저장된 문항 하나에 axis 라벨을 부여한다 (DB 무변경, 이력 미등록).
 * 실패 시 empty — 호출부(dry-run 집계)는 해당 문항을 "라벨 실패"로 센다.
 */
public Optional<String> labelAxis(String question, String keyword, Category category,
        List<String> knownAxes) {
    Map<String, Object> requestBody = Map.of(
            "model", props.model(),
            "max_tokens", 100,
            "messages", List.of(
                    Map.of("role", "user", "content",
                            axisLabelPrompt(question, keyword, category, knownAxes))
            )
    );
    try {
        String rawResponse = restClient.post()
                .uri(OPENAI_API_URL)
                .body(requestBody)
                .retrieve()
                .body(String.class);
        logTokenUsage("label-axis", rawResponse);

        JsonNode root = objectMapper.readTree(rawResponse);
        String text = root.path("choices").get(0).path("message").path("content").asText();
        String json = text.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
        String axis = objectMapper.readTree(json).path("axis").asText(null);
        return (axis == null || axis.isBlank()) ? Optional.empty() : Optional.of(axis.trim());
    } catch (Exception e) {
        log.warn("axis 라벨링 실패. question={}", question, e);
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests AxisLabelPromptTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/pinq_backend/news/client/OpenAIQuizClient.java src/test/java/com/example/pinq_backend/news/client/AxisLabelPromptTest.java
git commit -m "feat: axis 라벨링 클라이언트 (명명 수렴성 dry-run용)"
```

---

### Task 3: 라벨링 서비스 — 날짜순 증분 라벨링 + 차단 시뮬레이션

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/quiz/service/QuizGenerationService.java`
- Create: `src/main/java/com/example/pinq_backend/quiz/dto/AxisLabelResponse.java`
- Test: `src/test/java/com/example/pinq_backend/quiz/service/AxisLabelDryRunTest.java`

**Interfaces:**
- Consumes: `OpenAIQuizClient.labelAxis(String, String, Category, List<String>)` → `Optional<String>` (Task 2)
- Produces: `QuizGenerationService.labelAxes(Category category, int days)` → `AxisLabelResponse`

```java
// AxisLabelResponse.java
package com.example.pinq_backend.quiz.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * axis 명명 수렴성 dry-run 결과 (스펙 2026-08-08-axis-dedup-design.md 1단계).
 *
 * wouldBlock: 이 문항의 axis 가 직전 7일 내(같은 카테고리) 이미 부여된 라벨과
 * 문자열 동일이면 true — 2단계 가드를 켰다면 폐기됐을 문항. 합계가 곧
 * "잃을 발행 수의 상한"이다 (개념 포화 충돌의 사전 측정).
 */
public record AxisLabelResponse(
        String category,
        int days,
        int labeled,
        int labelFailed,
        int wouldBlockCount,
        List<Item> items
) {
    public record Item(Long quizId, LocalDate quizDate, String term, String axis, boolean wouldBlock) {}
}
```

- [ ] **Step 1: 실패하는 테스트 작성**

핵심 로직(증분 주입·7일 창 차단 판정)을 목으로 검증한다. `labelAxis` 가 순서대로 반환할 값을 스텁하고, ① 앞 문항의 라벨이 뒤 문항 호출의 knownAxes 로 들어가는가 ② 7일 내 동일 라벨이 wouldBlock=true 인가 ③ 7일 밖이면 false 인가를 본다.

```java
package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.quiz.dto.AxisLabelResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// 기존 QuizGenerationService 테스트가 쓰는 목 구성 관례를 그대로 따른다
// (QuizGenerationServiceDedupTest 참조 — repository·client 목 주입 방식 복사).
class AxisLabelDryRunTest {

    // 구성: quizRepository.findAllByQuizDateGreaterThanEqual(...) 이
    // 다음 3개를 반환하도록 스텁 (헬퍼로 Quiz 픽스처 생성 — Dedup 테스트의 빌더 재사용):
    //  - id=1, quizDate=8/1, category=EXCHANGE_RATE, keyword="공동 외환시장 개입: …"
    //  - id=2, quizDate=8/3, category=EXCHANGE_RATE, keyword="엔 캐리트레이드: …"
    //  - id=3, quizDate=8/12, category=EXCHANGE_RATE, keyword="경쟁적 평가절하: …"
    // labelAxis 는 순서대로 "엔화 약세", "엔화 약세", "엔화 약세" 반환.

    @Test
    void 앞_라벨이_뒤_호출의_knownAxes_로_들어간다() {
        // given 위 구성
        // when labelAxes(EXCHANGE_RATE, 30)
        // then ArgumentCaptor 로 3번째 labelAxis 호출의 knownAxes 에 "엔화 약세" 포함
    }

    @Test
    void 칠일_내_동일_라벨은_wouldBlock() {
        // id=2 (8/3, 직전 8/1 과 2일 간격 동일 라벨) → wouldBlock=true
        // id=3 (8/12, 직전 8/3 과 9일 간격) → wouldBlock=false
        // wouldBlockCount == 1
    }

    @Test
    void 라벨_실패는_실패_카운트로_센다() {
        // labelAxis 가 두 번째 호출에서 Optional.empty() → labelFailed=1, items 에서 제외
    }
}
```

(플레이스홀더 금지 원칙에 따라: 구현 시 위 주석 시나리오를 실제 given/when/then 코드로 완성한다. Quiz 픽스처 생성이 `QuizGenerationServiceDedupTest` 와 겹치면 공용 테스트 헬퍼로 추출하지 말고 각자 둔다 — 두 테스트의 독립성 유지.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests AxisLabelDryRunTest`
Expected: 컴파일 실패 (labelAxes 없음)

- [ ] **Step 3: 구현**

`QuizGenerationService` 에 추가:

```java
/** axis 가드 시뮬레이션 창 (일). 2단계 AXIS_GUARD_DAYS 기본 후보와 같은 값. */
private static final int AXIS_DRY_RUN_WINDOW_DAYS = 7;

/**
 * 명명 수렴성 dry-run (스펙 1단계) — DB 무변경, 이력 미등록.
 *
 * 과거 발행분을 quiz_date 오름차순으로 라벨링하되, 이미 부여한 라벨을
 * 다음 호출의 재사용 목록으로 넘긴다 — 프로덕션의 증분 생성 흐름을 모사해야
 * 수렴성 측정이 실제와 같은 조건이 된다 (한 번에 전부 보여주면 과대평가).
 */
@Transactional(readOnly = true)
public AxisLabelResponse labelAxes(Category category, int days) {
    LocalDate today = LocalDate.now(clock);
    List<Quiz> quizzes = quizRepository.findAllByQuizDateGreaterThanEqual(today.minusDays(days))
            .stream()
            .filter(q -> q.getCategory() == category)
            .sorted(Comparator.comparing(Quiz::getQuizDate))
            .toList();

    List<AxisLabelResponse.Item> items = new ArrayList<>();
    List<String> knownAxes = new ArrayList<>();       // 부여 순서 유지 (재사용 목록)
    Map<String, LocalDate> lastSeen = new HashMap<>(); // axis → 마지막 등장일 (차단 판정)
    int failed = 0;

    for (Quiz quiz : quizzes) {
        Optional<String> axisOpt = openAIQuizClient.labelAxis(
                quiz.getQuestion(), quiz.getKeyword(), category, knownAxes);
        if (axisOpt.isEmpty()) {
            failed++;
            continue;
        }
        String axis = axisOpt.get();

        LocalDate prev = lastSeen.get(axis);
        boolean wouldBlock = prev != null
                && !prev.isBefore(quiz.getQuizDate().minusDays(AXIS_DRY_RUN_WINDOW_DAYS));

        items.add(new AxisLabelResponse.Item(
                quiz.getId(), quiz.getQuizDate(),
                extractKeywordTerm(quiz.getKeyword()), axis, wouldBlock));
        if (!knownAxes.contains(axis)) knownAxes.add(axis);
        lastSeen.put(axis, quiz.getQuizDate());
    }

    int blocked = (int) items.stream().filter(AxisLabelResponse.Item::wouldBlock).count();
    return new AxisLabelResponse(category.name(), days, items.size(), failed, blocked, items);
}
```

주의: `quiz.getCategory()` 는 article 경유 파생이다 (`loadDedupHistory` 주석의 알려진 한계 — 기사 재사용 시 카테고리가 어긋날 수 있음). dry-run 도 같은 프록시를 쓰므로 한계가 동일하다는 것을 javadoc 에 명시한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests AxisLabelDryRunTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/pinq_backend/quiz/ src/test/java/com/example/pinq_backend/quiz/service/AxisLabelDryRunTest.java
git commit -m "feat: axis 명명 수렴성 dry-run 서비스"
```

---

### Task 4: admin 엔드포인트

**Files:**
- Modify: `src/main/java/com/example/pinq_backend/quiz/controller/QuizGenerationController.java`

**Interfaces:**
- Consumes: `QuizGenerationService.labelAxes(Category, int)` → `AxisLabelResponse` (Task 3)
- Produces: `POST /api/admin/quizzes/label-axes?category=EXCHANGE_RATE&days=30`

- [ ] **Step 1: 엔드포인트 추가**

기존 `test-generate` 관례를 그대로 따른다 (category 필수 RequestParam — 전 카테고리 일괄은 카테고리당 ~30회 순차 LLM 호출이라 타임아웃 위험):

```java
/**
 * axis 명명 수렴성 dry-run (스펙 2026-08-08-axis-dedup-design.md 1단계).
 * DB 무변경 — 라벨은 응답으로만 반환한다. 카테고리당 최근 N일 발행분을
 * 날짜순 증분 라벨링하고 "가드를 켰다면 막혔을 건수"를 함께 센다.
 *
 * ⚠️ 카테고리당 문항 수만큼 순차 OpenAI 호출 → 수십 초 소요.
 *    nginx(60s)를 피해 컨테이너 안에서 localhost:8080 으로 호출할 것 (회귀 하네스와 동일).
 */
@PostMapping("/label-axes")
public ResponseEntity<AxisLabelResponse> labelAxes(
    @RequestParam("category") Category category,
    @RequestParam(value = "days", defaultValue = "30") int days
) {
    return ResponseEntity.ok(quizGenerationService.labelAxes(category, days));
}
```

import 추가: `com.example.pinq_backend.quiz.dto.AxisLabelResponse`.

- [ ] **Step 2: 전체 테스트**

Run: `./gradlew test`
Expected: 전체 PASS. (컨트롤러는 파라미터 바인딩과 위임뿐이라 전용 테스트를 만들지 않는다 — 기존 `generate`/`verify` 엔드포인트도 동일 관례)

- [ ] **Step 3: 커밋 + push**

```bash
git add src/main/java/com/example/pinq_backend/quiz/controller/QuizGenerationController.java
git commit -m "feat: axis 라벨링 dry-run admin 엔드포인트"
git push
```

---

### Task 5: 배포 + 실험 실행 (수동 게이트 포함)

**Files:** 없음 (운영 절차)

- [ ] **Step 1: CI 완료 확인 후 사용자 확인 하에 컨테이너 재생성**

서버 배포 순서 준수 (CI 배포 완료 **후에만** 재생성 — 과거 DB 다운 사고). 사용자에게 배포 타이밍 확인.

- [ ] **Step 2: 계측 확인 — 토큰 로그가 실제로 찍히는가**

배포 후 아침 발행(또는 test-generate 1회)에서:

```bash
# 서버에서 (읽기 전용 — 로그 확인)
docker logs --since 1h <live-container> 2>&1 | grep "token-usage"
```

Expected: `token-usage kind=generate …`, `kind=verify …` 라인. **이게 없으면 1단계로 넘어가지 않는다** (계측 확인 — 방법론 메모리).

- [ ] **Step 3: 라벨링 실행 — EXCHANGE_RATE 2회**

회귀 하네스와 같은 방식 (컨테이너 안 curl, 시크릿은 환경변수로):

```bash
docker exec -e S="$ADMIN_SECRET" <live-container> sh -c \
  'curl -s -X POST "http://localhost:8080/api/admin/quizzes/label-axes?category=EXCHANGE_RATE&days=30" \
   -H "X-Admin-Secret: $S"'
```

같은 명령을 2회 실행해 결과를 각각 저장한다 (라벨링도 확률적 — 재현성 확인).

- [ ] **Step 4: go/no-go 판정**

- 엔화 4건(385·390·395·410)이 응답에 있는지 확인 (days=30 이면 8/1~ 포함)
- **go**: 2회 실행 모두에서 4건 중 3건 이상이 동일 axis 라벨
- **no-go**: 라벨이 제각각 → axis 안 기각, 폴백(렉시컬 유사도) 검토로 전환
- 부수 산출물 기록: `wouldBlockCount` (카테고리별 — 나머지 4개 카테고리도 각 1회 실행해 합산), `token-usage kind=label-axis` 로그로 라벨링 비용 실측

- [ ] **Step 5: 결과 기록**

- `docs/quality-audit-log.md` 에 실험 항목 추가 (형식: 기존 "실험 (8/4 오전)" 항목 참조 — 대상·베이스라인·결과·판정)
- `docs/PENDING.md` 해당 항목 갱신 (go 면 "2단계 계획 수립" 으로, no-go 면 기각 사유와 폴백)
- 커밋 + push (`.md` 전용 — CI 스킵)

---

## Self-Review 결과

- 스펙 커버리지: 0단계→Task 1, 1단계→Task 2~5. 2단계·3단계는 의도적 제외 (go/no-go 의존, 본문 Architecture 에 명시)
- 토큰 전후 비교의 "후"는 2단계 배포 뒤에만 가능 — 이 계획은 기준선("전")과 라벨링 비용까지만 측정한다
- 타입 일치: `labelAxis(String, String, Category, List<String>)` — Task 2 정의 = Task 3 소비. `AxisLabelResponse` — Task 3 정의 = Task 4 소비
