package com.example.pinq_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseTimeEntity 의 @CreatedDate / @LastModifiedDate 가 동작하도록 감사 기능을 활성화한다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
