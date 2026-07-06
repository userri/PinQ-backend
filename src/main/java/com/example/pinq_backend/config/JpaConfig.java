package com.example.pinq_backend.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseTimeEntity 의 @CreatedDate / @LastModifiedDate 가 동작하도록 감사 기능을 활성화한다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /**
     * 감사 타임스탬프가 KST Clock 빈을 사용하도록 고정한다.
     *
     * 기본 구현은 LocalDateTime.now() — JVM 기본 timezone 을 따르므로
     * UTC 로 뜨는 서버(도커 컨테이너, CI 러너)에서는 KST 자정~09시 사이의
     * created_at 이 전날 날짜로 저장된다. 서비스 로직은 전부 Clock(KST) 을
     * 쓰기 때문에 풀이 시각 기반 집계(스트릭 복구, 일별 활동 그리드)가
     * lastSolvedDate 와 어긋나는 실버그가 있었다 (KST 자정에 돈 CI 가
     * UserServiceIntegrationTest 실패로 검출).
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
