# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- 답변과 문서(주석 · 커밋 메시지 포함)는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이다. 허락 없이 `legacy/` 아래 파일을 수정하지 않는다.

이 파일의 규칙은 `modern/api`(Spring Boot 3.3 · Java 21 · Gradle) 와 `modern/web`(React 18 · TypeScript · Vite) 에 적용한다.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — 테스트는 H2(test 프로필)로 돌아서 DB 컨테이너 없이 통과한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'   # 클래스 하나
cd modern/api && ./gradlew build
cd modern/api && ./gradlew bootRun     # :8080. 먼저 저장소 루트에서 docker compose --profile modern up -d

# modern/web — 의존성은 npm install 이 아니라 npm ci 로 설치한다(package-lock.json 을 바꾸지 않는다)
cd modern/web && npm ci
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx            # 파일 하나
cd modern/web && npm run build
cd modern/web && npm run dev           # :5173
```

- 코드를 바꾼 작업을 끝낼 때는 바꾼 쪽의 테스트(`./gradlew test` 또는 `npm run lint && npm run typecheck && npm test`)를 실행하고, 통과 · 실패 수를 답변에 적는다. 실행하지 않았으면 "실행하지 않음"이라고 적는다.

## 2. 코딩 컨벤션

### 계층 (modern/api)
- 호출 방향은 Controller → Service → Repository 다. 역방향 호출은 없다.
- Controller 는 Service 만 생성자로 주입받는다. Controller 에 Repository 주입, SQL · JPQL 문자열, `if`/`for` 로 하는 비즈니스 판단(상태 확인 · 계산 · 필터링)을 두지 않는다. Controller 메서드 본문은 요청 값 꺼내기 → Service 호출 → 반환만 한다.
- Repository 에는 Spring Data 쿼리 메서드와 `@Query` 만 둔다. `default` 메서드로 비즈니스 로직을 넣지 않는다.
- 비즈니스 로직과 `@Transactional` 은 Service 에 둔다. 조회 전용 Service 는 `@Transactional(readOnly = true)` 를 붙인다.
- Service 는 다른 도메인 패키지의 Repository 를 주입받지 않는다. 다른 도메인 데이터는 그 도메인의 Service 를 통한다.

### 요청 · 응답 DTO
- Controller 메서드의 파라미터 · 반환 타입(`List<>` 등 제네릭 안 포함)에 `@Entity` 클래스(`Item`, `Unit`, `Tag`, `Assignment`, `ClassRoom`, `Distribution`, `Submission`)가 나오지 않는다.
- 요청 본문은 `<동작>Request` record, 응답은 `<대상>Response` record 로 만든다(기존 예: `RedistributeRequest`, `ItemResponse`, `UnitResponse`). 엔티티 → DTO 변환은 DTO 의 정적 메서드 `from(엔티티)` 또는 Service 에서 한다.
- 요청 DTO 의 필드 제약은 Bean Validation 애너테이션(`@Size` 등)으로 적고, Controller 파라미터에 `@Valid` 를 붙인다.

### 응답 구조 · HTTP 상태 코드
- 성공 응답: DTO 를 본문에 그대로 반환한다. `{data: …}` 같은 감싸는 객체를 새로 만들지 않는다.
- 실패 응답: 모든 오류 본문은 `common/ErrorResponse`(`status, error, message, path, timestamp`) 모양이다. 다른 오류 본문 모양을 만들지 않는다.
- 예외 → 상태 코드 변환은 `common/GlobalExceptionHandler` 에서만 한다. Controller 안에서 try-catch 로 `ResponseEntity` 상태 코드를 만들지 않는다.
- 매핑은 아래 표를 따른다. 새 예외 종류가 필요하면 `GlobalExceptionHandler` 에 핸들러를 추가한다.

| 예외 | 상태 |
|---|---|
| `NotFoundException`, 없는 경로 | 404 |
| `MethodArgumentNotValidException`, `ConstraintViolationException`, `MethodArgumentTypeMismatchException`, `IllegalArgumentException` | 400 |
| `IllegalStateException` (현재 상태에서 할 수 없는 요청) | 409 |
| 그 밖의 `Exception` | 500 |

### 예외 · 로그
- 빈 `catch` 블록, 그리고 로그만 남기고 끝나는 `catch` 블록을 두지 않는다. `catch` 에서는 다시 던지거나 도메인 예외로 바꿔 던진다.
- 로그는 SLF4J `Logger`(`LoggerFactory.getLogger(현재클래스.class)`) 로만 남긴다. `System.out` · `System.err` · `printStackTrace()` 를 쓰지 않는다.
- 로그 인자에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- 로그 레벨: 정상 흐름 · 404 · 400 은 `INFO` 이하, 409 등 복구 가능한 실패는 `WARN`, 500 은 `ERROR`(예외 객체를 마지막 인자로).

### 테스트
- 새 public Service 메서드 · 새 엔드포인트마다 테스트를 1개 이상 추가한다. 동작을 바꾸면 그 동작의 테스트를 추가하거나 고친다.
- Controller 는 `@WebMvcTest`, Service 는 `@ExtendWith(MockitoExtension.class)` 단위 테스트, Repository 쿼리는 `@DataJpaTest`(H2)로 테스트한다. 테스트가 실제 MariaDB 에 붙지 않는다.
- 테스트 메서드 이름은 확인하는 동작을 camelCase 로(예: `rejectsOtherOrigins`), `@DisplayName` 에 한국어 설명을 붙인다.
- web 테스트는 컴포넌트 · 훅 옆에 `*.test.ts(x)` 로 두고, fetch 는 `src/test/mockFetch.ts` 로 대체한다.

### 이름 · 형식
- Java: 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 약어도 첫 글자만 대문자(`ItemDto`).
- 비교 · 계산에 쓰는 숫자 리터럴(0, 1 제외)은 이름 붙인 상수로 뺀다(예: `MAX_LEVEL = 5`).
- Java 들여쓰기 4칸, 한 줄 120자 이내, 와일드카드 import(`import x.*;`) 없음.
- TypeScript: `any` 없음, 미사용 변수는 `_` 접두어만 허용, 타입만 가져올 때 `import type`. (`npm run lint` 가 검사한다)
- web 의 HTTP 호출은 모두 `src/api/client.ts` 의 `getJson` 을 거친다. 컴포넌트 · 훅에서 `fetch` 를 직접 부르지 않는다.

## 3. 금지 사항

아래는 하기 전에 먼저 사람에게 묻는다.
- 의존성 추가 · 변경: `modern/api/build.gradle` 의 `dependencies`, `modern/web/package.json` 의 `dependencies` · `devDependencies`. 물을 때 이유와 대안을 함께 적는다.
- DB 스키마 변경: 테이블 · 컬럼 추가 · 삭제, 인덱스 변경, 마이그레이션 파일 추가, 시드 데이터 변경.
- `characterization/` 의 테스트를 실패하게 만드는 동작 변경.

아래는 하지 않는다.
- `modern/api/src/main/resources/application.yml` 의 DB 접속 정보 · 커넥션 풀(Hikari) 설정 변경. 풀 크기 5 · 대기 3초는 운영 값과 같게 맞춘 값이다.
- 실제 비밀값(운영 계정 · 토큰 · 키)을 코드 · 설정 파일에 리터럴로 넣기. 실습용 더미 `app-pass` 는 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정 만들기.
- `@Transactional` 메서드 안에서 외부 HTTP 호출.
- `characterization/__snapshots__/` 의 스냅샷을 손으로 고치거나, 테스트를 통과시키려고 테스트 · 스냅샷을 고치기.
- 요청에 없는 파일의 수정(포맷팅 · import 정리 포함). 고쳤다면 파일과 이유를 답변에 적는다.
- README 의 "이 저장소에 넣지 않은 것" 표에 있는 파일(`/api/items/search` 엔드포인트, `.claude/`, `hooks/`, `.mcp.json` 등)을 요청 없이 만들기. 이 파일들은 참가자가 수업 중에 만든다.

## 4. 아키텍처 안내

```
modern/api/src/main/java/com/example/
├── item/          문항 · 단원 · 태그 (Item*, Unit*, Tag*)
├── assignment/    과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*, ClassRoom, Submission)
├── common/        GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig, RootController
└── config/        WebConfig (/api/** CORS)
modern/api/src/main/resources/application.yml       기본 프로필 = 로컬 MariaDB(localhost:3306/itembank)
modern/api/src/test/resources/application-test.yml  테스트 프로필 = H2 (MariaDB 모드)
modern/web/src/  api/(client.ts · items.ts · types.ts) → hooks/(useApiQuery 등) → components/
```

- 기존 엔드포인트: `GET /api/units`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`.
- 새 엔드포인트는 `/api/<도메인 복수형>` 아래에 두고, Controller · Service · Repository · 테스트를 같은 도메인 패키지에 만든다.
- 공개 문항만 노출한다: `item.status = 'A'`(`ItemStatus`). 삭제 여부도 상태 코드로 판단한다.
- 현재 시각은 `ClockConfig` 가 등록한 `Clock` 을 주입받아 쓴다. `Instant.now()` · `LocalDateTime.now()` 를 인자 없이 부르지 않는다(`ErrorResponse.timestamp` 는 예외).
- web 은 `VITE_API_BASE`(기본 `http://localhost:8080`)로 API 를 부른다. `VITE_API_BASE=""` 이면 Vite 프록시가 `/api` 를 8080 으로 넘긴다.
- 레거시 규칙을 옮길 때는 근거를 `파일:줄번호` 로 답변에 적는다(예: `legacy/item-bank-php/search.php:214`).
- 도메인 용어: 문항 `item` · 단원 `unit` · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. "거의 됐다"처럼 요약해서 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 사람에게 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.
