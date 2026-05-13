package com.example.pinq_backend.quiz.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 도메인 유스케이스.
 *
 *  - {@link #getTodayQuizzes()}: 오늘 풀 4개 문제를 반환 (정답/해설/기사 미포함).
 *  - {@link #checkAnswer(Long, Long)}: 정답 채점 + 해설/기사/keyword 포함 응답.
 *
 * Phase 2 변경점: option → choice 명명 정렬, article 은 FK 로 연결됨.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizService {

    private final QuizRepository quizRepository;

    public List<QuizResponse> getTodayQuizzes() {
        return quizRepository.findAllByOrderByIdAsc().stream()
            .map(QuizResponse::from)
            .toList();
    }

    public AnswerResponse checkAnswer(Long quizId, Long selectedChoiceId) {
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new QuizNotFoundException(quizId));
        return AnswerResponse.of(quiz, selectedChoiceId);
    }
}
