package com.example.pinq_backend.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 공통 빈.
 *
 * Clock 을 빈으로 등록하는 이유:
 *  - LocalDate.now() 는 JVM 기본 timezone 을 따른다.
 *  - EC2 등 서버가 UTC 로 뜨면 KST 자정 전후 1시간 동안 날짜가 어긋나
 *    스트릭·일별 집계가 잘못된 날짜에 기록된다.
 *  - Clock 을 주입받으면 서비스 코드가 timezone 을 명시적으로 고정하고,
 *    테스트에서 Clock.fixed() 로 날짜를 완전히 제어할 수 있다.
 */
@Configuration
public class AppConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
