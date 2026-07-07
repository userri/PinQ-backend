package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
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
}
