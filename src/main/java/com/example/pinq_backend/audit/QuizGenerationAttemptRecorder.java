package com.example.pinq_backend.audit;

import com.example.pinq_backend.audit.domain.AttemptReason;
import com.example.pinq_backend.audit.domain.AttemptStage;
import com.example.pinq_backend.audit.domain.QuizGenerationAttempt;
import com.example.pinq_backend.audit.repository.QuizGenerationAttemptRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 퀴즈 생성 시도 1건을 남긴다.
 *
 * 두 가지를 지킨다 — {@code TokenUsageRecorder} 와 같은 계약이다:
 *  ① <b>계측이 본 기능을 깨지 않는다.</b> 저장 실패는 잡아서 삼킨다.
 *  ② <b>바깥 트랜잭션을 오염시키지 않는다.</b> REQUIRES_NEW 로 분리한다.
 *     {@code generateTodayQuizzes()} 전체가 @Transactional 이라, 같은 트랜잭션에 태우면
 *     계측 저장 실패가 rollback-only 를 찍어 <b>그날 퀴즈 생성이 통째로 롤백된다.</b>
 *     삼키기만 해서는 막을 수 없는 유형이라 전파 설정이 함께 필요하다.
 *
 * <p><b>{@code @Transactional} 애노테이션이 아니라 {@link TransactionTemplate} 을 쓰는 이유</b> —
 * DB 제약 위반(예: NOT NULL)은 flush 시점에 터지고, IDENTITY 채번 전략이라 {@code save()} 호출
 * 안에서 즉시 flush 된다. 그 예외 자체는 여기 {@code try} 로 잡히지만, Hibernate 세션은 그 뒤로
 * "commit 불가" 상태가 된다. {@code @Transactional} 애노테이션을 쓰면 커밋은 AOP 프록시가
 * <b>메서드 반환 이후</b>, 즉 이 메서드의 {@code try/catch} 바깥에서 수행한다 — 그래서 반환 시점에
 * 커밋이 {@code UnexpectedRollbackException} 으로 실패하면 그 예외가 그대로 호출부로 샌다
 * (실측: {@code QuizGenerationAttemptRecorderTest} 에서 NOT NULL 위반으로 직접 재현·확인함).
 * {@link TransactionTemplate} 은 트랜잭션 시작·커밋을 이 메서드 안에서, 즉 {@code try} 블록
 * 안에서 수행하므로 커밋 실패까지 여기서 잡힌다.
 */
@Slf4j
@Service
public class QuizGenerationAttemptRecorder {

    private final QuizGenerationAttemptRepository repository;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public QuizGenerationAttemptRecorder(QuizGenerationAttemptRepository repository, Clock clock,
                                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String category, String runWindow, String searchKeyword,
                       String articleTitle, String articleUrl,
                       AttemptStage stage, AttemptReason reason, String detail, Long quizId) {
        try {
            requiresNewTransaction.executeWithoutResult(status ->
                    repository.save(new QuizGenerationAttempt(
                            LocalDateTime.now(clock), category, runWindow,
                            searchKeyword, articleTitle, articleUrl, stage, reason, detail, quizId)));
        } catch (Exception e) {
            log.warn("생성 시도 계측 저장 실패 — 계측만 유실된다. category={}, stage={}, error={}",
                    category, stage, e.getMessage());
        }
    }
}
