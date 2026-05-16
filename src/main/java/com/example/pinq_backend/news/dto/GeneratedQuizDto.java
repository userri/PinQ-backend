package com.example.pinq_backend.news.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI API가 생성한 퀴즈 데이터.
 * JSON 역직렬화 대상이므로 Jackson 기본 생성자가 필요한 일반 클래스로 선언.
 */
public class GeneratedQuizDto {

    private String question;
    private List<ChoiceDto> choices;
    private String explanation;
    private String keyword;

    public GeneratedQuizDto() {}

    public String getQuestion() { return question; }
    public List<ChoiceDto> getChoices() { return choices; }
    public String getExplanation() { return explanation; }
    public String getKeyword() { return keyword; }

    public static class ChoiceDto {
        private int orderNum;
        private String content;
        @JsonProperty("isAnswer")
        private boolean isAnswer;

        public ChoiceDto() {}

        public int getOrderNum() { return orderNum; }
        public String getContent() { return content; }
        public boolean isAnswer() { return isAnswer; }
    }
}
