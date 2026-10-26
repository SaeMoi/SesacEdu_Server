package com.example.sesacedu.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /login/find-id (Notion API 명세서: 아이디 확인).
 * 이름 + 생년월일로 가입된 이메일(로그인 ID)을 찾는다. 로그인 전 공개 API (JWT 불필요).
 *
 * 처리 순서: 1. 입력 검증(400 INVALID_INPUT) → 2. uname + b_date로 회원 조회
 * → 3. 0건이면 404 ACCOUNT_NOT_FOUND → 4. 1건 이상이면 200 ACCOUNT_FOUND { email } (여러 건이면 첫 건).
 *
 * 의도된 취약점 (모의해킹용):
 *  - A03 SQL 인젝션: name을 bind 변수가 아니라 쿼리 문자열에 직접 연결한다. birthDate는 형식을 검증하므로
 *    주입 지점은 name 하나뿐이고, UNION SELECT로 임의 컬럼(예: pw 해시)을 email 자리로 빼낼 수 있다.
 *  - A01/A07 계정 열거 + 개인정보(이메일) 노출: 전체 이메일을 마스킹 없이 반환하고, 있음(200)/없음(404)을
 *    구분한다. 이름+생년월일은 엔트로피가 낮아 대입으로 가입 계정·이메일을 수집할 수 있다. 호출 횟수 제한도 없다.
 */
@RestController
public class find_id {

    // STRICT + uuuu: 2026-02-30 같은 실재하지 않는 날짜도 거른다 (SMART는 clamp 해서 통과시킴)
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final int NAME_MAX = 50;

    private final JdbcTemplate jdbc;

    public find_id(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/login/find-id")
    public ResponseEntity<Map<String, Object>> findId(@RequestBody(required = false) FindIdRequest req) {
        if (req == null) req = new FindIdRequest();

        // 1. 입력 검증. name은 공백 제거, birthDate는 YYYY-MM-DD 형식 + 실재 날짜만 (2026-02-30 등은 거름)
        String name = req.name == null ? "" : req.name.trim();
        String birthDate = req.birthDate == null ? "" : req.birthDate.trim();
        List<Map<String, String>> errors = new ArrayList<>();
        if (name.isEmpty()) errors.add(error("name", "이름을 입력해주세요."));
        else if (name.length() > NAME_MAX) errors.add(error("name", "이름은 최대 50자입니다."));
        if (birthDate.isEmpty()) errors.add(error("birthDate", "생년월일을 입력해주세요."));
        else if (!isValidDate(birthDate)) errors.add(error("birthDate", "YYYY-MM-DD 형식으로 입력해주세요."));
        if (!errors.isEmpty()) return invalidInput(errors);

        // 2. 회원 조회
        //    의도된 취약점(A03): name을 bind(?) 가 아니라 문자열로 직접 연결한다. birthDate는 위에서 형식을
        //    검증했으므로(숫자·'-'만) 주입 지점은 name 하나뿐이다.
        String sql = "SELECT mail FROM users"
                + " WHERE uname = '" + name + "'"
                + " AND b_date = TO_DATE('" + birthDate + "', 'YYYY-MM-DD')";
        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        // 3. 없으면 404 (있음/없음을 구분 = 의도된 취약점: 계정 열거)
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(body("ACCOUNT_NOT_FOUND", "일치하는 계정을 찾을 수 없습니다.", null));
        }

        // 4. 전체 이메일을 마스킹 없이 그대로 반환 (의도된 취약점: 개인정보 노출). 여러 건이면 첫 건.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("email", String.valueOf(rows.get(0).get("MAIL")));
        return ResponseEntity.ok().body(body("ACCOUNT_FOUND", "가입된 이메일을 찾았습니다.", result));
    }

    /** 본문이 JSON이 아니거나 깨진 경우도 400 INVALID_INPUT */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        List<Map<String, String>> errors = new ArrayList<>();
        errors.add(error("body", "JSON 형식이 올바르지 않습니다."));
        return invalidInput(errors);
    }

    private static boolean isValidDate(String s) {
        try {
            LocalDate.parse(s, YMD);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 요청 본문 { name, birthDate } */
    public static class FindIdRequest {
        public String name;
        public String birthDate;
    }
}
