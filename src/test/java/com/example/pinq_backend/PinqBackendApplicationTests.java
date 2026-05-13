package com.example.pinq_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 전체 컨텍스트 로딩 스모크 테스트.
 * test 프로파일(H2) 로 실행되어 로컬 MySQL 없이도 통과한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PinqBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
