# 강의 조회 API

강의 목록·상세 조회. 데이터는 공유 RDS(Oracle 19)에 있고 다른 DB 는 쓰지 않는다.

목록과 비로그인 상세 조회를 구현했다. 로그인 미구매·수강자 분기 로직도 테스트했지만, 실제 JWT 검증기는 아직 연결되지 않았다. Oracle 실서버 연동과 프론트 화면 연동도 별도 확인이 필요하다.

## 요청·응답 계약

| 메서드·경로 | 입력 | 응답 | 권한 |
|---|---|---|---|
| GET `/lectures` | query: keyword, category, level, sort, page, size. body 없음 | lectures 배열, page 객체 | 공개 |
| GET `/lectures/{Lid}` | 양수 정수 강의 번호. body 없음. 로그인 시 Authorization 헤더 | 상세·공개 강사 정보·평점·viewerState | 공개. 개인별 상태는 검증된 사용자 기준 |

두 API 모두 `status='open'`인 강의만 조회한다. 상세의 없는 강의·draft·closed는 404다. 구매자의 closed 강의 접근 정책은 수강 API 작업에서 별도로 확인한다.

### 목록

- keyword: 강의명 부분 검색. 앞뒤 공백을 없애고 빈 값은 필터 없음으로 처리한다. `%`, `_`, `\`는 와일드카드가 아닌 문자로 검색한다.
- category, level: DB 값과 일치 검색. 미지정·공백이면 필터 없음. 미등록 값은 빈 결과다.
- page: 1부터 시작, 기본 1. size: 기본 12, 1~50. 정수 범위를 넘는 offset은 400.
- sort: 기본 `lid_desc`, 허용 `lid_desc`, `price_asc`, `price_desc`, `latest`.
- 노션에 적힌 `id_desc`는 `lid_desc`, `pric_desc`는 `price_desc`의 별칭으로 지원한다. `latest`는 기존 코드 호환용 등록 시각순이다. 가격 동률은 강의 번호 내림차순으로 고정한다.
- 결과가 없으면 `lectures: []`, total/totalPages는 0. 마지막 페이지를 넘으면 빈 배열과 전체 건수를 반환한다.
- rating은 리뷰가 없으면 null, 있으면 소수 첫째 자리까지 반올림. reviewCount는 0 이상이다.

```http
GET /lectures?keyword=보안&sort=price_asc&page=1&size=12
```

```json
{
  "lectures": [
    {
      "lid": 1,
      "name": "웹 보안 입문",
      "headline": "보안의 기초",
      "picture": null,
      "instructor": "예시 강사",
      "category": "웹 보안",
      "level": "입문",
      "price": 49000,
      "rating": 4.5,
      "reviewCount": 2
    }
  ],
  "page": { "current": 1, "size": 12, "total": 1, "totalPages": 1 }
}
```

### 상세

| viewerState | 조건 | 표시용 action | 추가 필드 |
|---|---|---|---|
| guest | 인증 헤더 없음 | purchase | price |
| member | JWT 검증 후 해당 수강 행 없음 | purchase | price |
| enrolled | JWT 검증 후 enrollments(user_id,Lid) 존재 | continue | done, position(값이 있으면), learnUrl, materialUrl, assignmentUrl. price 생략 |

응답은 기존 명세대로 목록은 `lid`, 상세는 `lectureId`를 사용한다. 이 둘을 임의로 통일하지 않았다. API 이름과 DB의 `Lid`, `Lprice`, `user_id`는 DTO에서 매핑한다.

상세에는 name/headline/summary/category/level/picture/preview/intro, instructor, otherLectures, rating/reviewCount, viewerState/action이 들어간다. nullable 상세 필드는 생략된다. intro는 현재 summary를 줄바꿈으로 나눈 배열이며, 별도 소개 항목 저장 구조는 없다. otherLectures는 같은 강사의 다른 공개 강의 최대 5건이다.

`learnUrl` 등은 다음 기능의 API 주소 계약일 뿐, 이번 구현에서 영상·자료·과제 API가 실행된다는 뜻은 아니다. 실제 영상·자료 저장 경로는 상세 조회 SQL에서도 읽지 않는다. 비밀번호·JWT·정산 계좌도 반환하지 않는다.

### 오류

```json
{"code":"INVALID_SORT","message":"정렬 조건이 올바르지 않습니다."}
```

| HTTP | code | 조건 |
|---|---|---|
| 400 | INVALID_PAGE | 페이지/크기 범위 오류·offset overflow |
| 400 | INVALID_SORT | 미지원 정렬 |
| 400 | INVALID_INPUT | 숫자 자리의 문자, 0 이하 강의 번호 |
| 404 | LECTURE_NOT_FOUND | 강의 없음·비공개 |
| 401 | UNAUTHORIZED | 상세 요청의 인증 헤더 형식 오류, 검증기에서 거부한 토큰 |
| 503 | AUTH_NOT_CONFIGURED | 상세 요청에 토큰이 있으나 JWT 검증기 미연결 |

목록은 인증 정보가 필요 없는 공개 API라 토큰을 처리하지 않는다.

## 최신 명세와 확인할 차이

근거: [목록 명세](https://app.notion.com/p/456c85bae88482f7a51f01d77b7c167a), [상세 명세](https://app.notion.com/p/076c85bae88482cea9ef0195b915f001).

- 상세 명세의 세션 쿠키 안내는 JWT 전환 결정 이전 내용이다. Authorization Bearer를 받는 연결점을 만들었다.
- 상세 상태표는 400, 예시는 404로 충돌한다. 없는 자원은 기존 구현·예시를 따라 404로 구현했다.
- 목록 sort의 오탈자는 위 별칭으로 수용했다. category 예시와 최신 DB의 분류명은 다르므로 프론트는 실제 DB 값을 보내야 한다.
- size는 기존 코드에 있던 선택 query를 유지했다. 노션 query 표에는 아직 없다.
- notices 테이블이 없어 `noticeCount`를 0이라고 만들어 반환하지 않는다. 해당 필드를 생략한다.
- catchphrase는 저장 컬럼이 없어 현재 instructor 안에서 null이다. 프론트 표시 여부를 확인해야 한다.
- 최신 DBML이 실제 Oracle에 적용되었는지 확인이 필요하다. `user_id`, headline, summary, preview, enrollments.position 등을 사용한다.

## 코드가 이어지는 과정

예를 들어 프론트가 `GET /lectures?sort=price_asc`를 보내면 다음 순서로 처리한다.

1. `student/lecture/LectureController.java`: 주소와 GET 요청을 받고 query 값을 Java 인자로 바꾼다.
2. `student/lecture/LectureService.java`: page·size를 검사하고 `price_asc`를 정해진 SQL 정렬로 바꾼다. 응답에 필요한 형태를 결정한다.
3. `student/lecture/LectureRepository.java`: JdbcTemplate로 SQL을 실행한다. 검색어는 `?`에 바인딩하고 공개 강의와 리뷰 집계만 조회한다.
4. 조회 결과 객체 `LectureCard`를 `dto/LectureListItemResponse`로 바꾼다. DB 전체 행을 그대로 노출하지 않는다.
5. `LectureListResponse`를 반환하면 Spring이 JSON으로 직렬화해 프론트에 보낸다. 프론트는 lectures 배열로 카드를 그리고 page로 페이지 버튼을 그린다.

Controller/Service/Repository는 서로 다른 역할이지만 폴더를 따로 나누지는 않는다. 최신 개발 규칙대로 `student/lecture/`에 함께 있고 DTO만 `dto/`에 둔다. 공개 목록도 수강생용 강의 탐색 기능이라 이 위치에 둔다. 폴더명이 접근 권한을 정하는 것은 아니다.

공통 오류는 `global/exception/`, 인증 연결은 `global/auth/`, 인자 해석기 등록은 `global/config/WebMvcConfig.java`다. 테스트는 `src/test/java/com/example/sesacedu/student/lecture/`에 있다.

## JWT 담당 코드 연결

10/12 확정: 토큰 검증은 다른 담당자가 global에 구현한다. 강의 기능에서 별도 JWT 검증기를 만들지 않는다. AccessTokenVerifier는 임시 연결 경계이며 팀 코드가 공유되면 해당 인터페이스·사용자 정보 전달 방식에 맞춰 연결하거나 대체한다.

`AccessTokenVerifier`를 구현한 Spring 빈이 검증된 `CurrentUser`를 반환하도록 연결한다. 서명·허용 알고리즘·만료·발급자/대상 등 팀 JWT 계약, 계정 제한 및 폐기 정책은 해당 구현체가 확인해야 한다. 사용자 ID를 query나 미검증 payload에서 받지 않는다.

현재 검증기 구현체는 없다. `users.token` 문자열 일치만 확인하던 초안은 만료를 검증하지 못해 인증 경로에서 제거했다. 토큰을 제시했는데 검증기가 없으면 503을 반환한다. 단위·HTTP 테스트에서만 가짜 검증기를 사용하며 배포 코드에는 우회 검증기가 없다.

## 실행·검증

`.env` 에 `DB_URL`, `DB_USER`, `DB_PASSWORD`(RDS), `JWT_SECRET` 을 둔다. `.env` 는 git 에 올리지 않는다. JDK 11 에서 실행한다.

```powershell
.\gradlew.bat bootRun   # http://localhost:8080, Swagger 는 /swagger
.\gradlew.bat test      # 스프링 설정 테스트는 같은 RDS 에 붙는다
```

테스트 범위는 서비스 분기, MockMvc 의 요청·응답/권한 경계, 실제 Spring 설정의 빈 등록이다. SQL 은 RDS 에서 직접 호출해 확인한다.
