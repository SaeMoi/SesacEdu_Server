package com.example.sesacedu.global;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 실행 폴더의 .env(KEY=VALUE)를 Spring 설정으로 읽는다. 파일이 없으면 아무것도 하지 않는다.
 *
 * - 우선순위 최하위: OS 환경변수·실행 인자·application*.yml·테스트 설정이 모두 이긴다
 *   (AWS 에서 환경변수로 넣으면 .env 가 없어도 되고, 있어도 환경변수가 우선).
 * - 값 양끝의 "..." / '...' 는 벗긴다. 그 밖의 문자(#, \, 공백, =)는 그대로 둔다.
 * - 빈 줄, # 로 시작하는 줄은 건너뛴다. 앞의 "export " 는 무시한다.
 * - 값은 로그에 남기지 않는다.
 * 등록: META-INF/spring.factories
 **/
public class DotenvLoader implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = Paths.get(".env");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, parse(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(".env 를 읽지 못했습니다", e);
        }
    }

    static Map<String, Object> parse(Path file) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("﻿")) {
                line = line.substring(1);   // 메모장이 붙이는 BOM
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' || first == '\'') && first == last) {
                    value = value.substring(1, value.length() - 1);
                }
            }
            values.put(key, value);
        }
        return values;
    }
}
