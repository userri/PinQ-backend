package com.example.pinq_backend.news.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.article.domain.Category;
import java.util.List;
import org.junit.jupiter.api.Test;

class AxisLabelPromptTest {

    @Test
    void 기존_라벨이_재사용_목록으로_들어간다() {
        String p = OpenAIQuizClient.axisLabelPrompt(
                "엔화 약세가 지속될 때 각국이 정책을 조정하는 주된 이유는?",
                "엔 캐리트레이드: 저금리 엔화를 빌려 고수익 자산에 투자하는 거래",
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
