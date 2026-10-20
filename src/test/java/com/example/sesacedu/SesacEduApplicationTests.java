package com.example.sesacedu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 테스트용 H2·메일 설정(application-test.yml)과 JWT_SECRET이 있어야 컨텍스트가 뜬다
@SpringBootTest(properties = "JWT_SECRET=context-loads-test-secret-at-least-32-bytes")
@ActiveProfiles("test")
class SesacEduApplicationTests {

    @Test
    void contextLoads() {
    }

}
