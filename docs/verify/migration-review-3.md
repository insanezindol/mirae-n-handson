# 머지 전 검증 3차 — 문항 검색 이관

- 검증 대상: `git diff upstream/main...HEAD -- modern characterization` (커밋 4개: `b6c972c`, `d4d5cdc`, `b7f306e`, `7404146`, 파일 17개, +2670 / -1)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 체크리스트: `templates/approval-checklist.md`
- 이전 리뷰: `docs/verify/migration-review-1.md`, `docs/verify/migration-review-2.md`
- 진행 방식: 체크리스트 대조는 reviewer Sub-agent, 테스트 추가 · 실행은 tester Sub-agent 가 했고, 아래 판정은 두 보고를 받은 메인 세션이 내렸다. 두 보고가 엇갈린 지점은 없다(reviewer 는 테스트를 실행하지 않아 기준 1을 "확인 필요"로 남겼고, tester 가 그 부분을 채웠다).
- 작업 트리 상태: 검증 시점에 커밋되지 않은 변경이 있다. 범위 diff 에는 나오지 않지만 테스트는 이 상태로 실행됐다.
  - `modern/api/src/main/java/com/example/item/ItemRepository.java` — 검색 LIKE 에 `escape '\'` 추가(이 검증 전에 리뷰 지적을 반영한 수정)
  - `modern/api/src/test/java/com/example/item/UnitServiceTest.java`, `ItemSearchRepositoryTest.java` (수정), `UnitControllerTest.java` (신규) — 이번 검증에서 tester 가 추가한 테스트 12개

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 미충족 | `modern/api` `./gradlew test`: 117건 통과 · 실패 0 · 건너뜀 0. `characterization` `npm test`(레거시): 36건 통과 · 실패 0. 새 API 대상 `TARGET_BASE_URL=… npm test` 는 이번 검증에서 실행하지 못했다(확인 필요 1). 실행하지 못한 명령이 있어 충족으로 표시하지 않는다. 또 새 엔드포인트의 테스트와 LIKE 수정의 테스트가 HEAD 에 없고 작업 트리에만 있다(이슈 #2). |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 · 데이터 삭제 코드 · 인증 변경이 없다. 입력은 모두 `@Param` 바인딩이고 `ItemRepository.java:50-51` 의 문자열 연결은 상수끼리다. 정렬은 `ItemSearchService.java:122-138` 의 고정 switch 로만 정해진다. |
| 요청 범위 이탈 없음 | 미충족 | 요청은 "엔드포인트 1개 이관"인데 이관과 무관한 새 엔드포인트 `GET /api/units/item-counts` 가 범위에 있다(이슈 #1). |
| 컨벤션 준수 | 미충족 | 이슈 #1 과 같은 원인이다(`CLAUDE.md` 금지 사항 "요청에 없는 파일의 수정"). 그 밖의 반려 항목(계층, 빈 catch, `System.out`, 통과용 테스트 수정)은 위반이 없다. 이슈 #4 · #5 는 제안. |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/UnitController.java:25-29`, `modern/api/src/main/java/com/example/item/UnitService.java:16-21`, `:32-43`, `modern/api/src/main/java/com/example/item/UnitItemCountResponse.java:1-9` | 커밋 `7404146`("review: vendor pr-1 적용")의 단원별 문항 수 엔드포인트다. `search.php` 이관에 필요한 줄이 없다. | 별도 PR 로 분리한다. 외주 PR 적용분까지 이번 머지 범위라면 요청 문장을 고쳐 다시 판정한다. |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/ItemRepository.java:36`, `modern/api/src/main/java/com/example/item/UnitService.java:36`, `modern/api/src/main/java/com/example/item/UnitController.java:27` | LIKE `escape '\'` 수정과 테스트 12개가 커밋되지 않았다. HEAD 의 `ItemRepository.java:31` 에는 escape 가 없어 이대로 머지하면 수정이 빠지고, `countActiveItemsByUnit` · `itemCounts` 는 HEAD 기준으로 테스트가 없다. 레거시는 escape 지정 없이 DB 기본값(백슬래시)을 쓴다(`legacy/item-bank-php/search.php:97-98`). | 수정과 테스트를 커밋한 뒤 그 범위로 다시 검증한다. Unit 쪽 테스트는 #1 을 분리하면 그 PR 로 옮긴다. |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/UnitService.java:37-39` | 단원마다 `countByUnitIdAndStatus` 를 호출해 단원 수만큼 쿼리가 나간다. | group by 집계 `@Query` 한 번으로 바꾼다(#1 을 분리한 PR 에서). |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemRepository.java:34` | Repository 인터페이스에 문자열 상수 `SEARCH_CONDITION` 이 있다. `CLAUDE.md` 는 "Repository 에는 Spring Data 쿼리 메서드와 `@Query` 만 둔다"고 한다. 조회 · 건수 쿼리의 조건을 맞추려는 목적이고 사용자 입력은 들어가지 않는다. | 유지한다면 예외로 둔다는 합의를 기록한다. |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/Item.java:28` | `TAG_BATCH_SIZE = 20` 과 `ItemSearchService.java:30` 의 `PAGE_SIZE = 20` 이 값만 같고 서로 참조하지 않는다. 어긋나면 결과는 같고 쿼리 수만 달라진다. | 조치는 선택. 한 상수를 참조하게 하거나 두 값이 같음을 테스트로 고정한다. |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRequest.java:53-55` | `q[a][b]=x` 같은 중첩 배열에서 레거시는 문자열 `"Array"` 로 검색하고(`legacy/item-bank-php/search.php:55`), modern 은 `"x"` 로 검색한다. | 실사용 가능성이 낮다. 의도된 차이로 기록할지 사람이 정한다. |
| 7 | 제안 | `characterization/tests/item-bank.test.js:117-119` | 동작 보존 테스트의 와일드카드 케이스가 `q=%` 하나뿐이라, #2 의 백슬래시 동작을 실제 MariaDB 에서 고정하지 못한다. | `q=\%` 케이스 추가는 새 스냅샷이 필요하므로 사람에게 먼저 묻는다. |

## 테스트 실행 기록 (tester)

| 명령 | 통과 | 실패 | 건너뜀 |
|---|---|---|---|
| `cd modern/api && ./gradlew test` | 117 | 0 | 0 |
| `cd characterization && npm test` (레거시 대상) | 36 | 0 | 0 |
| `cd characterization && TARGET_BASE_URL=http://localhost:18080 npm test` (새 API 대상) | 실행하지 않음 | | |

추가된 테스트(12개, 모두 통과):

- `UnitServiceTest`: `countActiveItemsByUnitMapsCountsInOrder`, `countActiveItemsByUnitIncludesUnitsWithoutItemsAsZero`, `countActiveItemsByUnitQueriesActiveStatusOnly`, `countActiveItemsByUnitEmpty`
- `UnitControllerTest`(신규): `itemCountsReturnsArray`, `itemCountsEmptyReturnsEmptyArray`, `listUnitsReturnsArray`
- `ItemSearchRepositoryTest`: `backslashPercentMatchesLiteralPercentOnly`, `backslashUnderscoreMatchesLiteralUnderscoreOnly`, `unescapedUnderscoreStaysWildcard`, `backslashPercentMatchesLiteralPercentInStem`, `doubleBackslashMatchesLiteralBackslashOnly`

`characterization/` 의 테스트 코드와 스냅샷은 `b6c972c` 이후 바뀌지 않았고 작업 트리에도 변경이 없다. `modern/web` 은 범위에 변경이 없어 실행하지 않았다.

## 확인 필요

1. 새 API 대상 characterization 을 이번 검증에서 실행하지 못했다. 8080 서버는 미커밋 수정 이전 코드이고, 현재 코드를 다른 포트로 띄우는 `./gradlew bootRun` 이 tester 에게 허용된 명령 밖이었다. 참고로 이 검증 전, `ItemRepository.java` 를 고친 직후 메인 세션이 같은 소스를 18080 포트로 띄워 실행한 결과는 35건 통과 · 1건 건너뜀 · 실패 0 이었다(건너뛴 1건은 기존 `example-units.test.js:21` 의 `skipIf(legacyOnly)`). 그 실행은 이번 검증의 tester 실행이 아니므로 기준 판정에는 쓰지 않았다.
2. 백슬래시 테스트 5개가 회귀를 실제로 잡는지 확인하지 못했다. 테스트는 H2 에서 돌고, 수정 전 코드로 실패하는지는 돌려 보지 않았다. 백슬래시를 일반 글자로 바꾸는 것은 Hibernate 의 MariaDB 방언 동작이라 H2 에서는 수정 전 코드로도 통과할 수 있다. 수정 후 MariaDB 로 나가는 SQL 이 `like ? escape '\\'` 인 것은 메인 세션이 SQL 로그로 확인했지만, 시드 데이터에 `%` · `_` · 백슬래시가 든 문항이 없어 결과 행의 차이는 보지 못했다.
3. 백슬래시 뒤에 `%` · `_` · `\` 가 아닌 글자가 오는 키워드(예: `\a`)는 어느 테스트에도 없다.
4. 레거시는 조회 중 예외가 나도 오류 문구를 HTTP 200 으로 낸다(`legacy/item-bank-php/search.php:726-729`). modern 은 `GlobalExceptionHandler` 를 거쳐 500 이 될 것으로 보이지만 실행으로 확인하지 않았다.
5. 레거시의 입력 경고 문구(`legacy/item-bank-php/search.php:88`, `:92`, `:95`, `:113`, `:225` 등)와 조건 요약이 `ItemSearchResponse` 에 없다. characterization 의 정규화(`characterization/lib/normalize.mjs:4`)가 `{status, rows, count, message}` 만 비교해 테스트로는 드러나지 않는다. 의도한 생략인지 요청자가 정해야 한다.
6. `q[]=a&q=b&q[]=c` 는 레거시가 `c`, modern 이 `b` 일 것으로 보인다. `ItemSearchRequest.java:39` 주석에 한계로 적혀 있지만 실제 파싱 순서는 확인하지 않았다.
7. 스냅샷이 레거시 실제 응답에서 생성됐는지는 git 이력으로 구분할 수 없다. 이관 커밋 전에 한 번 커밋되고 이후 바뀌지 않은 것만 확인했다.

## 의심 동작 (레거시 그대로 옮긴 것, 수정 대상 아님)

- `legacy/item-bank-php/search.php:213-216` — 주석은 "1~5 모두 포함"인데 조건은 `level < 5` 라 난이도를 비우면 5가 빠진다. modern 은 `ItemSearchService.java:103-106` 에서 그대로 옮겼다.
- `legacy/item-bank-php/search.php:223-229` — 1~5 형식이 아닌 난이도를 `(int)` 로 바꿔 비교한다. `level=05`, `level=5abc` 가 난이도 5 로 조회된다.
- `legacy/item-bank-php/search.php:94-98` — 키워드의 `%` · `_` 를 경고만 하고 와일드카드로 쓴다. `q=%` 는 전체와 일치한다.
- `legacy/item-bank-php/search.php:115-118` — "입력값 그대로 조회"라고 경고하지만 콜레이션(`db/mariadb/init/01-schema.sql:18`) 때문에 소문자 단원 코드도 일치한다.
- `legacy/item-bank-php/search.php:293-299` — 단원 정렬의 2차 키 `level DESC` 가 방향과 무관하게 고정이다.
- `legacy/item-bank-php/search.php:337-341` — page 만 trim 하지 않아 `" 2"` 는 1페이지다.
- `legacy/item-bank-php/search.php:345-349`, `:655-660` — 마지막 페이지를 넘으면 건수는 그대로인데 행도 안내 문구도 없다.

## 2차 리뷰와의 차이

- 2차의 이슈 #1(팀 지침 · Skill 파일)은 이번 범위를 `modern characterization` 으로 좁혀 해당하지 않는다.
- 2차의 이슈 #2(`Item.tags` 의 `@BatchSize`)는 이번에 이슈로 올리지 않았다. 검색은 페이징 때문에 태그를 fetch join 할 수 없어 이관에 필요한 변경이고, 다른 엔드포인트의 응답은 같다. 검색 쪽 묶음 로딩은 기존 `loadsTagsOfAPageInOneBatch` 가 확인한다.
- 새로 반려 사유가 된 것은 2차 이후 들어온 커밋 `7404146` 이다.
