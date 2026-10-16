# 강의 API

강의 목록·상세·맛보기를 다룬다. 데이터는 공유 RDS(Oracle 19)에 있고 다른 DB 는 쓰지 않는다.

## 실행

`.env` 에 다음 값을 둔다. `.env` 는 git 에 올리지 않는다.

```
DB_URL=jdbc:oracle:thin:@//<RDS 엔드포인트>:1521/<서비스명>
DB_USER=...
DB_PASSWORD=...
JWT_SECRET=...   # 32바이트 이상
```

JDK 11 에서 실행한다.

```powershell
.\gradlew.bat bootRun      # http://localhost:8080, Swagger 는 /swagger
.\gradlew.bat test         # 테스트도 같은 RDS 에 붙는다
```

테스트 중 쓰기는 트랜잭션 안에서 실행하고 끝나면 롤백한다. 테스트를 돌려도 RDS 데이터는 바뀌지 않는다.

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

## 엔드포인트

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/lectures?keyword&category&level&sort&page&size` | `lectures[]`, `page` |
| GET | `/lectures/{Lid}` | 상세, `viewerState`(guest·member·enrolled). enrolled 는 `done`·`position`·각 화면 주소 |
| GET | `/lectures/{Lid}/preview` | 맛보기 영상(Range 지원) |

정렬 값: `lid_desc`(= `id_desc`, `default`), `latest`, `price_asc`(= `low`), `price_desc`(= `pric_desc`, `high`). 분류: `개발 기초` `로그 분석` `웹 보안` `네트워크` `클라우드`. 난이도: `입문` `초급` `중급`. `all` 은 필터 없음.

오류 본문은 모두 `{code, message}` 다. 노션 API 명세서(카테고리 "강의")에 엔드포인트별 요청·응답 예시가 있다.

## 파일

DB 의 경로 컬럼(`video`, `material`, `preview`)은 `app.storage.root`(기본 `./storage`) 아래 상대 경로로 읽는다.

## 테스트

| 파일 | 내용 |
|---|---|
| `LectureRdsRepositoryTest` | 강의 SQL 을 RDS 에서 실행. 읽기와 쓰기(롤백) |
| `global/auth/JwtAccessTokenVerifierTest` | DB 토큰 대조·제한 계정. RDS, 롤백 |
| `Lecture*ControllerTest`, `Lecture*WriteTest`, `LectureServiceTest`, `LecturePreviewTest`, `LectureFileStorageTest` | HTTP 계약·서비스 분기. 저장소는 목 객체 |
| `LectureApplicationTest`, `SesacEduApplicationTests` | 실제 스프링 설정 로딩(RDS 연결) |
