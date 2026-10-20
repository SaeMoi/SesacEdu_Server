# 강의 API

강의 목록·상세, 수강 화면(영상·목차·자료·과제·수료), 리뷰, Q&A 를 다룬다. 데이터는 EC2 ② 의 Oracle(Docker, Oracle 26ai Free, `FREEPDB1`)에 있고 다른 DB 는 쓰지 않는다.

## 실행

`.env` 에 다음 값을 둔다. `.env` 는 git 에 올리지 않는다.
DB 는 사설 서브넷에 있어서 로컬에서는 `aws login` 뒤 EC2 DB 터널을 열고 `localhost:1521` 로 붙는다(팀 공유 EC2 DB 로컬 접속 가이드).

```
DB_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1
DB_USERNAME=...   # 예전 이름 DB_USER 도 읽는다
DB_PASSWORD=...
JWT_SECRET=...   # 32바이트 이상
```

JDK 11 에서 실행한다.

```powershell
.\gradlew.bat bootRun      # http://localhost:8080, Swagger 는 /swagger
.\gradlew.bat test         # H2·목 객체로 돈다. 실제 DB 테스트는 건너뛴다
```

실제 DB 에서 강의 SQL 을 확인할 때만 터널을 연 뒤 `LectureRdsRepositoryTest` 를 따로 켠다. 쓰기는 트랜잭션 안에서 실행하고 끝나면 롤백해 DB 데이터는 바뀌지 않는다.

```powershell
$env:RUN_DB_TESTS = "true"; .\gradlew.bat test --tests "*LectureRdsRepositoryTest"
```

## 인증

- 요청 헤더 `Authorization: Bearer {JWT}`.
- 검증: JWT 서명·만료만 본다. 로그인 API 와 같은 방식이며 서버는 토큰을 저장하지 않는다(DB 조회 없음).
- 로그아웃하거나 다시 로그인해도 이전 토큰은 만료까지 쓸 수 있다.
- 개발용 `GET /dev/token?userId={번호}&auth={0~4}` 는 로그인 없이 토큰을 만들어 돌려준다.

| 상황 | 응답 |
|---|---|
| 헤더 없음 | 공개 API 는 비로그인으로 처리, 그 밖은 401 `UNAUTHORIZED` |
| Bearer 형식 아님·서명 틀림 | 401 `UNAUTHORIZED` |
| 만료 | 401 `TOKEN_EXPIRED` |

## 접근 규칙

| 구분 | 경로 | 조건 |
|---|---|---|
| 공개 | `GET /lectures`, `/lectures/{Lid}`, `/lectures/{Lid}/preview` | `status='open'` 강의만. 없거나 draft·closed 는 404 |
| 공개 | `GET /lectures/{Lid}/reviews`, `/lectures/{Lid}/qna` | 목록의 비밀글 제목은 작성자·담당 강사가 아니면 `비밀글입니다.` |
| 비밀글 | `GET /lectures/{Lid}/qna/{qnaid}` | 공개글은 누구나. 비밀글은 비로그인 401, 작성자·담당 강사가 아니면 403 `SECRET_QNA` |
| 수강자 | `/learn`, `/learn/video`, `/learn/position`, `/complete`, `/material`, `/material/download`, `/assignment`, `/assignments/**` | 로그인 + `enrollments(user_id, Lid)` 행. 없으면 403 `NOT_ENROLLED`. 강의는 open·closed 만 |
| 수강자 | `POST·PUT·DELETE /reviews`, `POST /qna` | 로그인 + 수강 + 공개 강의 |

## 엔드포인트

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/lectures?keyword&category&level&sort&page&size` | `lectures[]`, `page` |
| GET | `/lectures/{Lid}` | 상세, `viewerState`(guest·member·enrolled). enrolled 는 `done`·`position`·각 화면 주소 |
| GET | `/lectures/{Lid}/preview` | 맛보기 영상(Range 지원) |
| GET | `/lectures/{Lid}/learn` | `videoUrl`, `duration`, `position`, `done`, `chapters[{startSec,title}]` |
| GET | `/lectures/{Lid}/learn/video` | 본 영상(Range 지원) |
| PUT | `/lectures/{Lid}/learn/position` | body `{position}` → 204 |
| POST | `/lectures/{Lid}/complete` | 저장 위치가 영상 길이에 닿으면 수료. 이미 수료 409 `ALREADY_COMPLETED`, 덜 봄 409 `NOT_WATCHED_ENOUGH` |
| GET | `/lectures/{Lid}/material` | `filename`, `downloadUrl` |
| GET | `/lectures/{Lid}/material/download?file` | 자료 파일. `file` 을 주면 같은 자료 폴더의 다른 파일(부록 등). 경로가 저장소 밖이면 403 `INVALID_PATH` |
| GET | `/lectures/{Lid}/assignment` | 과제 안내와 내 제출물 |
| POST | `/lectures/{Lid}/assignments` | body `{work, workFilename, note}`. 파일·내용 중 하나 필수, 내용 3000자. 1인 1건(409 `ALREADY_SUBMITTED`) → 201 `{id}`. `work` 가 http·https 링크면 서버가 받아 보관한다(5MB, 막힌 주소 400 `BLOCKED_URL`, 실패 400 `LINK_FETCH_FAILED`) |
| GET·PUT·DELETE | `/lectures/{Lid}/assignments/{aid}` | 제출물 조회·수정·삭제. 채점 뒤 수정·삭제 409 `ALREADY_SCORED`. 수정 때 `work` 를 안 보내면 기존 첨부를 유지한다 |
| GET | `/lectures/{Lid}/assignments/{aid}/file` | 제출 파일. 파일 없이 낸 경우 404 `SUBMISSION_FILE_NOT_FOUND` |
| GET | `/lectures/{Lid}/reviews?page&size` | `reviews[]`, `page`, `rating`, `reviewCount` |
| POST·PUT | `/lectures/{Lid}/reviews` | body `{star 1~5, review ≤2000}`. 1인 1건(409 `ALREADY_REVIEWED`) |
| DELETE | `/lectures/{Lid}/reviews` | 내 리뷰 삭제 → 204 |
| GET | `/lectures/{Lid}/qna?status=answered\|waiting&page&size` | `questions[]`, `page` |
| POST | `/lectures/{Lid}/qna` | body `{title ≤200, question ≤4000, secret}` → 201 `{id}` |
| GET | `/lectures/{Lid}/qna/{qnaid}` | 질문·답변 상세 |

정렬 값: `lid_desc`(= `id_desc`, `default`), `latest`, `price_asc`(= `low`), `price_desc`(= `pric_desc`, `high`). 분류: `개발 기초` `로그 분석` `웹 보안` `네트워크` `클라우드`. 난이도: `입문` `초급` `중급`. `all` 은 필터 없음.

오류 본문은 모두 `{code, message}` 다. 노션 API 명세서(카테고리 "강의")에 엔드포인트별 요청·응답 예시가 있다.

## 파일

DB 의 경로 컬럼(`video`, `material`, `preview`, `assignments.work`)은 `app.storage.root`(기본 `./storage`) 아래 상대 경로로 읽는다.

## 테스트

| 파일 | 내용 |
|---|---|
| `LectureRdsRepositoryTest` | 강의 SQL 을 실제 DB 에서 실행. 읽기와 쓰기(롤백). `RUN_DB_TESTS=true` 일 때만 |
| `global/auth/JwtAccessTokenVerifierTest` | 토큰 서명·만료 검증. DB 는 보지 않는다(`.env` 의 `JWT_SECRET` 사용) |
| `Lecture*ControllerTest`, `Lecture*WriteTest`, `LectureServiceTest`, `LecturePreviewTest`, `LectureFileStorageTest` | HTTP 계약·서비스 분기. 저장소는 목 객체 |
| `LectureApplicationTest`, `SesacEduApplicationTests` | 실제 스프링 설정 로딩. `LectureApplicationTest` 는 `.env` 를 읽고 강의 저장소만 목 객체, `SesacEduApplicationTests` 는 test 프로필(H2) |
