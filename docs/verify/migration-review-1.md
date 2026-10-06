# 머지 전 검증 — 문항 검색 이관

- 검증 대상: `git diff upstream/main...HEAD` (커밋 3개: `b6c972c`, `9dc7303`, `d4d5cdc`, 파일 21개)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 체크리스트: `templates/approval-checklist.md` v0

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `modern/api` `./gradlew test --rerun`: 104건 중 통과 104 · 실패 0 · 오류 0 · 건너뜀 0. `characterization` `npm test`(레거시): 36건 통과. `TARGET_BASE_URL=http://localhost:8080 npm test`(새 API): 35건 통과 · 1건 건너뜀 · 실패 0. 건너뛴 1건은 기존 예시 `characterization/tests/example-units.test.js:21` 의 `skipIf(legacyOnly)` 이고 이번 변경이 아니다. `item-bank` 테스트는 건너뛰기 없이 전부 통과했다. |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 · SQL 문자열 이어 붙이기 · 데이터 삭제 코드가 없다. `ItemRepository.java` 의 JPQL 은 `:param` 바인딩만 쓴다. |
| 요청 범위 이탈 없음 | 미충족 | 요청은 "엔드포인트 1개 이관"이다. 이관 코드는 `d4d5cdc` 의 `modern/api` 파일 11개(소스 7 · 테스트 4)로 설명된다. 그러나 범위에 요청으로 설명되지 않는 파일이 함께 들어 있다(이슈 #1). |
| 컨벤션 준수 | 충족 | 위반 없음(이슈 #2 · #3 은 제안). 아래 "컨벤션 대조"를 참고한다. |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `CLAUDE.md:1`, `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `.claude/skills/impact/SKILL.md:1` | 요청은 엔드포인트 1개 이관인데 변경 파일에 팀 지침 · Skill 정의(커밋 `9dc7303`)가 포함된다. 요청 문장으로 설명되지 않는다. `docs/item-bank/*` 4개(`9dc7303`)와 `characterization/` 베이스라인(`b6c972c`)은 이관의 선행 산출물이라 범위 이탈로 세지 않았다. | 이 파일들이 이전 작업의 산출물이면 검증 범위를 `d4d5cdc^..d4d5cdc` 로 좁혀 다시 검증한다. 같은 PR 에 넣을 거라면 요청 문장에 포함시킨다. |
| 2 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRowResponse.java:22` | `item.getTags()` 를 행마다 지연 로딩한다. 한 페이지(20건)마다 태그 조회가 최대 20번 더 나간다. `ItemRepository.java` 의 주석이 이 선택을 밝혀 두었고 동작에는 영향이 없다. | 이관이 끝난 뒤 `@BatchSize` 나 태그 id 일괄 조회로 줄인다. 이관 중에는 바꾸지 않는다. |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchController.java:25` | 파라미터를 `MultiValueMap` 으로 받고 `@Valid` 가 없다. 레거시가 어떤 입력도 막지 않아서 의도된 것이며 `ItemSearchRequest.java:10` 에 이유가 적혀 있다. | 조치 없음. 다만 `CLAUDE.md` 의 `@Valid` 규칙에 대한 예외로 한 줄 남겨 두면 다음 리뷰에서 같은 질문이 나오지 않는다. |

## 컨벤션 대조

- 계층: Controller → Service → Repository 만 호출한다(`ItemSearchController.java:19-26`, `ItemSearchService.java:58-72`). Controller 에 Repository · 비즈니스 판단이 없다.
- DTO: Controller 파라미터 · 반환 타입에 `@Entity` 가 없다(`ItemSearchController.java:25`). 응답은 `ItemSearchResponse` · `ItemSearchRowResponse` record 이다.
- `@Transactional(readOnly = true)` 가 Service 에 있다(`ItemSearchService.java:20`).
- 현재 시각 호출 · `System.out` · `printStackTrace` · 빈 `catch` · 와일드카드 import 가 새 코드에 없다(grep 확인).
- 120자를 넘는 줄이 없다(awk 확인).
- 테스트: 새 Controller · Service · Repository 쿼리에 `@WebMvcTest` · `@ExtendWith(MockitoExtension.class)` · `@DataJpaTest` 가 각각 있고, 모든 테스트에 `@DisplayName` 이 있다(`@Test` 29개 · `@DisplayName` 29개).
- 의존성 · DB 스키마 · `application.yml` 변경 없음. `modern/web` · `legacy` · `characterization/__snapshots__` 수정 없음. `git diff b6c972c d4d5cdc -- characterization` 이 비어 있다.

## 확인 필요

- `docs/item-bank/BUSINESS-RULES.md` 에 "이관 회고" 6줄이 `d4d5cdc` 에서 추가됐다. 요청에는 문서 수정이 없지만 이관의 부산물로 보인다. 요청에 포함된 것인지 확인이 필요하다.
- 새 API 대상 `characterization` 테스트는 `localhost:8080` 에 떠 있던 서버를 대상으로 돌렸다. 그 서버가 현재 HEAD 로 기동된 것인지는 확인하지 못했다. `/api/items/search` 가 응답하는 것만 확인했다.
- 레거시 동작이 의심스러운 부분의 목록은 `docs/item-bank/BUSINESS-RULES.md` 에 있고, 이번 검증에서 다시 판단하지 않았다.
- 작업 트리의 `.claude/skills/verify/` 는 추적되지 않는 파일이라 diff 에 없다. 검증 대상에서 제외했다.
