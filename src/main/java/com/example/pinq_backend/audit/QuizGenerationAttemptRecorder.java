package com.example.pinq_backend.audit;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 생성 시도 1건을 남긴다.
 *
 * 두 가지를 지킨다 — {@code TokenUsageRecorder} 와 같은 계약이다:
 *  ① <b>계측이 본 기능을 깨지 않는다.</b> 저장 실패는 잡아서 삼킨다.
 *  ② <b>바깥 트랜잭션을 오염시키지 않는다.</b> REQUIRES_NEW 로 분리한다.
 *     {@code generateTodayQuizzes()} 전체가 @Transactional 이라, 같은 트랜잭션에 태우면
 *     계측 저장 실패가 rollback-only 를 찍어 <b>그날 퀴즈 생성이 통째로 롤백된다.</b>
 *     삼키기만 해서는 막을 수 없는 유형이라 전파 설정이 함께 필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizGenerationAttemptRecorder {

    private final QuizGenerationAttemptRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String category, String runWindow, String searchKeyword,
                       String articleTitle, String articleUrl,
                       AttemptStage stage, AttemptReason reason, String detail, Long quizId) {
        try {
            repository.save(new QuizGenerationAttempt(
                    LocalDateTime.now(clock), category, runWindow,
                    searchKeyword, articleTitle, articleUrl, stage, reason, detail, quizId));
        } catch (Exception e) {
            log.warn("생성 시도 계측 저장 실패 — 계측만 유실된다. category={}, stage={}, error={}",
                    category, stage, e.getMessage());
        }
    }
}
