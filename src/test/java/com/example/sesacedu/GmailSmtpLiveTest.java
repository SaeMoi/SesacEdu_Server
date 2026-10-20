package com.example.sesacedu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 실제 Gmail SMTP 연결 확인 (선택 실행). MAIL_USERNAME·MAIL_PASSWORD 환경 변수가 둘 다 있을 때만 돈다.
 * application.yaml과 같은 설정(smtp.gmail.com:587, STARTTLS 필수, 인증)으로 로그인까지만 하고 메일은 보내지 않는다.
 * 비밀번호는 Google 계정의 「앱 비밀번호」(16자리)를 쓴다. 값은 로그·출력에 남기지 않는다.
 *
 * PowerShell:
 *   $env:MAIL_USERNAME = "보내는계정@gmail.com"; $env:MAIL_PASSWORD = "앱비밀번호16자리"
 *   .\gradlew test --tests com.example.sesacedu.GmailSmtpLiveTest
 * 환경 변수가 없으면 이 클래스는 skipped로 표시된다.
 */
@EnabledIfEnvironmentVariable(named = "MAIL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MAIL_PASSWORD", matches = ".+")
class GmailSmtpLiveTest {

    @Test @DisplayName("Gmail SMTP: STARTTLS + 앱 비밀번호 로그인 성공 (메일 발송 없음)")
    void connectsAndAuthenticates() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("smtp.gmail.com");
        sender.setPort(587);
        sender.setUsername(System.getenv("MAIL_USERNAME"));
        sender.setPassword(System.getenv("MAIL_PASSWORD"));
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "5000");
        p.put("mail.smtp.writetimeout", "5000");

        // 연결 → STARTTLS → AUTH 까지 하고 닫는다. 실패하면 MessagingException (주소·포트·앱 비밀번호 확인)
        assertDoesNotThrow(sender::testConnection);
    }
}
