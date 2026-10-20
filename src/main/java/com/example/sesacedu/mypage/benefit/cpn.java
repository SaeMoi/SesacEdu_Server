package com.example.sesacedu.mypage.benefit;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * POST /mypage/cpn (Notion API 명세서: 쿠폰등록).
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 입력 정규화(공백·하이픈 제거, 대문자) + 검증 (영문 대문자·숫자 12~16자, 아니면 400 INVALID_INPUT)
 * → 3. coupons.code 조회, 없거나 due < SYSTIMESTAMP면 400 INVALID_COUPON_CODE (둘을 구분하지 않음)
 * → 4. 한 트랜잭션: user_coupons INSERT (PK 중복이면 409 COUPON_ALREADY_REGISTERED, 수량 차감 없음)
 *      → 수량 원자적 차감, 0행이면 롤백 후 409 COUPON_SOLD_OUT
 * → 5. 201 COUPON_REGISTERED (result는 GET /mypage/bnf의 coupons[] 한 항목과 같은 형식).
 *
 * ERD 2026-10-12에서 명세의 coupons.stock이 빠지고 quantity(총수량, NULL = 미확정) + issued(발급 수)가 생겨
 * 차감을 UPDATE coupons SET issued = issued + 1 WHERE cid = ? AND (quantity IS NULL OR issued < quantity)로 한다.
 * quantity가 NULL이면 수량 제한 없이 발급한다. received_at에 등록 시각을 남긴다.
 *
 * 의도된 취약점 없음 (기본 설계는 원자적 차감). 명세 「의도된 취약점」: 차감을 SELECT 후 UPDATE로 나누면 선착순 race condition.
 */
@RestController
public class cpn {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{12,16}");

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate tx;

    public cpn(JdbcTemplate jdbc, JwtUtil jwtUtil, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.tx = tx;
    }

    @PostMapping("/mypage/cpn")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) RegisterRequest req,
                                                        HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저)
        long userId = jwtUtil.getUid(request);
        if (req == null) req = new RegisterRequest();

        // 2. 정규화 + 형식 검증
        String code = req.couponCode == null ? "" : req.couponCode.replaceAll("[\\s-]", "").toUpperCase();
        if (!CODE.matcher(code).matches()) {
            List<Map<String, String>> errors = new ArrayList<>();
            errors.add(error("couponCode", "영문 대문자와 숫자 12~16자여야 합니다."));
            return invalidInput(errors);
        }

        // 3. 코드 조회 + 만료 확인 (없음·만료 구분 없음 = 코드 추측 방지)
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT cid, user_id FROM coupons WHERE code = ? AND due >= SYSTIMESTAMP", code);
        if (found.isEmpty()) {
            return ResponseEntity.badRequest().body(body("INVALID_COUPON_CODE", "유효하지 않은 쿠폰 코드입니다.", null));
        }
        long cid = ((Number) found.get(0).get("CID")).longValue();
        // 강사는 본인이 발행한 쿠폰을 등록할 수 없다 (Kim 2026-10-19)
        if (((Number) found.get(0).get("USER_ID")).longValue() == userId) {
            return ResponseEntity.badRequest().body(body("OWN_COUPON", "본인이 발행한 쿠폰은 등록할 수 없습니다.", null));
        }

        // 4. 등록 + 수량 차감을 한 트랜잭션으로. 둘 다 성공해야 커밋
        String failure = tx.execute(status -> {
            try {
                jdbc.update("INSERT INTO user_coupons (user_id, cid, used, received_at) VALUES (?, ?, 0, LOCALTIMESTAMP)",
                        userId, cid);
            } catch (DuplicateKeyException e) {
                return "COUPON_ALREADY_REGISTERED";       // 이미 가진 사람은 수량을 깎지 않는다
            }
            int issued = jdbc.update(
                    "UPDATE coupons SET issued = issued + 1 WHERE cid = ? AND (quantity IS NULL OR issued < quantity)", cid);
            if (issued == 0) {
                status.setRollbackOnly();                 // 소진: 방금 넣은 user_coupons 행도 되돌린다
                return "COUPON_SOLD_OUT";
            }
            return null;
        });
        if ("COUPON_ALREADY_REGISTERED".equals(failure)) {
            return ResponseEntity.status(409).body(body(failure, "이미 등록한 쿠폰입니다.", null));
        }
        if ("COUPON_SOLD_OUT".equals(failure)) {
            return ResponseEntity.status(409).body(body(failure, "쿠폰이 모두 소진되었습니다.", null));
        }

        // 5. 방금 등록한 쿠폰을 나의해택과 같은 형식으로
        Map<String, Object> row = jdbc.queryForMap(bnf.SELECT_COUPON + " WHERE uc.user_id = ? AND uc.cid = ?", userId, cid);
        return ResponseEntity.status(201).body(body("COUPON_REGISTERED", "쿠폰이 등록되었습니다.", bnf.couponItem(row)));
    }

    /** 요청 본문 { couponCode } */
    public static class RegisterRequest {
        public String couponCode;
    }
}
