package com.example.sesacedu.virtualpg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 백엔드(POST /payment)와 가상 PG(POST /virtualPG/buy/confirm)가 공유하는 X-VPG-Secret.
 *
 * 값은 환경 변수 VPG_SECRET (설정 vpg.secret). 비어 있으면 기동할 때마다 무작위 32바이트를 만든다:
 * 백엔드와 가상 PG가 같은 애플리케이션 안에 있으면 둘 다 이 빈을 보므로 설정 없이 동작하고,
 * 바깥(브라우저)은 값을 알 수 없어 가상 PG를 직접 부를 수 없다. 가상 PG를 다른 인스턴스에 띄우면(vpg.base-url)
 * 양쪽에 같은 VPG_SECRET을 넣어야 한다. 값은 로그·응답 어디에도 내보내지 않는다.
 */
@Component
public class VpgSecret {

    private static final Logger log = LoggerFactory.getLogger(VpgSecret.class);

    private final byte[] secret;

    public VpgSecret(@Value("${vpg.secret:${VPG_SECRET:}}") String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            log.info("VPG_SECRET이 없어 이번 기동 전용 무작위 시크릿을 만들었습니다 (같은 애플리케이션 안의 호출에만 유효).");
        }
        this.secret = value.getBytes(StandardCharsets.UTF_8);
    }

    /** 요청 헤더 값과 상수 시간 비교 */
    public boolean matches(String header) {
        return header != null && MessageDigest.isEqual(secret, header.getBytes(StandardCharsets.UTF_8));
    }

    /** 백엔드가 가상 PG를 부를 때 X-VPG-Secret에 넣는 값 */
    String value() {
        return new String(secret, StandardCharsets.UTF_8);
    }
}
