---
name: convention-check
description: 변경된 코드(또는 인자로 받은 경로)가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항 중 코드로 판정 가능한 8개 항목을 지키는지 점검하고, 위반을 파일:줄번호와 함께 표로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 데 있는지 봐 줘" 같은 요청에 쓴다. 코드는 고치지 않는다.
argument-hint: "[점검할 파일 또는 디렉터리 경로 …]"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

## 원칙
- 코드를 직접 고치지 않는다. 점검 결과와 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기
- 인자 `$ARGUMENTS` 가 있으면 그 경로만 본다. 디렉터리면 Glob 으로 아래 파일을 모은다.
- 인자가 없으면 변경 파일을 본다.
  - `git status --porcelain --untracked-files=all` 로 수정 · 추가 · 아직 add 하지 않은 새 파일(`??`)을 모은다. 삭제된 파일은 뺀다.
  - `git diff HEAD` 로 바뀐 줄을 확인한다. 새 파일은 파일 전체가 대상이다.
  - 변경 파일이 없으면 "점검할 변경 없음"이라고만 답하고 끝낸다.
- `modern/api/**/*.java` 와 `modern/web/src/**/*.{ts,tsx}` 만 점검한다. `legacy/` · 빌드 산출물 · 그 밖의 파일은 "대상 아님"으로 센다.
- 변경 파일이면 바뀐 줄에서 생긴 위반만 지적한다. 기존 줄의 위반은 요약에 "기존 위반 N건"으로만 적는다.

## 2. 점검 항목
| # | 규칙 | 대상 | 판정 방법 |
|---|---|---|---|
| 1 | Controller 는 Repository 를 주입받지 않고 SQL · JPQL 문자열을 두지 않는다 | `*Controller.java` | 필드 · 생성자 파라미터 타입이 `*Repository` 이거나, 문자열 리터럴에 `SELECT` · `FROM` · `UPDATE` · `DELETE` 가 있으면 위반 |
| 2 | Controller 메서드 파라미터 · 반환 타입에 `@Entity` 클래스가 나오지 않는다 | `*Controller.java` | 시그니처(제네릭 안 포함)에 `Item` · `Unit` · `Tag` · `Assignment` · `ClassRoom` · `Distribution` · `Submission` 이 있으면 위반 |
| 3 | Controller 안에서 try-catch 로 상태 코드를 만들지 않는다 | `*Controller.java` | `catch` 블록 안에서 `ResponseEntity` 로 상태 코드를 정해 반환하면 위반 |
| 4 | 빈 `catch`, 로그만 남기고 끝나는 `catch` 가 없다 | `.java` | `catch` 본문이 비었거나 `throw` 없이 로그 호출만 있으면 위반 |
| 5 | `System.out` · `System.err` · `printStackTrace()` 를 쓰지 않는다 | `.java` | 해당 호출이 있으면 위반 |
| 6 | `Instant.now()` · `LocalDateTime.now()` 를 인자 없이 부르지 않는다 | `.java` | 인자 없는 호출이 있으면 위반. `ErrorResponse` 의 `timestamp` 는 예외 |
| 7 | 와일드카드 import 가 없다 | `.java` | `import …*;` (static 포함) 가 있으면 위반 |
| 8 | 컴포넌트 · 훅에서 `fetch` 를 직접 부르지 않는다 | `modern/web/src` | `src/api/client.ts` · `*.test.ts(x)` · `src/test/` 밖에서 `fetch(` 를 부르면 위반 |

- Grep 으로 후보 줄을 찾고, Read 로 앞뒤 문맥을 읽은 뒤 판정한다. 주석 · 문자열 안의 일치는 위반이 아니다.
- 항목 1~3 의 "Controller" 는 `@RestController` · `@Controller` 가 붙은 클래스다. 파일 이름만으로 정하지 않는다.
- 문맥이 잘려 판정할 수 없으면 위반으로 세지 않고 "확인 필요"로 적는다.

## 3. 출력 형식 (순서 고정)

### 판정 요약
- 점검 파일 N개 (대상 아님 M개), 위반 X건, 확인 필요 Y건
- 항목별 결과를 한 줄씩 적는다: `1. 통과` / `2. 위반 2건` / `8. 해당 없음(web 변경 없음)` / `4. 확인 필요 — 이유`

### 위반 목록
| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/src/main/java/com/example/item/ItemController.java:42` | 2. Controller 반환 타입에 엔티티 | `ItemResponse.from(item)` 으로 변환해 반환 |

- 위반이 없으면 표 대신 "위반 없음" 한 줄만 적는다.
- 파일 경로는 저장소 루트 기준 상대 경로로 적는다.
