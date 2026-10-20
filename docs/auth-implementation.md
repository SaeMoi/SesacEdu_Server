# Authentication implementation decisions

Sources: latest ERD pasted on 2026-10-12 and Notion's active personal API documentation (not the archived copy):

- [POST /login](https://app.notion.com/p/d925c915859182a5951f0184fb1247cd)
- [POST /login/replc](https://app.notion.com/p/3f55c91585918009bcdce614de98f949)
- [POST /logout](https://app.notion.com/p/ec85c9158591827daa4601b7350caf83)
- [POST /signup](https://app.notion.com/p/4665c915859182399534012313db2afe)
- [POST /email-auth](https://app.notion.com/p/ee95c9158591824a9ab101363d3dde9c)
- [POST /email-auth/verify](https://app.notion.com/p/2005c915859182e7aaf381744d3ed21d)

## User-approved differences from Notion

The ERD takes precedence: `users.token` stores the latest issued JWT as a CLOB. No `last_login_at` or `last_login_ip` columns are added. `phone` remains nullable in the schema; these six API request/response contracts do not expose it.

Per the user's later explicit choice, the global filter verifies HS256 signature, expiration, claims, and equality with `users.token`. A new login or reissue replaces that value and invalidates previous tokens. Logout verifies the current token (even if expired) and clears the value. Repeating logout with the cleared token returns 401 UNAUTHORIZED, rather than the documentation's stateless/idempotent 204. Deleted members are rejected. Login and reissue check account restriction as documented; restriction does not independently revoke a current JWT during its validity.

Reissue locks the member row with `SELECT ... FOR UPDATE`, compares the current CLOB in Java, then replaces it in the same transaction. This avoids Oracle CLOB equality limitations and allows only one concurrent reissue per token. A random `jti` claim makes tokens unique even when two logins occur in the same second. The remaining reissue count stays in the signed JWT: 2 on login, then 1, then 0. An unexpired token produces TOKEN_NOT_EXPIRED; the reissue grace period is 24 hours after expiration, rather than an absolute 3-hour wall-clock session.

JWTs are returned only in the Authorization response header. The body uses `{code,message,result}`. Refresh tokens, cookies and server sessions are not used. Database storage makes authentication stateful despite the absence of HTTP sessions.

## Email verification and signup

Email is normalized before validation (whitespace removed, lowercase). Email requests, verification and signup share one synchronized in-memory store. SecureRandom generates six-digit codes (including leading zeroes) and 32-character verification tokens. Codes expire after 5 minutes; signup tokens expire 30 minutes after verification.

Resend replaces any earlier code/token. Successful sends have a 60-second cooldown, maximum 5 per email per rolling hour and 20 per client IP per rolling hour. Separate histories survive resends, signup and expiry cleanup. SMTP failure invalidates the prior entry but does not consume a successful-send quota. The direct connection IP is used; untrusted forwarded headers cannot bypass the IP limit.

Signup holds the store lock through the database transaction and removes the verification only after successful commit. Failed inserts/duplicate checks preserve it for correction. Email uniqueness is enforced by Oracle; nickname uniqueness is an application check only, per the API. Account role/status are always set by the server to 0/active. Signup does not issue a JWT.

Email state is local to one server and is lost on restart. Multiple app instances require a shared store before deployment. Synchronous sending and signup serialize store operations; suitable for the specified single-server exercise.

## Exercise-specific choices retained

The Notion documentation explicitly requests saltless MD5 password hashes, no login lockout, and unlimited verification-code attempts. Those behaviors are preserved for the penetration-testing exercise and are unsuitable for production. Send limits remain enabled. Passwords, JWTs and email codes are not logged.

`email_auth`, `refresh_tokens` and `audit_logs` remain deferred as in the latest ERD/login page. No audit SQL is executed. All database inputs use bound parameters.

## Verification scope

Reusable verification is provided by `global/security/JwtService.java` and the immutable nested return type `JwtService.JwtClaims`. Inject `JwtService` and call `JwtClaims claims = jwtService.verify(authorization)` with the full Bearer header. The returned getters provide member ID (sub), name, role, expiry, remaining reissues and token ID (jti). Verification includes equality with `users.token`; invalid, replaced or expired tokens throw the existing API exception instead of returning decoded data.

The global filter stores this same `JwtClaims` object as the Spring Security principal. Protected controllers can accept `@AuthenticationPrincipal JwtClaims claims` and access the verified values without parsing the token again. Logout/reissue keep their existing row-locked token comparison and expiration rules.

Automated tests use H2 in Oracle mode and a mocked email sender. They cover the API contracts, persisted token state, global authentication, expiry, concurrent reissue, signup, validation, rollback, sending limits and email failures. They do not prove real Oracle driver/CLOB locking behavior or Gmail delivery; verify those with the project's Oracle database and SMTP credentials.

JWT signing, verification, decoded claims, configuration and the filter are consolidated in JwtService.java. JwtSettings and JwtFilter are static nested classes. Logout/reissue use verifyForTokenMutation and retain their row-locked state check.
