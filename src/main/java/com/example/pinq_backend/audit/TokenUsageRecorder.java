package com.example.pinq_backend.audit;

import com.example.pinq_backend.audit.domain.TokenUsage;
import com.example.pinq_backend.audit.repository.TokenUsageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 토큰 사용량 1건을 남긴다.
 *
 * 두 가지를 지킨다:
 *  ① <b>계측이 본 기능을 깨지 않는다.</b> 저장 실패는 잡아서 삼킨다 — 토큰을 못 남기는 것보다
 *     퀴즈 생성이 멈추는 쪽이 훨씬 나쁘다.
 *  ② <b>바깥 트랜잭션을 오염시키지 않는다.</b> REQUIRES_NEW 로 분리한다. 같은 트랜잭션에 태우면
 *     저장 실패가 rollback-only 를 찍어 <b>생성 트랜잭션까지 함께 죽는다</b> — 삼키기만 해서는
 *     막을 수 없는 유형이라 전파 설정이 함께 필요하다.
 *
 * <p><b>{@code @Transactional} 애노테이션이 아니라 {@link TransactionTemplate} 을 쓰는 이유</b> —
 * 애노테이션을 쓰면 커밋을 AOP 프록시가 <b>메서드 반환 이후</b>, 즉 아래 {@code try/catch} 바깥에서
 * 한다. DB 제약 위반은 flush 시점에 터지고 그 예외 자체는 {@code catch} 에 잡히지만, 세션이
 * "commit 불가"가 되어 프록시의 커밋이 {@code UnexpectedRollbackException} 으로 실패하고
 * <b>그 예외는 호출부로 샌다</b> — ① 이 무너지는 지점이다. {@link TransactionTemplate} 은
 * 시작·커밋이 {@code try} 블록 안에서 끝나므로 커밋 실패까지 여기서 잡힌다.
 * (2026-08-16 {@code QuizGenerationAttemptRecorder} 에서 먼저 발견·수정한 것과 같은 결함이고,
 * 2026-08-17 에 이 클래스에서도 {@code kind=null} NOT NULL 위반으로 재현한 뒤 고쳤다.)
 */
@Slf4j
@Service
public class TokenUsageRecorder {

    private final TokenUsageRepository repository;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public TokenUsageRecorder(TokenUsageRepository repository, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String kind, String model, int promptTokens, int completionTokens,
                       int cacheWriteTokens, int cacheReadTokens, int totalTokens) {
        try {
            requiresNewTransaction.executeWithoutResult(status ->
                    repository.save(new TokenUsage(
                            LocalDateTime.now(clock), kind, model,
                            promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens,
                            totalTokens)));
        } catch (Exception e) {
            log.warn("token-usage 저장 실패 — 계측만 유실된다. kind={}, error={}", kind, e.getMessage());
        }
    }
}
