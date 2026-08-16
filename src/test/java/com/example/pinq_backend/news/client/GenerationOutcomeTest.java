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
