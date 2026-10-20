# Windows / Mac to-do and API testing

## 1. To-do per machine

### Mac (Apple silicon) — done
- [x] JDK 17 installed (`brew install openjdk@17`)
- [x] `~/.gradle/gradle.properties` → `org.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- [x] VS Code User settings point at JDK 17
- [x] `./gradlew test` passes, `./gradlew bootRun` connects to RDS
- [ ] (optional) Oracle Instant Client for macOS ARM64 (Basic + SQL*Plus) if you want `sqlplus`

### Windows — to do before the next build
- [ ] Create `C:\Users\<you>\.gradle\gradle.properties` with one line:
      `org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot`
- [ ] VS Code → Command Palette → *Preferences: Open User Settings (JSON)* and add:
      ```json
      "java.configuration.runtimes": [{ "name": "JavaSE-17", "path": "C:\\Program Files\\Java\\jdk-17", "default": true }],
      "java.import.gradle.java.home": "C:\\Program Files\\Java\\jdk-17",
      "jdk.jdkhome": "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot",
      "jdk.project.jdkhome": "C:\\Program Files\\Java\\jdk-17"
      ```
- [ ] Wait for OneDrive to finish syncing, then run `gradlew test --no-watch-fs --rerun-tasks` once to confirm

### Both machines, every time you switch
- [ ] Stop `bootRun` / tests on the other machine and let OneDrive finish syncing
- [ ] Never write `C:\` or `\` in code; use relative paths with `Paths.get(...)`

## 2. Automated API tests (`src/test/...`)

They use an in-memory H2 database (`application-test.yml`, `schema-test.sql`), so they need **no RDS, no internet and no .env**.
Each `*ApiTest` / `*FlowTest` class starts Spring and calls the real endpoints.

| What | Mac (zsh) | Windows (PowerShell) |
|---|---|---|
| All tests | `./gradlew test --no-watch-fs` | `.\gradlew test --no-watch-fs` |
| Force re-run (no cache) | add `--rerun-tasks` | add `--rerun-tasks` |
| One class | `./gradlew test --tests com.example.sesacedu.LoginApiTest` | `.\gradlew test --tests com.example.sesacedu.LoginApiTest` |
| One method | `--tests 'com.example.sesacedu.LoginApiTest.methodName'` | `--tests "com.example.sesacedu.LoginApiTest.methodName"` |
| HTML report | `open ~/gradle-build/sesacedu/reports/tests/test/index.html` | `start C:\gradle-build\sesacedu\reports\tests\test\index.html` |

In cmd.exe instead of PowerShell, drop the `.\` (`gradlew test`).

Test classes: `LoginApiTest`, `SignupApiTest`, `EmailAuthFlowTest`, `JwtFlowTest`, `UserFlowTest`, `PaymentApiTest`, `PurchaseFlowTest`,
`MypageApiTest`, `MypageBuyApiTest`, `MypageBuyHistoryApiTest`, `MypageCartBenefitApiTest`, `MypageLearningApiTest`, `MypageProfileApiTest`,
`global.JwtUtilTest`, `SesacEduApplicationTests`, and `GmailSmtpLiveTest` (see below).

### Gmail live test (the 1 "skipped" test)
`GmailSmtpLiveTest` only runs when `MAIL_USERNAME` and `MAIL_PASSWORD` are **OS environment variables** (the `.env` file is not enough).
It logs in to Gmail and sends no mail.

- Mac: `(set -a; . ./.env; set +a; ./gradlew test --tests com.example.sesacedu.GmailSmtpLiveTest)`
- Windows (PowerShell):
  `$env:MAIL_USERNAME="..."; $env:MAIL_PASSWORD="<app password>"; .\gradlew test --tests com.example.sesacedu.GmailSmtpLiveTest`

## 3. Manual API check against RDS

1. Start the server: `./gradlew bootRun` (Mac) / `.\gradlew bootRun` (Windows). It reads `.env` from the project root.
2. Swagger UI: http://localhost:8080/swagger-ui/index.html (same on both).
3. curl from the project root (works on both; on Windows type `curl.exe`, not `curl`):
   `curl -H "Content-Type: application/json" -d @login.json http://localhost:8080/login`
4. Stop with Ctrl+C.
