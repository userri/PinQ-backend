package com.example.pinq_backend.audit;

import com.example.pinq_backend.audit.domain.TokenUsage;
import com.example.pinq_backend.audit.repository.TokenUsageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 사용량 1건을 남긴다.
 *
 * 두 가지를 지킨다:
 *  ① <b>계측이 본 기능을 깨지 않는다.</b> 저장 실패는 잡아서 삼킨다 — 토큰을 못 남기는 것보다
 *     퀴즈 생성이 멈추는 쪽이 훨씬 나쁘다.
 *  ② <b>바깥 트랜잭션을 오염시키지 않는다.</b> REQUIRES_NEW 로 분리한다. 같은 트랜잭션에 태우면
 *     저장 실패가 rollback-only 를 찍어 <b>생성 트랜잭션까지 함께 죽는다</b> — 삼키기만 해서는
 *     막을 수 없는 유형이라 전파 설정이 함께 필요하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageRecorder {

    private final TokenUsageRepository repository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String kind, String model, int promptTokens, int completionTokens,
                       int cacheWriteTokens, int cacheReadTokens, int totalTokens) {
        try {
            repository.save(new TokenUsage(
                    LocalDateTime.now(clock), kind, model,
                    promptTokens, completionTokens, cacheWriteTokens, cacheReadTokens, totalTokens));
        } catch (Exception e) {
            log.warn("token-usage 저장 실패 — 계측만 유실된다. kind={}, error={}", kind, e.getMessage());
        }
    }
}
