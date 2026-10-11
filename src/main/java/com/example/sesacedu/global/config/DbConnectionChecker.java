package com.example.sesacedu.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 "지금 실제로 어느 DB/계정에 연결됐는지"를 한 번 로그로 찍는다.
 * 의도한 DB(XEPDB1, 계정 SESACEDU)에 붙었는지 부팅 로그에서 바로 확인하기 위한 것.
 *
 * 확인이 끝나면 이 클래스는 지워도 된다(운영에 꼭 필요한 건 아니다).
 */
@Component
public class DbConnectionChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionChecker.class);

    private final JdbcTemplate jdbcTemplate;

    public DbConnectionChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String user   = jdbcTemplate.queryForObject("SELECT USER FROM dual", String.class);
            String conName = jdbcTemplate.queryForObject(
                    "SELECT SYS_CONTEXT('USERENV','CON_NAME') FROM dual", String.class);
            String dbName = jdbcTemplate.queryForObject(
                    "SELECT SYS_CONTEXT('USERENV','DB_NAME') FROM dual", String.class);
            // 이 계정이 소유한 테이블 개수 (0이면 테이블이 이 계정에 없다는 뜻)
            Integer tableCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_tables", Integer.class);

            log.info("================= DB 연결 확인 =================");
            log.info(" 접속 계정(USER)   : {}", user);       // 기대값: SESACEDU
            log.info(" 컨테이너(CON_NAME) : {}", conName);   // 기대값: XEPDB1
            log.info(" DB 이름(DB_NAME)   : {}", dbName);
            log.info(" 이 계정 테이블 수   : {}", tableCount);
            log.info("===============================================");
        } catch (Exception e) {
            log.error("DB 연결 확인 중 오류: {}", e.getMessage(), e);
        }
    }
}