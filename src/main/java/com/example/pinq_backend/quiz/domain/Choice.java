package com.example.pinq_backend.quiz.domain;

import com.example.pinq_backend.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 4지선다의 보기 한 개.
 *
 * 도메인 규칙(상위 Quiz 가 강제):
 *  - 한 퀴즈에 정확히 4개의 Choice
 *  - 그 중 정확히 1개의 is_answer=true
 */
@Entity
@Table(name = "choice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Choice extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    /** 화면 표시 순서 (1~4). */
    @Column(name = "order_num", nullable = false)
    private int orderNum;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "is_answer", nullable = false)
    private boolean answer;

    @Builder
    private Choice(int orderNum, String content, boolean answer) {
        this.orderNum = orderNum;
        this.content = content;
        this.answer = answer;
    }

    /** Quiz 내부에서만 호출 (양방향 연관관계). */
    void assignQuiz(Quiz quiz) {
        this.quiz = quiz;
    }
}
