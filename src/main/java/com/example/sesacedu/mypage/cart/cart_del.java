package com.example.sesacedu.mypage.cart;

import com.example.sesacedu.global.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.sesacedu.global.ApiResponse.*;

/**
 * DELETE /mypage/cart/del (Notion API 명세서: 장바구니 삭제). 행의 `삭제`(1개)와 `선택 삭제`(여러 개)가 같이 쓴다.
 *
 * 처리 순서: 1. Authorization의 JWT 검증 (없음·위조 401 UNAUTHORIZED, 만료 401 TOKEN_EXPIRED)
 * → 2. 입력 검증 (courseIds 1~50개, 각각 양의 정수, 아니면 400 INVALID_INPUT). 중복은 제거
 * → 3. DELETE ... WHERE user_id = 나 AND Lid IN (?, ...) → 4. 200 CART_ITEMS_DELETED { deletedCount, totalCount }.
 * 내 장바구니에 없는 courseId는 조용히 무시한다 (다시 지워도 200). IN 목록은 자리표시자로 바인드한다 (SQLi 없음).
 * cart PK가 (user_id, Lid)라 삭제가 항상 내 행으로 묶인다 → 의도된 취약점 없음 (명세 「의도된 취약점」).
 */
@RestController
public class cart_del {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    public cart_del(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    @DeleteMapping("/mypage/cart/del")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody(required = false) DeleteRequest req,
                                                      HttpServletRequest request) {
        // 1. JWT 검증 (입력 검증보다 먼저)
        long userId = jwtUtil.getUid(request);
        if (req == null) req = new DeleteRequest();

        // 2. 입력 검증. 배열이 아니거나, 비었거나, 50개 초과, 양의 정수가 아닌 값이 있으면 400
        Set<Long> ids = new LinkedHashSet<>();
        boolean valid = req.courseIds instanceof List
                && !((List<?>) req.courseIds).isEmpty()
                && ((List<?>) req.courseIds).size() <= cart.MAX_COUNT;
        if (valid) {
            for (Object v : (List<?>) req.courseIds) {
                Long id = cart.positiveId(v);
                if (id == null) {
                    valid = false;
                    break;
                }
                ids.add(id);
            }
        }
        if (!valid) {
            List<Map<String, String>> errors = new ArrayList<>();
            errors.add(error("courseIds", "1개 이상 50개 이하로 선택해야 합니다."));
            return invalidInput("삭제할 강의를 선택해주세요.", errors);
        }

        // 3. 내 행만 삭제. 자리표시자 개수만 이어 붙이고 값은 모두 바인드
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(ids);
        int deleted = jdbc.update("DELETE FROM cart WHERE user_id = ? AND Lid IN (" + placeholders + ")", args.toArray());

        // 4. 응답
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedCount", deleted);
        result.put("totalCount", cart.count(jdbc, userId));
        return ResponseEntity.ok().body(body("CART_ITEMS_DELETED", "선택한 강의를 장바구니에서 삭제했습니다.", result));
    }

    /** 요청 본문 { courseIds: number[] }. 형식 오류를 field 단위로 알려 주려고 Object로 받는다 */
    public static class DeleteRequest {
        public Object courseIds;
    }
}
