package com.example.pinq_backend.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.news.dto.GeneratedQuizDto;
import com.example.pinq_backend.quiz.domain.Choice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuizGenerationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("생성 AI가 준 보기 순서를 저장 전에 섞고 orderNum을 1부터 다시 부여한다")
    void buildChoicesForDisplay_shufflesChoicesAndReassignsOrderNum() throws Exception {
        GeneratedQuizDto dto = objectMapper.readValue("""
            {
              "skip": false,
              "question": "기준금리가 오르면 대출 시장에는 어떤 변화가 먼저 나타나는가?",
              "choices": [
                {"orderNum": 1, "content": "대출 이자 부담이 줄어든다", "isAnswer": false},
                {"orderNum": 2, "content": "대출 이자 부담이 커진다", "isAnswer": true},
                {"orderNum": 3, "content": "대출 한도가 자동으로 늘어난다", "isAnswer": false},
                {"orderNum": 4, "content": "예금 금리가 반드시 하락한다", "isAnswer": false}
              ],
              "explanation": "기준금리 인상은 시장금리 상승으로 이어져 대출 이자 부담을 키운다.",
              "keyword": "기준금리: 중앙은행이 통화정책의 기준으로 삼는 금리"
            }
            """, GeneratedQuizDto.class);

        List<Choice> choices = QuizGenerationService.buildChoicesForDisplay(dto, new Random(0L));

        assertThat(choices).hasSize(4);
        assertThat(choices.stream().map(Choice::getOrderNum).toList())
                .containsExactly(1, 2, 3, 4);
        assertThat(choices.stream().map(Choice::getContent).toList())
                .isNotEqualTo(List.of(
                        "대출 이자 부담이 줄어든다",
                        "대출 이자 부담이 커진다",
                        "대출 한도가 자동으로 늘어난다",
                        "예금 금리가 반드시 하락한다"
                ));
        assertThat(choices.stream().filter(Choice::isAnswer).toList())
                .singleElement()
                .extracting(Choice::getContent)
                .isEqualTo("대출 이자 부담이 커진다");
    }

    @Test
    @DisplayName("같은 퀴즈 내용은 같은 저장 순서를 만든다")
    void choiceShuffleSeed_sameQuiz_returnsSameSeed() throws Exception {
        GeneratedQuizDto dto = objectMapper.readValue("""
            {
              "skip": false,
              "question": "환율 상승은 수입 기업에 어떤 부담을 주는가?",
              "choices": [
                {"orderNum": 1, "content": "수입 원가가 낮아진다", "isAnswer": false},
                {"orderNum": 2, "content": "수입 원가가 높아진다", "isAnswer": true},
                {"orderNum": 3, "content": "외화 결제 부담이 사라진다", "isAnswer": false},
                {"orderNum": 4, "content": "원화 표시 비용이 항상 줄어든다", "isAnswer": false}
              ],
              "explanation": "환율이 오르면 같은 달러 금액을 결제하기 위해 더 많은 원화가 필요하다.",
              "keyword": "환율: 한 나라 통화와 다른 나라 통화의 교환 비율"
            }
            """, GeneratedQuizDto.class);

        long seed = QuizGenerationService.choiceShuffleSeed(dto);

        assertThat(QuizGenerationService.buildChoicesForDisplay(dto, new Random(seed))
                .stream()
                .map(Choice::getContent)
                .toList())
                .isEqualTo(QuizGenerationService.buildChoicesForDisplay(dto, new Random(seed))
                        .stream()
                        .map(Choice::getContent)
                        .toList());
    }
}
