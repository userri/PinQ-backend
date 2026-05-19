package com.example.pinq_backend.quiz.service;

import com.example.pinq_backend.quiz.domain.Quiz;
import com.example.pinq_backend.quiz.dto.AnswerResponse;
import com.example.pinq_backend.quiz.dto.QuizResponse;
import com.example.pinq_backend.quiz.exception.InvalidChoiceException;
import com.example.pinq_backend.quiz.exception.QuizNotFoundException;
import com.example.pinq_backend.quiz.repository.QuizRepository;
import com.example.pinq_backend.user.domain.UserQuizAttempt;
import com.example.pinq_backend.user.repository.UserQuizAttemptRepository;
import com.example.pinq_backend.user.service.UserService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 도메인 유스케이스.
 *
 *  - {@link #getTodayQuizzes(Long)}: 오늘의 퀴즈 전체를 풀이 상태(solved/correct)와 함께 반환.
 *    과거에는 푼 퀴즈를 리스트에서 제외했으나, 1개를 풀고 돌아오면 "3개 준비"로 보이는
 *    UX 부자연스러움이 있어 전체를 항상 반환하고 풀이 여부 표시를 프론트에 위임한다.
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
    private final UserQuizAttemptRepository userQuizAttemptRepository;
    private final UserService userService;
    private final Clock clock;

    public List<QuizResponse> getTodayQuizzes(Long userId) {
        LocalDate today = LocalDate.now(clock);

        // 오늘 생성된 퀴즈가 있으면 반환, 없으면 Phase 2 시드 데이터(quizDate=null)만 폴백
        List<Quiz> quizzes = quizRepository.countByQuizDate(today) > 0
            ? quizRepository.findAllByQuizDateOrderByIdAsc(today)
            : quizRepository.findAllByQuizDateIsNullOrderByIdAsc();

        if (quizzes.isEmpty()) return List.of();

        // 사용자의 attempt 를 quizId 기준으로 한 번에 조회 → N+1 방지.
        List<Long> quizIds = quizzes.stream().map(Quiz::getId).toList();
        Map<Long, UserQuizAttempt> attemptByQuizId = userQuizAttemptRepository
            .findByUserIdAndQuizIdIn(userId, quizIds).stream()
            .collect(Collectors.toMap(
                UserQuizAttempt::getQuizId,
                Function.identity(),
                (a, b) -> a // (user, quiz) UK 보장으로 실제 중복은 없음. BookmarkService 와 일관성 유지.
            ));

        // 전체 퀴즈를 반환하되 attempt 가 있으면 solved=true, correct=firstCorrect 로 채움
        return quizzes.stream()
            .map(q -> {
                UserQuizAttempt attempt = attemptByQuizId.get(q.getId());
                boolean solved = attempt != null;
                Boolean correct = solved ? attempt.isFirstCorrect() : null;
                return QuizResponse.from(q, solved, correct);
            })
            .toList();
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
