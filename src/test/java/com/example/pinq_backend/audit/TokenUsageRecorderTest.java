package com.example.pinq_backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pinq_backend.audit.repository.TokenUsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 영속 계측이 실제로 남고, 검수가 쓰는 롤업 모양으로 읽히는지 고정한다.
 *
 * 로그 문자열만 검증하던 종전 테스트(TokenUsageLoggerTest)로는 "재시작하면 사라진다"는
 * 문제를 못 잡았다 — 저장 경로는 저장 경로대로 테스트가 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TokenUsageRecorderTest {

    @Autowired
    private TokenUsageRecorder recorder;

    @Autowired
    private TokenUsageRepository repository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 저장된_사용량이_날짜_kind_로_롤업된다() {
        repository.deleteAll();

        recorder.record("verify", "claude-opus-4-8", 3400, 105, 2798, 0, 6303);
        recorder.record("verify", "claude-opus-4-8", 3391, 135, 0, 2798, 6324);
        recorder.record("generate", "gpt-4.1-mini", 5410, 89, 0, 0, 5499);

        List<TokenUsageRepository.DailyRow> rows =
                repository.rollupSince(LocalDate.now(clock).minusDays(1));

        assertThat(rows).hasSize(2);

        var generate = rows.stream().filter(r -> r.getKind().equals("generate")).findFirst().orElseThrow();
        assertThat(generate.getCalls()).isEqualTo(1);
        assertThat(generate.getCacheHits()).isZero();

        var verify = rows.stream().filter(r -> r.getKind().equals("verify")).findFirst().orElseThrow();
        assertThat(verify.getCalls()).isEqualTo(2);
        // 캐싱 성과 지표는 토큰 합이 아니라 이 카운트다 — read>0 인 회차만 센다.
        assertThat(verify.getCacheHits()).isEqualTo(1);
        assertThat(verify.getCacheReadTokens()).isEqualTo(2798);
        assertThat(verify.getTotalTokens()).isEqualTo(6303 + 6324);
    }

    /**
     * 계측 실패가 본 기능을 깨면 안 된다 — 저장이 터져도 예외가 호출부로 새지 않는다.
     *
     * ⚠️ 종전에는 {@code model=null} 을 넘겼는데 그 컬럼은 nullable 이라 <b>애초에 아무것도
     * 안 터졌다</b> — 실패를 재현하지 못하는 테스트였다. {@code kind} 가 NOT NULL 이라
     * 이쪽으로 실제 flush 실패를 만든다.
     */
    @Test
    void 저장_실패는_삼킨다() {
        recorder.record(null, "claude-opus-4-8", 1, 1, 0, 0, 2);
    }

    /**
     * 삼키는 것과 바깥 트랜잭션을 지키는 것은 다른 주장이다.
     *
     * {@code @Transactional(REQUIRES_NEW)} 애노테이션 + 내부 try/catch 조합은 이걸 못 지킨다 —
     * 커밋은 AOP 프록시가 <b>메서드 반환 이후</b>, 즉 자기 try/catch 바깥에서 하기 때문에
     * flush 실패 뒤의 커밋이 {@code UnexpectedRollbackException} 으로 터지면 그대로 호출부로 샌다.
     * 퀴즈 생성 전체가 트랜잭션이므로 그게 새면 <b>그날 생성이 통째로 롤백된다</b> —
     * 토큰을 못 남기는 것보다 훨씬 나쁘고, 이 클래스의 존재 이유가 무너지는 지점이다.
     */
    @Test
    void 바깥_트랜잭션은_실제_제약_위반에도_살아남는다() {
        repository.deleteAll();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.execute(status -> {
            // 이 정상 행이 살아남아야 격리가 증명된다.
            recorder.record("generate", "gpt-4.1-mini", 10, 2, 0, 0, 12);
            // kind=null → NOT NULL 위반. mock 이 아니라 진짜 영속성 계층 실패다.
            recorder.record(null, "claude-opus-4-8", 1, 1, 0, 0, 2);
            return null;
        });

        assertThat(repository.rollupSince(LocalDate.now(clock).minusDays(1)))
                .extracting(TokenUsageRepository.DailyRow::getKind)
                .containsExactly("generate");
    }
}
