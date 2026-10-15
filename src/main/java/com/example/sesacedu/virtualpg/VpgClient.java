package com.example.sesacedu.virtualpg;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * 백엔드 → 가상 PG 서버끼리 호출 (POST /payment가 쓴다). 브라우저는 이 경로에 끼지 않는다.
 *
 * - 주소: vpg.base-url. 비어 있으면 같은 애플리케이션 (http://localhost:{실행 포트}{context-path}).
 *   외장 Tomcat(WAR)이나 가상 PG를 다른 인스턴스에 띄울 때는 vpg.base-url을 꼭 넣는다.
 * - 헤더: X-VPG-Secret (VpgSecret), X-Request-Id (UUID). Origin은 붙이지 않는다.
 * - 타임아웃: 연결 vpg.connect-timeout-ms(3초), 읽기 vpg.read-timeout-ms(10초).
 * - 4xx·5xx도 예외로 바꾸지 않고 상태·본문을 그대로 돌려준다. 연결 실패·시간 초과만 null.
 * 요청 본문(결제 정보)과 응답은 로그에 남기지 않는다.
 */
@Component
public class VpgClient {

    private final VpgSecret secret;
    private final Environment env;
    private final String baseUrl;
    private final RestTemplate rest;

    public VpgClient(VpgSecret secret, Environment env,
                     @Value("${vpg.base-url:}") String baseUrl,
                     @Value("${vpg.connect-timeout-ms:3000}") int connectTimeoutMs,
                     @Value("${vpg.read-timeout-ms:10000}") int readTimeoutMs) {
        this.secret = secret;
        this.env = env;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.rest = new RestTemplate(factory);
        this.rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    /** 가상 PG 응답 (HTTP 상태 + { code, message, result }) */
    public static class Reply {
        public final int status;
        public final Map<?, ?> body;

        Reply(int status, Map<?, ?> body) {
            this.status = status;
            this.body = body;
        }
    }

    /** POST /virtualPG/buy/confirm. 응답이 없거나 시간 초과면 null */
    public Reply confirm(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-VPG-Secret", secret.value());
        headers.set("X-Request-Id", UUID.randomUUID().toString());
        try {
            ResponseEntity<Map> res = rest.exchange(url(), HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
            return new Reply(res.getStatusCodeValue(), res.getBody());
        } catch (RestClientException e) {
            return null;
        }
    }

    private String url() {
        String base = baseUrl;
        if (base.isEmpty()) {
            String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
            base = "http://localhost:" + port + env.getProperty("server.servlet.context-path", "");
        }
        return base.replaceAll("/+$", "") + "/virtualPG/buy/confirm";
    }
}
