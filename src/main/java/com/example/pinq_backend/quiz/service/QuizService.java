package com.example.pinq_backend.quiz.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.service.UserService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 도메인 유스케이스.
 *
 *  - {@link #getTodayQuizzes()}: 오늘 풀 4개 문제를 반환 (정답/해설/기사 미포함).
 *  - {@link #checkAnswer(Long, Long)}: 정답 채점 + 해설/기사/keyword 포함 응답.
 *    → 채점 결과를 UserService 에 전달해 스트릭/통계를 함께 갱신한다.
 *    → 제출한 choiceId 가 해당 퀴즈의 보기가 아니면 InvalidChoiceException(400) 을 던져
 *       통계 오염을 방지한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizService {

    private final QuizRepository quizRepository;
    private final UserService userService;
    private final Clock clock;

    public List<QuizResponse> getTodayQuizzes() {
        LocalDate today = LocalDate.now(clock);

        // 오늘 생성된 퀴즈가 있으면 반환, 없으면 Phase 2 시드 데이터(quizDate=null)만 폴백
        List<Quiz> quizzes = quizRepository.countByQuizDate(today) > 0
            ? quizRepository.findAllByQuizDateOrderByIdAsc(today)
            : quizRepository.findAllByQuizDateIsNullOrderByIdAsc();

        return quizzes.stream().map(QuizResponse::from).toList();
    }

    /** Phase 3 표준: 인증된 userId 기반 채점. */
    @Transactional
    public AnswerResponse checkAnswer(Long userId, Long quizId, Long selectedChoiceId) {
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new QuizNotFoundException(quizId));

        if (!quiz.hasChoice(selectedChoiceId)) {
            throw new InvalidChoiceException(quizId, selectedChoiceId);
        }

        AnswerResponse response = AnswerResponse.of(quiz, selectedChoiceId);
        userService.recordAnswer(userId, quizId, selectedChoiceId, response.correct());
        return response;
    }

    /** Phase 2 하위 호환: demo 유저 기반 채점. */
    @Transactional
    public AnswerResponse checkAnswer(Long quizId, Long selectedChoiceId) {
        Quiz quiz = quizRepository.findById(quizId)
            .orElseThrow(() -> new QuizNotFoundException(quizId));

        if (!quiz.hasChoice(selectedChoiceId)) {
            throw new InvalidChoiceException(quizId, selectedChoiceId);
        }

        AnswerResponse response = AnswerResponse.of(quiz, selectedChoiceId);
        userService.recordAnswer(quizId, selectedChoiceId, response.correct());
        return response;
    }
}
