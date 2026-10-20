# SesacEdu API: Swagger test samples

Swagger UI: http://localhost:8080/swagger-ui/index.html
Checked against the running server on 2026-10-14 (after the timezone and signup-phone fixes).
Data below is from your RDS: lectures 1–10 are open (51,000–60,000 KRW); test user 41 = `kindkim521@naver.com` already owns lectures 1, 2, 3 (paid orders 39, 40, 41).

Every response is `{ "code", "message", "result" }`. Error 400s carry `result.errors: [{ field, reason }]`.

---

## 0. Read this first: Swagger limitations

| Problem | Effect in Swagger | Workaround until fixed |
|---|---|---|
| **No security scheme**: there's no **Authorize** button, and protected endpoints have no Authorization field | Every `/mypage/**`, `/logout`, `/login/replc` and `/payment` call from "Try it out" returns **401 UNAUTHORIZED** | Use the curl commands below, or Postman (header `Authorization: Bearer <token>`) |
| **`X-Reauth-Token` header not shown** on `PUT /mypage/profile` and `PUT /mypage/password` | Always **403 REAUTH_REQUIRED** from Swagger | curl/Postman |
| **Two classes are both named `VerifyRequest`** (`email_auth_verify` and `verify_password`) | `/email-auth/verify` shows the example body `{pw, purpose}`, which is **wrong**: the real body is `{email, code}` | Type the body from §1.2 by hand |
| `/mypage/edit_prof` shown as `application/json` | Swagger can't upload the file correctly | curl `-F image=@file.png` |
| `/virtualPG/buy/confirm` body is a raw string and needs `X-VPG-Secret` | Not callable from Swagger (server-to-server only) | Called internally by `POST /payment` |
| `courseId`, `orderId`, `card`, `transfer` shown as `object` | Example Value is `{}` | Use the samples below |

Fixing the first three is a small code change (a bearer scheme plus header parameters in `SwaggerConfig`, and renaming one `VerifyRequest`). Not done yet.

Getting a token: call `POST /login`, then copy the response **header** `Authorization: Bearer eyJ...`. The token isn't in the body. It's valid for 60 minutes.

In the curl examples, `$T` = the token after `Bearer ` and `$R` = a reauthToken.
On Windows Git Bash/PowerShell, send Korean text from a UTF-8 file (`--data-binary @body.json`); typed inline, it arrives garbled.

---

## 1. Auth (no token needed)

### 1.1 POST /email-auth: sends a real mail
```json
{ "email": "newuser@example.com" }
```
- 200 `EMAIL_SENT` `{expiresIn:300, resendAfter:60}`
- Invalid → 400 `INVALID_INPUT`: `{ "email": "not-an-email" }` · `{}`
- Already registered → 409 `DUPLICATE_EMAIL`: `{ "email": "kindkim521@naver.com" }`
- Again within 60 s → 429 `TOO_MANY_REQUESTS` `{retryAfter}`
- SMTP failure → 503 `EMAIL_SEND_FAILED`

### 1.2 POST /email-auth/verify (ignore Swagger's example body)
```json
{ "email": "newuser@example.com", "code": "123456" }
```
- 200 `EMAIL_VERIFIED` `{verificationToken, expiresIn:1800}`
- Wrong code → 400 `INVALID_CODE` · no request or expired → 400 `CODE_EXPIRED`
- Invalid → 400 `INVALID_INPUT`: `{ "email": "newuser@example.com", "code": "12a45" }`

### 1.3 POST /signup: phone is now required
```json
{
  "email": "newuser@example.com",
  "verificationToken": "<from /email-auth/verify>",
  "pw": "Passw0rd!",
  "pwcheck": "Passw0rd!",
  "name": "홍길동",
  "nickname": "newbie01",
  "phone": "01012345678",
  "b_date": "2000-01-01",
  "address": "서울특별시 영등포구 선유로 1"
}
```
- 201 `SIGNUP_SUCCESS` `{memberId}`; role is always GENERAL (extra `"auth":3` is ignored)
- Missing phone → 400 `INVALID_INPUT` errors `[phone: 연락처를 입력해주세요.]`
- Bad phone → 400: `"phone": "010-1234-5678"` (hyphens) · `"0101234"` (too short)
- All bad at once → 400 listing every field:
```json
{ "email": "x", "pw": "short", "pwcheck": "diff", "name": "열한글자가넘는이름입니다", "nickname": "a", "phone": "12", "b_date": "2026-02-30", "address": "" }
```
- Wrong/used token → 400 `INVALID_VERIFICATION` · duplicate → 409 `DUPLICATE_EMAIL` / `DUPLICATE_NICKNAME`

### 1.4 POST /login
```json
{ "email": "kindkim521@naver.com", "pw": "<test password>" }
```
- 200 `LOGIN_SUCCESS`, token in the **response header** `Authorization`
- Wrong password / unknown user → 401 `LOGIN_FAILED` · restricted account → 403 `ACCOUNT_RESTRICTED`
- Invalid → 400: `{}` · `{ "email": "bad", "pw": "x" }` · pw > 30 chars

### 1.5 POST /logout: header only, no body
`Authorization: Bearer $T` → 204 (sending it again also gives 204). No token → 401.
Intended vulnerability: the token keeps working until it expires.

### 1.6 POST /login/replc: header with an **expired** token, no body
- 200 `REISSUE_SUCCESS` `{reissueNum}` + new token header
- Token not expired yet → 400 `TOKEN_NOT_EXPIRED` · more than 24 h after expiry → 401 `REISSUE_EXPIRED` · no reissues left → 401 `REISSUE_LIMIT`

---

## 2. Mypage reads (header `Authorization: Bearer $T`)

| Request | OK | Bad example → 400 `INVALID_INPUT` |
|---|---|---|
| `GET /mypage` | 200 `PROFILE_FOUND` | (no params) |
| `GET /mypage/courses?status=ALL&sort=RECENT_ENROLL&page=0&size=10` | 200 `MY_COURSES_FOUND` | `?status=DONE&size=99` |
| `GET /mypage/qna?status=ALL&sort=RECENT&page=0&size=10` | 200 `MY_QNA_FOUND` | `?sort=OLD&page=-1` |
| `GET /mypage/cart` | 200 `CART_FOUND` | |
| `GET /mypage/bnf?page=0&size=10` | 200 `BENEFITS_FOUND` | `?size=0` |
| `GET /mypage/buy` | 200 `CHECKOUT_ITEMS_FOUND` | |
| `GET /mypage/buy/cpn?courseIds=4,5&couponId=1` | 200 `COUPON_APPLIED` (needs 4, 5 in cart + an owned coupon) | `?courseIds=4,x&couponId=0` |
| `GET /mypage/buy/pnt?courseIds=4,5&usePoint=0` | 200 `POINT_APPLIED` | `?courseIds=4` (usePoint missing) |
| `GET /mypage/buy/history?status=ALL&page=0&size=10` | 200 `ORDER_HISTORY_FOUND` | `?status=PENDING` |
| `GET /mypage/buy/history/41` | 200 `ORDER_DETAIL_FOUND` | `/1`, `/abc`, `/-5` → 404 `ORDER_NOT_FOUND` |

Values: courses status `ALL|IN_PROGRESS|COMPLETED`, sort `RECENT_ENROLL|TITLE`; qna status `ALL|PENDING|ANSWERED`, sort `RECENT|TITLE|STATUS`; history status `ALL|PAID|REFUNDED`; size 1–50.
buy/cpn and buy/pnt: a course that isn't in the cart → 409 `CART_CHANGED {unavailableCourseIds}`. Bad coupon → 400 `COUPON_NOT_APPLICABLE {reason}`. usePoint > 0 → 400 `INVALID_POINT` (points are disabled, so the balance is 0).

---

## 3. Cart

### POST /mypage/cart/add
```json
{ "courseId": 4 }
```
- 201 `CART_ITEM_ADDED {courseId, totalCount}` (extra `price` is ignored)
- 400: `{}` · `{ "courseId": "4" }` (string) · `{ "courseId": 1.5 }` · `{ "courseId": -1 }`
- 404 `COURSE_NOT_FOUND`: `{ "courseId": 999999 }`
- 409 `ALREADY_IN_CART` (add 4 twice) · 409 `ALREADY_ENROLLED`: `{ "courseId": 1 }` for user 41 · 409 `CART_FULL` (50+ items)

### DELETE /mypage/cart/del
```json
{ "courseIds": [4, 5] }
```
- 200 `CART_ITEMS_DELETED {deletedCount, totalCount}`; ids not in your cart are ignored
- 400: `{}` · `{ "courseIds": [] }` · `{ "courseIds": ["4"] }` · more than 50 ids

---

## 4. Coupon registration: POST /mypage/cpn
```json
{ "couponCode": "WEBHACK5000OFF" }
```
(Spaces/hyphens are removed and letters uppercased, so `"webhack-5000-off"` also works.) The codes in `db/seed.sql` are now WEBHACK5000OFF / ALLCOURSE3000OFF / NETOPEN7000OFF (expired) / FIRSTCOME10KOFF (sold out). **They only exist if seed.sql is loaded**; on your current RDS, use a real `coupons.code` that is 12–16 characters.
- 201 `COUPON_REGISTERED` · same code twice → 409 `COUPON_ALREADY_REGISTERED` · sold out → 409 `COUPON_SOLD_OUT`
- 400 `INVALID_INPUT`: `{ "couponCode": "WEB5000" }` (too short) · `{}`
- 400 `INVALID_COUPON_CODE`: `{ "couponCode": "ABCDEFGH1234" }` (unknown or expired)

---

## 5. Profile (needs `Authorization` **and** `X-Reauth-Token`, so use curl, not Swagger)

### 5.1 POST /mypage/verify-password
```json
{ "pw": "<current password>", "purpose": "PROFILE_UPDATE" }
```
`purpose`: `PROFILE_UPDATE` (for PUT /mypage/profile) or `PASSWORD_CHANGE` (for PUT /mypage/password).
- 200 `PASSWORD_VERIFIED {reauthToken, expiresIn:600}`
- 401 `PASSWORD_MISMATCH` · 400: `{}` · `{ "pw": "x", "purpose": "ADMIN" }`

### 5.2 PUT /mypage/profile: header `X-Reauth-Token: $R` (PROFILE_UPDATE)
```json
{ "name": "홍길동", "nickname": "newnick01", "email": "kindkim521@naver.com", "phone": "01012345678", "birthDate": "2000-01-01" }
```
- 200 `PROFILE_UPDATED` (same shape as GET /mypage; extra `auth`/`role`/`memberId` are ignored)
- 403 `REAUTH_REQUIRED` (no reauth token, or one with purpose PASSWORD_CHANGE)
- 400 all fields: `{ "name": "", "nickname": "a", "email": "bad", "phone": "12", "birthDate": "2999-01-01" }`
- 409 `DUPLICATE_NICKNAME` / `DUPLICATE_EMAIL`

### 5.3 PUT /mypage/password: header `X-Reauth-Token: $R` (PASSWORD_CHANGE)
```json
{ "newPw": "NewPassw0rd!", "newPwCheck": "NewPassw0rd!" }
```
- 200 `PASSWORD_CHANGED` · 403 `REAUTH_REQUIRED`
- 400: `{ "newPw": "abc", "newPwCheck": "abd" }` · same as current → 400 `SAME_AS_CURRENT_PASSWORD`

### 5.4 POST /mypage/edit_prof: multipart, part name `image`
```bash
curl.exe -H "Authorization: Bearer $T" -F "image=@photo.png" http://localhost:8080/mypage/edit_prof
```
- 200 `PROFILE_IMAGE_UPDATED {profileImageUrl}` (saved as a 400×400 PNG)
- No `image` part / two files → 400 `INVALID_INPUT` · a .txt file → 415 `UNSUPPORTED_IMAGE_TYPE` · broken PNG → 400 `INVALID_IMAGE` · > 5 MB → 413 · > 10 uploads/hour → 429

---

## 6. Order and payment

Flow: cart/add → `GET /mypage/buy` → (optional cpn/pnt preview) → `POST /mypage/buy` → `POST /payment` with that `orderId`.
For user 41, use lectures **4–10** (1–3 are already owned).

### 6.1 POST /mypage/buy
```json
{ "courseIds": [4], "couponId": null, "usePoint": 0, "payMethod": "CARD" }
```
```json
{ "courseIds": [5, 6], "usePoint": 0, "payMethod": "TRANSFER" }
```
- 201 `ORDER_CREATED {orderId, orderName, amount, ...}` (status pending; extra `amount`/`price` are ignored)
- Total 0 KRW → 201 `ORDER_COMPLETED` (paid immediately, no PG)
- 400: `{}` · `{ "courseIds": [4], "payMethod": "CASH" }`
- 409 `CART_CHANGED`: `{ "courseIds": [9], "payMethod": "CARD" }` when 9 isn't in the cart
- 400 `INVALID_POINT`: `"usePoint": 100` · 400 `COUPON_NOT_APPLICABLE`: `"couponId": 999`

### 6.2 POST /payment: CARD order
```json
{ "orderId": 42, "card": { "number": "4111111111111111", "expiry": "12/30", "cvc": "123", "installment": 0 } }
```
installment: `0` (lump sum) or `2`–`12`. Number: 14–16 digits, Luhn-valid.

| Card number | Result |
|---|---|
| `4111111111111111` (VISA) / `5555555555554444` (Master) | 200 `PAYMENT_COMPLETED` |
| `4111111111150002` | 402 `PAYMENT_DECLINED` reason `INSUFFICIENT_BALANCE` |
| `4111111111140003` | 402 `PAYMENT_DECLINED` reason `LIMIT_EXCEEDED` |
| `4111111111180009` | slow PG → 502 `PG_UNAVAILABLE` (the PG still records it as approved; retrying is safe) |
| `4111111111111111` with `"expiry": "01/20"` | 402 `PAYMENT_DECLINED` reason `EXPIRED_CARD` |
| `4111111111111112` (fails Luhn) | 402 `PAYMENT_DECLINED` reason `INVALID_CARD` |
| amount > 1,000,000 | 402 `LIMIT_EXCEEDED` |

### 6.3 POST /payment: TRANSFER order
```json
{ "orderId": 43, "transfer": { "bankCode": "004", "accountNumber": "1234567891", "holderName": "tester" } }
```
Valid bank codes: 002 003 004 007 011 020 023 027 031 032 034 035 037 039 045 048 071 081 088 089 090 092.
Account number ending `0002` → 402 declined `INSUFFICIENT_BALANCE`; bank code `999` → 402 declined `INVALID_ACCOUNT`. Cases I actually ran on 2026-10-14: approved 4111…1111, 0002, expired card, approved transfer 004, paying twice. The rest are from vpg_confirm.decide(). holderName 1–20 characters.

### 6.4 Payment errors
- 400: `{ "orderId": "abc" }` · `{ "orderId": 42 }` (card missing) · transfer sent for a CARD order ·
  `{ "orderId": 42, "card": { "number": "12", "expiry": "13/99", "cvc": "1", "installment": 1 } }` (4 field errors)
- 404 `ORDER_NOT_FOUND`: `{ "orderId": 1, "card": {...} }` (someone else's order)
- 409 `ALREADY_PAID`: pay 41 again · 409 `CART_CHANGED` if the lecture closed after ordering

### 6.5 POST /virtualPG/buy/confirm
Server-to-server only. Without `X-VPG-Secret` (or with any `Origin` header) → 401 `VPG_UNAUTHORIZED`. Don't call it from Swagger; `POST /payment` calls it.

---

## 7. curl quick start (Git Bash)

```bash
B=http://localhost:8080
T=$(curl.exe -s -D - -o /dev/null -H "Content-Type: application/json" \
    -d '{"email":"kindkim521@naver.com","pw":"<test password>"}' $B/login | sed -n 's/^[Aa]uthorization: Bearer //p' | tr -d '\r')
curl.exe -H "Authorization: Bearer $T" $B/mypage
curl.exe -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"courseId":4}' $B/mypage/cart/add
curl.exe -H "Authorization: Bearer $T" -H "Content-Type: application/json" -d '{"courseIds":[4],"payMethod":"CARD"}' $B/mypage/buy
curl.exe -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
     -d '{"orderId":<id>,"card":{"number":"4111111111111111","expiry":"12/30","cvc":"123","installment":0}}' $B/payment
```
