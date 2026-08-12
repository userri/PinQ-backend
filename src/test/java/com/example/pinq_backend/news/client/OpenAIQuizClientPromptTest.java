package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 시스템 프롬프트 조립 검증.
 *
 * 앵커링 완화 개편(카테고리별 예시 분리 + 소재 복제 금지)의 구조가
 * 유지되는지 확인한다. 프롬프트 문구 전체를 고정하는 게 아니라,
 * 개편의 핵심 장치(예시 스코프, 금지 문구, 응답 형식)만 검증한다.
 */
class OpenAIQuizClientPromptTest {

    @Test
    @DisplayName("각 카테고리 프롬프트에는 해당 카테고리 예시만 노출된다")
    void systemPrompt_containsOnlyOwnCategoryExamples() {
        String stock = OpenAIQuizClient.systemPrompt(Category.STOCK);
        assertThat(stock).contains("PER");            // STOCK 예시
        assertThat(stock).doesNotContain("콜금리");     // INTEREST_RATE 예시
        assertThat(stock).doesNotContain("명목환율");    // EXCHANGE_RATE 예시
        assertThat(stock).doesNotContain("LTV");       // REAL_ESTATE 예시

        String interestRate = OpenAIQuizClient.systemPrompt(Category.INTEREST_RATE);
        assertThat(interestRate).contains("콜금리");
        assertThat(interestRate).doesNotContain("PER");
    }

    @ParameterizedTest
    @EnumSource(Category.class)
    @DisplayName("모든 카테고리에서 프롬프트가 온전히 조립된다 (예시 2개 + 섹션 구조 + JSON 응답 형식)")
    void systemPrompt_rendersCompletelyForAllCategories(Category category) {
        String prompt = OpenAIQuizClient.systemPrompt(category);

        assertThat(prompt)
                .contains("## 핵심 경제 인과 룰북")
                .contains("환율 상승(원화 약세) → 수출 증가")   // 공유 룰북 본문이 조립됨
                .contains("예시 1 —")
                .contains("예시 2 —")
                .contains("## 나쁜 문제 예시")
                .contains("## 응답 형식")
                .contains("{\"skip\": true");
    }

    @ParameterizedTest
    @EnumSource(Category.class)
    @DisplayName("앵커링 방지 문구가 포함된다 (룰북 출제 소재 금지 + 예시 복제 금지)")
    void systemPrompt_containsAnchorGuards(Category category) {
        String prompt = OpenAIQuizClient.systemPrompt(category);

        assertThat(prompt)
                .contains("출제 소재 목록이 아닙니다")
                .contains("'이미 출제된 것'으로 간주");
    }

    // ── 검증 프롬프트 ────────────────────────────────────────────────────────

    /**
     * 2026-08-04 기준 16 채택(4140dd5)에서 카테고리와 룰북 인자가 뒤바뀐 채 8일간 발행됐다.
     * 검증기는 "배정된 카테고리" 자리에서 룰북 전문을, "인과 룰북" 자리에서 카테고리명을 읽었고,
     * 기준 16(카테고리 정합)은 판정 근거가 없어 사실상 동작하지 않았다.
     *
     * 조립이 HTTP 호출과 한 메서드에 묶여 있어 단위 테스트가 불가능했던 것이 8일을 만든 원인이라,
     * 여기서는 문구가 아니라 **어떤 값이 어느 자리에 들어갔는지**를 고정한다.
     */
    @ParameterizedTest
    @EnumSource(Category.class)
    @DisplayName("검증 프롬프트: 카테고리 자리엔 카테고리가, 룰북 자리엔 룰북이 들어간다")
    void verifyPrompt_placesCategoryAndRulebookInTheirOwnSlots(Category category) {
        String prompt = OpenAIQuizClient.verifyPrompt(sampleQuiz(), category, null, null);

        String categorySlot = between(prompt, "이 문항이 배정된 카테고리:", "경제 인과 룰북");
        String rulebookSlot = between(prompt, "경제 인과 룰북 (방향 판정의 절대 기준):", "문제:");

        // 카테고리 자리 — 카테고리명 한 줄이지, 룰북이 통째로 들어오면 안 된다
        assertThat(categorySlot)
                .contains(category.name())
                .doesNotContain("환율 상승(원화 약세) → 수출 증가");
        assertThat(categorySlot.lines().count()).isLessThan(3);

        // 룰북 자리 — 룰북 본문이지, 카테고리명 한 단어가 아니다
        assertThat(rulebookSlot)
                .contains("환율 상승(원화 약세) → 수출 증가");
        assertThat(rulebookSlot.length()).isGreaterThan(200);
    }

    @Test
    @DisplayName("검증 프롬프트: 문항 필드가 각자 제 자리에 들어간다")
    void verifyPrompt_placesQuizFieldsInTheirOwnSlots() {
        GeneratedQuizDto quiz = sampleQuiz();
        String prompt = OpenAIQuizClient.verifyPrompt(quiz, Category.STOCK, null, null);

        assertThat(between(prompt, "문제:", "전체 보기:")).contains("표본 질문");
        assertThat(between(prompt, "정답으로 표시된 보기:", "해설:")).contains("정답 보기");
        assertThat(between(prompt, "해설:", "핵심 용어(keyword):")).contains("표본 해설");
        assertThat(between(prompt, "핵심 용어(keyword):", "검증 기준:")).contains("표본 용어");
    }

    /** 두 라벨 사이의 값 구간. 라벨이 없으면 테스트가 의미를 잃으므로 즉시 실패시킨다. */
    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertThat(from).as("라벨 '%s' 이 프롬프트에 없다", start).isNotNegative();
        from += start.length();
        int to = text.indexOf(end, from);
        assertThat(to).as("라벨 '%s' 이 '%s' 뒤에 없다", end, start).isNotNegative();
        return text.substring(from, to);
    }

    private static GeneratedQuizDto sampleQuiz() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                    {
                      "skip": false,
                      "question": "표본 질문입니다. 무엇이 맞는가?",
                      "choices": [
                        {"orderNum": 1, "content": "오답 보기1", "isAnswer": false},
                        {"orderNum": 2, "content": "정답 보기입니다", "isAnswer": true},
                        {"orderNum": 3, "content": "오답 보기3", "isAnswer": false},
                        {"orderNum": 4, "content": "오답 보기4", "isAnswer": false}
                      ],
                      "explanation": "표본 해설입니다.",
                      "keyword": "표본 용어: 한 줄 정의"
                    }
                    """, GeneratedQuizDto.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
