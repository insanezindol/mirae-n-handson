# 머지 전 검증 2차 — 문항 검색 이관

- 검증 대상: `git diff upstream/main...HEAD` (커밋 4개: `b6c972c`, `9dc7303`, `d4d5cdc`, `b7f306e`, 파일 23개)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 체크리스트: `templates/approval-checklist.md` v0
- 1차 리뷰: `docs/verify/migration-review-1.md`. 1차 이후 `b7f306e` 가 `Item.java` · `ItemRepository.java` 주석 · Repository 테스트를 바꿨다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `modern/api` `./gradlew test --rerun`: 105건 중 통과 105 · 실패 0 · 오류 0 · 건너뜀 0. `characterization` `npm test`(레거시): 36건 통과. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API): 35건 통과 · 1건 건너뜀 · 실패 0. 건너뛴 1건은 기존 `example-units.test.js` 의 `skipIf(legacyOnly)` 이고 이번 변경이 아니다. 새 테스트 파일에 `@Disabled` · `skip` 없음(grep). `git diff b6c972c HEAD -- characterization` 이 비어 있다. 단, 새 API 대상 결과는 서버가 현재 HEAD 인지 확인하지 못했다(확인 필요 1). |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 · SQL 문자열 이어 붙이기 · 데이터 삭제 코드가 없다. JPQL 은 `:param` 바인딩만 쓴다(`ItemRepository.java:30-37`). `"select i from Item i join fetch i.unit u " + SEARCH_CONDITION`(`:46`)은 상수 조각의 연결이며 사용자 입력이 들어가지 않는다. |
| 요청 범위 이탈 없음 | 미충족 | 요청은 "엔드포인트 1개 이관"이다. 이 범위에는 이관과 무관한 팀 지침 · Skill 파일이 들어 있고(이슈 #1), 공유 엔티티 `Item.java` 를 이관 코드 외의 목적으로 바꾼 변경이 있다(이슈 #2). |
| 컨벤션 준수 | 충족 | 위반 없음(아래 "컨벤션 대조"). 이슈 #3 · #4 는 제안. |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `.claude/skills/impact/SKILL.md:1`, `docs/verify/migration-review-1.md:1` | 요청 문장("엔드포인트 1개 이관")으로 설명되지 않는 팀 지침 · Skill 정의 · 이전 리뷰 문서가 diff 에 있다. 1차 리뷰의 #1 과 같은 문제가 해소되지 않았다. 이 파일들은 `9dc7303` · `b7f306e` 등 이전 커밋에서 왔다. `docs/item-bank/*` 와 `characterization/` 은 이관의 선행 산출물이라 이탈로 세지 않았다. | 이전 작업의 산출물이면 검증 범위를 이관 커밋(`d4d5cdc`, `b7f306e`)으로 좁혀 다시 검증한다. 같은 PR 에 넣을 거라면 요청 문장에 포함시킨다. |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/Item.java:27`, `:64` | `b7f306e` 가 공유 엔티티 `Item.tags` 에 `@BatchSize(size = TAG_BATCH_SIZE)` 를 붙였다. 1차 리뷰 #2(제안)는 "이관 중에는 바꾸지 않는다"였고, 이 변경은 검색뿐 아니라 `GET /api/items/{id}` · `GET /api/units/{code}/items` 의 태그 로딩 방식도 바꾼다. 결과는 같지만 해당 경로의 테스트가 이 변경을 직접 확인하지 않는다(`@BatchSize` · `TAG_BATCH_SIZE` 를 참조하는 테스트 없음, grep). 이관 요청에 성능 개선은 없었다. | 이관과 분리해 별도 변경으로 내거나, 요청에 포함됐다면 그 근거를 PR 설명에 적는다. 남긴다면 검색 외 경로의 태그 순서(`@OrderBy("id ASC")`)가 유지되는 테스트를 추가한다. |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/Item.java:27` | `TAG_BATCH_SIZE = 20` 이 `ItemSearchService.PAGE_SIZE`(`ItemSearchService.java:156`)와 값만 같고 서로 참조하지 않는다. 주석으로만 연결돼 한쪽이 바뀌면 어긋난다. | 어긋나도 동작은 같고 성능만 달라지므로 조치는 선택. 이슈 #2 를 정리할 때 함께 본다. |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchController.java:25` | 파라미터를 `MultiValueMap` 으로 받고 `@Valid` 가 없다. 레거시가 어떤 입력도 막지 않아서 의도된 것이며 `ItemSearchRequest.java:38` 에 이유가 있다. `CLAUDE.md` 의 `@Valid` 예외 문구와도 맞다. | 조치 없음. |

## 컨벤션 대조

- 계층: `ItemSearchController` 는 Service 만 주입받고 본문은 `ItemSearchRequest.from` → `search` 호출 → 반환뿐이다(`ItemSearchController.java:19-27`). Controller 에 Repository · 비즈니스 판단이 없다.
- DTO: Controller 반환 타입이 `ItemSearchResponse` 이고 `@Entity` 가 나오지 않는다. 엔티티 → DTO 변환은 `ItemSearchRowResponse.from`(`:18`).
- `@Transactional(readOnly = true)` 가 Service 에 있다(`ItemSearchService.java:146`). Service 는 같은 도메인의 `ItemRepository` 만 주입받는다(`:182`).
- Repository 에는 `@Query` 만 있고 `default` 메서드가 없다(`ItemRepository.java:44-56`).
- 레거시와의 동작 대조(`legacy/item-bank-php/search.php`): 키워드 trim · 100자 · 제목 OR 지문 LIKE(`:85-98` ↔ `ItemSearchService.java:212-215`), 난이도 빈 값 `level < 5` · 1~5 일치 · 그 밖은 `(int)` 변환(`:214-229` ↔ `:218-232`), 태그 50자 · 이름 정확 일치 EXISTS(`:235-247` ↔ `:235-237`), 정렬 5종과 2차 키(`:266-326` ↔ `:243-265`), 페이지 보정(`:336-348` ↔ `:271-281`). 공개 문항 조건은 뷰 `WHERE i.status = 'A'`(`db/mariadb/init/01-schema.sql:69`) ↔ `ItemStatus.ACTIVE`(`ItemSearchService.java:192`). 불일치 없음.
- 로그는 SLF4J `debug` 한 줄이며 학생 식별자 · 이메일 · 토큰이 없다(`ItemSearchService.java:204`). `System.out` · `printStackTrace` · 빈 `catch` · 와일드카드 import 가 `modern/api/src` 에 없다(grep). `Instant.now()` · `LocalDateTime.now()` 인자 없는 호출은 기존 `ErrorResponse.java:9` 뿐이고 CLAUDE.md 가 허용한 예외다.
- 120자 초과 줄이 새 Java 파일에 없다(awk).
- 테스트: Controller `@WebMvcTest`, Service `@ExtendWith(MockitoExtension.class)`, Repository `@DataJpaTest`. `@Test` 30개(Controller 5 · Repository 7 · Service 14 · PhpStrings 4) 대비 `@DisplayName` 30개.
- 의존성 · DB 스키마 · `application.yml` 변경 없음(`git diff --stat`에 `build.gradle` · `src/main/resources` 없음). `modern/web` · `legacy` · `characterization/__snapshots__` 수정 없음.

## 확인 필요

1. 새 API 대상 `characterization` 테스트는 `localhost:8080` 에 이미 떠 있던 서버를 대상으로 돌렸다. 프로세스 시작 시각(12:23 · 12:41 · 12:48)과 마지막 커밋 시각(12:49)을 보면 현재 HEAD 로 기동된 서버인지 알 수 없다. `/api/items/search` 가 200 을 돌려주는 것만 확인했다. 현재 HEAD 로 `bootRun` 을 다시 띄운 뒤 같은 명령을 돌려 주기를 요청한다.
2. `modern/api` 테스트는 H2(MariaDB 모드)다. `ItemRepository.java` 주석이 "단원 코드 · 태그 이름 비교와 정렬은 DB 콜레이션을 따른다"고 밝혔는데, 실제 MariaDB 콜레이션 동작은 1번의 characterization 이 HEAD 서버를 대상으로 통과해야 확인된다.
3. 작업 트리에서 `CLAUDE.md` 가 수정된 상태이고 `.claude/skills/verify/` 는 추적되지 않는다. diff 에 없어 검증 대상에서 제외했다. 이슈 #1 의 `CLAUDE.md` 는 커밋된 버전 기준이다.
4. `docs/item-bank/BUSINESS-RULES.md` 의 "이관 회고"(`:155`)와 규칙 비고 6줄은 이관의 부산물로 보이나 요청에 포함됐는지는 확인하지 못했다.
5. 레거시 의심 동작의 판단은 이번 검증 범위 밖이며 다시 판단하지 않았다.
