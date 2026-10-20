# SesacEdu_Server
SesacEdu_Server

## 실행 준비

### 1. `.env` (프로젝트 루트, git 에 올리지 않음)

`global/DotenvLoader` 가 기동 시 읽는다. `KEY=VALUE` 한 줄에 하나. 값을 `"..."` 나 `'...'` 로 감싸도 되고(양끝 따옴표는 벗김),
그 밖의 문자는 적은 그대로 쓴다. 같은 이름의 OS 환경변수가 있으면 그쪽이 우선한다.

```
DB_URL=jdbc:oracle:thin:@//<RDS 엔드포인트>:1521/<DB 이름>
DB_USERNAME=<RDS 사용자>
DB_PASSWORD=<비밀번호>
JWT_SECRET=<32바이트 이상 임의 문자열>
MAIL_USERNAME=<프로젝트 전용 Gmail 주소>
MAIL_PASSWORD=<Gmail 앱 비밀번호>
CORS_ALLOWED_ORIGINS=http://localhost:3000
```

- `DB_URL` 은 서비스 이름 형식(`@//호스트:포트/이름`)을 쓴다. RDS 콘솔 → 데이터베이스 → 구성 탭의 **DB 이름**(기본 `ORCL`)이 그 이름이다.
  SID 형식(`@호스트:1521:ORCL`)도 되지만 서비스 이름 형식을 권장한다.
- `CORS_ALLOWED_ORIGINS` 는 프런트 주소 목록(쉼표 구분). `localhost:3000` 처럼 스킴을 빼면 `http://` 를 붙여 해석한다.
  비우면 브라우저의 교차 출처 호출을 모두 막는다.

`.env` 는 실행하는 현재 폴더 기준으로 찾는다. `gradlew bootRun` 과 IDE 실행은 프로젝트 루트가 현재 폴더라 그대로 된다.
jar/war 를 다른 폴더에서 실행할 때는 그 폴더에 `.env` 를 두거나 같은 이름의 환경변수를 넣는다.

### 2. DB 스키마 (AWS RDS for Oracle)

`db/seed.sql` 이 `db/schema.sql` 을 불러 16개 테이블을 **지우고 다시 만든 뒤** 샘플 데이터를 넣는다.
RDS 마스터 사용자(또는 `.env` 의 `DB_USERNAME`)로 접속해 실행한다. SYSDBA 는 필요 없다.

```
chcp 65001
set NLS_LANG=KOREAN_KOREA.AL32UTF8
sqlplus <DB_USERNAME>@//<RDS 엔드포인트>:1521/<DB 이름>
SQL> @db\seed.sql
```

### 3. 실행 / 테스트

```
gradlew bootRun
gradlew test        # 테스트는 H2 메모리 DB 를 쓰므로 RDS 가 없어도 된다
```

## Windows / Mac 같이 쓰기

프로젝트 파일은 두 기기에서 똑같이 두고, 기기마다 다른 값(JDK 경로 등)은 각 기기의 사용자 설정에 둔다.

### 기기마다 한 번만 하는 설정

Gradle 7.6.4 는 JDK 17 로 돌려야 한다 (더 새 JDK 는 거부).

| | Windows | Mac (Apple silicon) |
|---|---|---|
| JDK 17 설치 | Eclipse Adoptium JDK 17 | `brew install openjdk@17` |
| Gradle 설정 파일 | `C:\Users\<사용자>\.gradle\gradle.properties` | `~/.gradle/gradle.properties` |
| 그 안에 한 줄 | `org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot` | `org.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| 빌드 결과물 | `C:\gradle-build\sesacedu` | `~/gradle-build/sesacedu` |

VS Code 의 JDK 경로(`java.configuration.runtimes`, `java.import.gradle.java.home`, `jdk.*`)도 프로젝트의
`.vscode/settings.json` 이 아니라 각 기기의 **사용자 설정**(명령 팔레트 → Preferences: Open User Settings (JSON))에 넣는다.

### 명령어

| Windows | Mac |
|---|---|
| `gradlew bootRun` | `./gradlew bootRun` |
| `gradlew test` | `./gradlew test` |
| `chcp 65001` | 필요 없음 |
| `set NLS_LANG=KOREAN_KOREA.AL32UTF8` | `export NLS_LANG=KOREAN_KOREA.AL32UTF8` |
| `SQL> @db\seed.sql` | `SQL> @db/seed.sql` |

Mac 의 sqlplus 는 Oracle Instant Client (macOS ARM64, Basic + SQL*Plus) 를 받아 쓴다.

### 주의

- 코드에서 경로는 `Paths.get(...)` 과 상대 경로로 쓴다. `C:\` 나 `\` 를 직접 쓰지 않는다.
- OneDrive 로 동기화하므로 두 기기에서 동시에 편집·실행하지 않고, 동기화가 끝난 뒤 기기를 바꾼다.
