-- =====================================================================
--  seed.sql : 새싹에듀 ERD 16개 테이블 샘플 데이터 (재실행 안전)
--  Target   : AWS RDS for Oracle (19c 이상), .env 의 DB_USERNAME 스키마
--
--  실행 (Windows cmd, 한글 깨짐 방지용 NLS_LANG 먼저):
--    chcp 65001
--    set NLS_LANG=KOREAN_KOREA.AL32UTF8
--    sqlplus <DB_USERNAME>@//<RDS 엔드포인트>:1521/<DB 이름>     (비밀번호는 프롬프트에서 입력)
--    (SQL Developer 라면 같은 접속으로 이 파일을 열고 '스크립트 실행(F5)')
--    SQL> @C:\경로\db\seed.sql
--
--  !! 주의 !! schema.sql 이 16개 테이블을 DROP 하고 다시 만든다 (가입해 둔 계정 포함 모두 사라진다).
--  순서: (1) @@schema.sql 로 테이블 재생성 (seed.sql 과 같은 폴더에 둔다) → (2) INSERT
--        → (3) COMMIT → (4) IDENTITY 를 MAX+1 로 맞춤 (앱 INSERT 충돌 방지)
--  중간에 에러가 나면 ROLLBACK 하고 SQL*Plus 를 끝낸다 (반쯤 들어간 데이터 없음).
--
--  테스트 계정 (전부 비밀번호 Passw0rd! — pw 컬럼은 MD5 hex):
--    user_id  mail                    auth                    status
--    1        admin@sesac.test        3 관리자                active
--    2        inst.kim@sesac.test     2 강사 (승인)           active
--    3        inst.lee@sesac.test     2 강사 (승인)           active
--    4        pending@sesac.test      4 강사 신청 대기        active
--    5        jade@example.com        1 수강생                active   ← 명세 예시 계정
--    6        student2@sesac.test     1 수강생                active
--    7        general@sesac.test      0 일반                  active
--    8        banned@sesac.test       1 수강생                restricted (로그인 시 403)
-- =====================================================================
SET DEFINE OFF
SET SERVEROUTPUT ON
SET FEEDBACK OFF

WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

PROMPT [1/4] drop and recreate 16 tables (schema.sql)
@@schema.sql

PROMPT [2/4] INSERT

-- ---- users ----------------------------------------------------------
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (1, 'admin@sesac.test',    LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '관리자',  '운영자',     '01000000001', DATE '1990-01-01', '서울특별시 영등포구 선유로 1', 3, 'active', NULL, 1, TIMESTAMP '2026-09-01 09:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (2, 'inst.kim@sesac.test', LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '김보안',  '웹해킹김쌤', '01011112222', DATE '1988-05-20', '서울특별시 마포구 월드컵로 10', 2, 'active', '2_profile.png', 1, TIMESTAMP '2026-09-01 10:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (3, 'inst.lee@sesac.test', LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '이클라',  '클라우드이쌤', '01022223333', DATE '1991-11-03', '경기도 성남시 분당구 판교로 20', 2, 'active', NULL, 1, TIMESTAMP '2026-09-02 10:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (4, 'pending@sesac.test',  LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '박대기',  '예비강사',   '01033334444', DATE '1995-07-07', '인천광역시 연수구 송도로 30', 4, 'active', NULL, 1, TIMESTAMP '2026-10-05 15:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (5, 'jade@example.com',    LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '김태형',  '태형',       '01012345678', DATE '1998-03-14', '서울특별시 강서구 공항대로 40', 1, 'active', '5_profile.png', 1, TIMESTAMP '2026-09-10 11:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (6, 'student2@sesac.test', LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '최수강',  '열공러',     '01055556666', DATE '2000-12-25', '부산광역시 해운대구 센텀로 50', 1, 'active', NULL, 1, TIMESTAMP '2026-09-12 12:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (7, 'general@sesac.test',  LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '정일반',  '구경꾼',     '01077778888', DATE '1999-02-02', NULL, 0, 'active', NULL, 1, TIMESTAMP '2026-10-01 13:00:00', NULL);
INSERT INTO users (user_id, mail, pw, uname, nickname, phone, b_date, address, auth, status, picture, alarm, created, updated)
VALUES (8, 'banned@sesac.test',   LOWER(RAWTOHEX(STANDARD_HASH('Passw0rd!', 'MD5'))), '한제한',  '정지된계정', '01099990000', DATE '1997-09-09', NULL, 1, 'restricted', NULL, 1, TIMESTAMP '2026-09-20 14:00:00', NULL);

-- ---- instructors (2, 3 승인 / 4 대기) --------------------------------
INSERT INTO instructors (user_id, iname, bio, description, field, career, contact, career_file, career_filename, bank, account, applied_at, approved_at, rejected_at, reject_reason)
VALUES (2, '김보안', '버그바운티 5년차 웹 해커', '실무 모의해킹 사례로 웹 취약점을 가르칩니다.', '웹 보안', '2019~ 보안 컨설팅사 모의해킹팀' || CHR(10) || '2023~ 버그바운티 플랫폼 상위 랭커', 'https://github.com/inst-kim', '/uploads/career/2_career.pdf', '경력증명서_김보안.pdf', '국민은행', '123-456-789012', TIMESTAMP '2026-09-01 10:30:00', TIMESTAMP '2026-09-02 09:00:00', NULL, NULL);
INSERT INTO instructors (user_id, iname, bio, description, field, career, contact, career_file, career_filename, bank, account, applied_at, approved_at, rejected_at, reject_reason)
VALUES (3, '이클라', 'AWS 아키텍트 출신 강사', 'AWS 실습 환경을 처음부터 같이 만듭니다.', '클라우드', '2018~ 클라우드 MSP 엔지니어' || CHR(10) || 'AWS SA Professional', NULL, '/uploads/career/3_career.pdf', 'AWS_SAP_자격증.pdf', '신한은행', '110-222-333444', TIMESTAMP '2026-09-02 10:30:00', TIMESTAMP '2026-09-03 09:00:00', NULL, NULL);
INSERT INTO instructors (user_id, iname, bio, description, field, career, contact, career_file, career_filename, bank, account, applied_at, approved_at, rejected_at, reject_reason)
VALUES (4, '박대기', '네트워크 관제 3년', NULL, '네트워크', '2023~ 보안관제센터 관제요원', NULL, '/uploads/career/4_career.pdf', '재직증명서.pdf', '우리은행', '1002-333-444555', TIMESTAMP '2026-10-05 15:10:00', NULL, NULL, NULL);

-- ---- lectures (open 3 / draft 1 / closed 1, 무료 1) -------------------
INSERT INTO lectures (Lid, user_id, name, category, lvl, picture, video, material, Lprice, status, created, headline, duration, assignment_title)
VALUES (1, 2, '웹 해킹 입문: OWASP Top 10', '웹 보안', '입문', '/images/lecture/1.png', '/videos/1.mp4', '/materials/1.pdf', 39000, 'open', TIMESTAMP '2026-09-03 10:00:00', 'OWASP Top 10 을 직접 공격하며 배운다', 5400, 'XSS 재현 보고서');
INSERT INTO lectures (Lid, user_id, name, category, lvl, picture, video, material, Lprice, status, created, headline, duration, assignment_title)
VALUES (2, 2, '패킷으로 배우는 네트워크', '네트워크', '중급', '/images/lecture/2.png', '/videos/2.mp4', '/materials/2.pdf', 29000, 'open', TIMESTAMP '2026-09-04 10:00:00', 'Wireshark 로 프로토콜 뜯어보기', 4800, NULL);
INSERT INTO lectures (Lid, user_id, name, category, lvl, picture, video, material, Lprice, status, created, headline, duration, assignment_title)
VALUES (3, 3, 'AWS 첫걸음 (무료)', '클라우드', '입문', '/images/lecture/3.png', '/videos/3.mp4', '/materials/3.pdf', 0, 'open', TIMESTAMP '2026-09-05 10:00:00', '계정 만들기부터 EC2 까지', 3600, 'EC2 인스턴스 생성 캡처');
INSERT INTO lectures (Lid, user_id, name, category, lvl, picture, video, material, Lprice, status, created, headline, duration, assignment_title)
VALUES (4, 3, '로그 분석 실전 (준비 중)', '로그 분석', '고급', NULL, NULL, NULL, 49000, 'draft', TIMESTAMP '2026-10-01 10:00:00', NULL, NULL, NULL);
INSERT INTO lectures (Lid, user_id, name, category, lvl, picture, video, material, Lprice, status, created, headline, duration, assignment_title)
VALUES (5, 2, '파이썬 기초 (판매 종료)', '개발 기초', '입문', '/images/lecture/5.png', '/videos/5.mp4', NULL, 19000, 'closed', TIMESTAMP '2026-08-01 10:00:00', '변수부터 함수까지', 3000, NULL);

-- ---- coupons (강의 지정 / 전체 / 만료 / 품절) --------------------------
INSERT INTO coupons (cid, user_id, Lid, cname, discount, due, code, quantity, issued, min_amount, created)
VALUES (1, 2, 1,    '웹 해킹 입문 5천원 할인', 5000, TIMESTAMP '2026-12-31 23:59:59', 'WEBHACK5000OFF', 100, 2, NULL, TIMESTAMP '2026-09-10 10:00:00');
INSERT INTO coupons (cid, user_id, Lid, cname, discount, due, code, quantity, issued, min_amount, created)
VALUES (2, 3, NULL, '전 강의 3천원 할인',      3000, TIMESTAMP '2026-12-31 23:59:59', 'ALLCOURSE3000OFF', 50, 1, 20000, TIMESTAMP '2026-09-10 10:00:00');
INSERT INTO coupons (cid, user_id, Lid, cname, discount, due, code, quantity, issued, min_amount, created)
VALUES (3, 2, 2,    '네트워크 오픈 기념 (만료)', 7000, TIMESTAMP '2026-09-30 23:59:59', 'NETOPEN7000OFF', 10, 1, NULL, TIMESTAMP '2026-09-05 10:00:00');
INSERT INTO coupons (cid, user_id, Lid, cname, discount, due, code, quantity, issued, min_amount, created)
VALUES (4, 3, NULL, '선착순 1만원 (품절)',    10000, TIMESTAMP '2026-12-31 23:59:59', 'FIRSTCOME10KOFF', 0, 0, NULL, TIMESTAMP '2026-09-10 10:00:00');

-- ---- user_coupons ---------------------------------------------------
-- 주문 1에서 사용
INSERT INTO user_coupons (user_id, cid, used, received_at, used_at) VALUES (5, 1, 1, TIMESTAMP '2026-09-12 10:00:00', TIMESTAMP '2026-09-15 14:20:00');
-- 사용 가능
INSERT INTO user_coupons (user_id, cid, used, received_at, used_at) VALUES (5, 2, 0, TIMESTAMP '2026-09-12 10:05:00', NULL);
-- 사용 가능 (강의 1 전용)
INSERT INTO user_coupons (user_id, cid, used, received_at, used_at) VALUES (6, 1, 0, TIMESTAMP '2026-09-20 09:30:00', NULL);
-- 미사용이지만 만료
INSERT INTO user_coupons (user_id, cid, used, received_at, used_at) VALUES (6, 3, 0, TIMESTAMP '2026-09-21 09:30:00', NULL);

-- ---- orders ---------------------------------------------------------
-- 1: jade, 강의 1+2, 쿠폰 1(5000) → 68000-5000 = 63000, paid
INSERT INTO orders (oid, ono, user_id, cid, discount, Aprice, pay, status, created, paid_at, refunded_at, refund_reason)
VALUES (1, 'SE20260915-0001', 5, 1, 5000, 63000, 'card', 'paid', TIMESTAMP '2026-09-15 14:20:00', TIMESTAMP '2026-09-15 14:21:00', NULL, NULL);
-- 2: student2, 무료 강의 3 → 0원, paid (PG 안 거침)
INSERT INTO orders (oid, ono, user_id, cid, discount, Aprice, pay, status, created, paid_at, refunded_at, refund_reason)
VALUES (2, 'SE20260920-0001', 6, NULL, 0, 0, NULL, 'paid', TIMESTAMP '2026-09-20 09:00:00', TIMESTAMP '2026-09-20 09:00:00', NULL, NULL);
-- 3: student2, 강의 1, 결제 전 (pending)
INSERT INTO orders (oid, ono, user_id, cid, discount, Aprice, pay, status, created, paid_at, refunded_at, refund_reason)
VALUES (3, 'SE20261011-0001', 6, NULL, 0, 39000, 'card', 'pending', TIMESTAMP '2026-10-11 20:00:00', NULL, NULL, NULL);
-- 4: jade, 강의 5 (판매 종료 전 구매) → 환불
INSERT INTO orders (oid, ono, user_id, cid, discount, Aprice, pay, status, created, paid_at, refunded_at, refund_reason)
VALUES (4, 'SE20260810-0001', 5, NULL, 0, 19000, 'transfer', 'refunded', TIMESTAMP '2026-08-10 16:00:00', TIMESTAMP '2026-08-10 16:05:00', TIMESTAMP '2026-08-12 10:00:00', '단순 변심');

-- ---- order_items (Lname·price = 구매 시점 스냅샷) ----------------------
INSERT INTO order_items (oiid, oid, Lid, Lname, price) VALUES (1, 1, 1, '웹 해킹 입문: OWASP Top 10', 39000);
INSERT INTO order_items (oiid, oid, Lid, Lname, price) VALUES (2, 1, 2, '패킷으로 배우는 네트워크', 29000);
INSERT INTO order_items (oiid, oid, Lid, Lname, price) VALUES (3, 2, 3, 'AWS 첫걸음 (무료)', 0);
INSERT INTO order_items (oiid, oid, Lid, Lname, price) VALUES (4, 3, 1, '웹 해킹 입문: OWASP Top 10', 39000);
INSERT INTO order_items (oiid, oid, Lid, Lname, price) VALUES (5, 4, 5, '파이썬 기초 (판매 종료)', 19000);

-- ---- enrollments (paid 주문의 강의마다 1행) ----------------------------
INSERT INTO enrollments (user_id, Lid, done, position, enrolled_at, completed_at) VALUES (5, 1, 0, 1260, TIMESTAMP '2026-09-15 14:21:00', NULL);
-- 수료
INSERT INTO enrollments (user_id, Lid, done, position, enrolled_at, completed_at) VALUES (5, 2, 1, 4800, TIMESTAMP '2026-09-15 14:21:00', TIMESTAMP '2026-10-02 22:00:00');
INSERT INTO enrollments (user_id, Lid, done, position, enrolled_at, completed_at) VALUES (6, 3, 0, NULL, TIMESTAMP '2026-09-20 09:00:00', NULL);

-- ---- cart -----------------------------------------------------------
-- 무료 강의
INSERT INTO cart (user_id, Lid, created) VALUES (5, 3, TIMESTAMP '2026-10-10 12:00:00');
-- 주문 3과 같은 강의 (결제 대기)
INSERT INTO cart (user_id, Lid, created) VALUES (6, 1, TIMESTAMP '2026-10-10 12:00:00');
INSERT INTO cart (user_id, Lid, created) VALUES (6, 2, TIMESTAMP '2026-10-10 12:00:00');
INSERT INTO cart (user_id, Lid, created) VALUES (7, 1, TIMESTAMP '2026-10-10 12:00:00');

-- ---- settlements ----------------------------------------------------
INSERT INTO settlements (sid, user_id, period, revenue, deduction, amount, status, requested, approved_by, approved_at)
VALUES (1, 2, '2026.09', 68000, 13600, 54400, 'approved',  TIMESTAMP '2026-10-01 10:00:00', 1,    TIMESTAMP '2026-10-02 10:00:00');
INSERT INTO settlements (sid, user_id, period, revenue, deduction, amount, status, requested, approved_by, approved_at)
VALUES (2, 3, '2026.09',     0,     0,     0, 'requested', TIMESTAMP '2026-10-02 10:00:00', NULL, NULL);

-- ---- assignments ----------------------------------------------------
INSERT INTO assignments (aid, Lid, user_id, work, work_filename, Wnote, submitted, score, feedback, feedback_at)
VALUES (1, 1, 5, '/uploads/assignments/1_5_report.pdf', 'xss_report.pdf', 'Reflected XSS 재현 과정 정리', TIMESTAMP '2026-09-25 22:00:00', 90, 'XSS 재현 과정이 깔끔합니다. CSP 우회 부분만 보완해 주세요.', TIMESTAMP '2026-09-27 10:00:00');
INSERT INTO assignments (aid, Lid, user_id, work, work_filename, Wnote, submitted, score, feedback, feedback_at)
-- 채점 전
VALUES (2, 3, 6, '/uploads/assignments/3_6_report.pdf', 'ec2.png', NULL, TIMESTAMP '2026-10-08 21:00:00', NULL, NULL, NULL);

-- ---- reviews --------------------------------------------------------
INSERT INTO reviews (Lid, user_id, star, review, created) VALUES (1, 5, 5, '실습 위주라 이해가 잘 됩니다.',     TIMESTAMP '2026-09-26 10:00:00');
INSERT INTO reviews (Lid, user_id, star, review, created) VALUES (2, 5, 4, '패킷 캡처 예제가 좋았어요.',         TIMESTAMP '2026-10-03 10:00:00');
INSERT INTO reviews (Lid, user_id, star, review, created) VALUES (3, 6, 4, '무료인데 알찹니다. 후속 강의 원해요.', TIMESTAMP '2026-10-09 10:00:00');

-- ---- qna (답변 완료 / 비밀글 대기 / 대기) ------------------------------
INSERT INTO qna (qid, Lid, user_id, title, question, answer, answered_by, answered_at, isSecret, created)
VALUES (1, 1, 5, 'Burp Suite 프록시 설정 질문', '브라우저에서 인증서 오류가 납니다.', 'Burp CA 인증서를 브라우저 신뢰 저장소에 추가해 주세요.', 2, TIMESTAMP '2026-09-18 11:00:00', 0, TIMESTAMP '2026-09-17 21:00:00');
INSERT INTO qna (qid, Lid, user_id, title, question, answer, answered_by, answered_at, isSecret, created)
VALUES (2, 2, 5, '[비밀] 실습 서버 접속 정보', '실습 VM 비밀번호를 잊어버렸습니다.', NULL, NULL, NULL, 1, TIMESTAMP '2026-10-04 19:00:00');
INSERT INTO qna (qid, Lid, user_id, title, question, answer, answered_by, answered_at, isSecret, created)
VALUES (3, 3, 6, 'IAM 사용자와 루트 계정 차이', '실습은 어느 계정으로 하나요?', NULL, NULL, NULL, 0, TIMESTAMP '2026-10-10 08:30:00');

-- ---- inquiries ------------------------------------------------------
INSERT INTO inquiries (iqid, user_id, title, content, answer, answered_by, answered_at, created)
VALUES (1, 7, '강사 신청 방법 문의', '강사 신청은 어디서 하나요?', '마이페이지 > 강사 신청 메뉴에서 하실 수 있습니다.', 1, TIMESTAMP '2026-10-02 10:00:00', TIMESTAMP '2026-10-01 18:00:00');
INSERT INTO inquiries (iqid, user_id, title, content, answer, answered_by, answered_at, created)
VALUES (2, 6, '결제가 안 끝났어요', '주문 3번 결제창이 닫혔습니다. 다시 결제할 수 있나요?', NULL, NULL, NULL, TIMESTAMP '2026-10-11 20:10:00');

-- ---- posts / comments -----------------------------------------------
INSERT INTO posts (pid, user_id, category, title, content, created) VALUES (1, 5, 'study', '정보처리기사 같이 공부하실 분', '주 2회 온라인 스터디 모집합니다.', TIMESTAMP '2026-10-05 20:00:00');
INSERT INTO posts (pid, user_id, category, title, content, created) VALUES (2, 2, NULL, '[공지] 웹 해킹 입문 2장 자료 갱신', '실습 자료 PDF 가 새 버전으로 바뀌었습니다.', TIMESTAMP '2026-10-06 09:00:00');

INSERT INTO comments (cmid, pid, user_id, content, created) VALUES (1, 1, 6, '저 참여할게요!',          TIMESTAMP '2026-10-05 21:00:00');
INSERT INTO comments (cmid, pid, user_id, content, created) VALUES (2, 1, 7, '요일이 언제인가요?',        TIMESTAMP '2026-10-06 08:00:00');
INSERT INTO comments (cmid, pid, user_id, content, created) VALUES (3, 2, 5, '감사합니다. 확인했습니다.', TIMESTAMP '2026-10-06 10:00:00');

PROMPT [3/4] COMMIT
COMMIT;

PROMPT [4/4] reset identity counters
ALTER TABLE users       MODIFY (user_id GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE lectures    MODIFY (Lid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE coupons     MODIFY (cid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE orders      MODIFY (oid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE order_items MODIFY (oiid    GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE settlements MODIFY (sid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE assignments MODIFY (aid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE qna         MODIFY (qid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE inquiries   MODIFY (iqid    GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE posts       MODIFY (pid     GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));
ALTER TABLE comments    MODIFY (cmid    GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE));

WHENEVER SQLERROR CONTINUE
SET FEEDBACK ON

PROMPT row counts
SELECT 'users' t, COUNT(*) n FROM users UNION ALL
SELECT 'instructors',  COUNT(*) FROM instructors  UNION ALL
SELECT 'lectures',     COUNT(*) FROM lectures     UNION ALL
SELECT 'enrollments',  COUNT(*) FROM enrollments  UNION ALL
SELECT 'cart',         COUNT(*) FROM cart         UNION ALL
SELECT 'coupons',      COUNT(*) FROM coupons      UNION ALL
SELECT 'user_coupons', COUNT(*) FROM user_coupons UNION ALL
SELECT 'orders',       COUNT(*) FROM orders       UNION ALL
SELECT 'order_items',  COUNT(*) FROM order_items  UNION ALL
SELECT 'settlements',  COUNT(*) FROM settlements  UNION ALL
SELECT 'assignments',  COUNT(*) FROM assignments  UNION ALL
SELECT 'reviews',      COUNT(*) FROM reviews      UNION ALL
SELECT 'qna',          COUNT(*) FROM qna          UNION ALL
SELECT 'inquiries',    COUNT(*) FROM inquiries    UNION ALL
SELECT 'posts',        COUNT(*) FROM posts        UNION ALL
SELECT 'comments',     COUNT(*) FROM comments;
