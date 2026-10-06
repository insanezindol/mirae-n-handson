# RCA — b-deploy-5xx

대상 로그: `incident-logs/b-deploy-5xx/` (2026-09-17)

> **작성 범위**: 이 문서는 "1. 수집 범위", "2. 타임라인", "3. 가설과 검증"의 가설 표까지만 담은 중간본이다.
> 반증 확인(실행 기록) · 코드 대조 · 판정 · 원인 · 수정안 · 재발 방지 · 모니터링 · 요약은 아직 쓰지 않았다.
> 3절의 가설은 어느 것도 채택 · 기각하지 않은 상태다.

## 1. 수집 범위

이 절은 "어느 시간대의 로그를 볼 것인가"만 정한다. 원인 판단은 넣지 않았다.
모든 수치는 `wc` · `head` · `tail` · `grep` · `sed -n` · `awk` 집계로 얻었고, 로그 파일을 통째로 읽지 않았다(`deploy-history.md` 39줄만 전체를 읽었다).

### 1.1 파일별 기록 기간 · 시각 형식

| 파일 | 줄 수 | 기록 기간(원문 표기) | 기록 기간(KST) | 시각 형식 | 타임존 |
|---|---|---|---|---|---|
| `app.log` | 2,938 (시각이 붙은 줄 1,275 + 스택 트레이스 등 이어지는 줄 1,663) | 09:00:04.878 ~ 14:39:57.905 | 09:00:04 ~ 14:39:57 | `2026-09-17T09:00:04.878+09:00` (ISO-8601, 밀리초) | KST, 오프셋 `+09:00` 명시 (1,275줄 전부) |
| `nginx-access.log` | 20,365 | 09:00:00 ~ 14:39:58 | 09:00:00 ~ 14:39:58 | `[17/Sep/2026:09:00:00 +0900]` (초 단위) | KST, 오프셋 `+0900` 명시 (20,365줄 전부) |
| `deploy-history.md` | 39 | 배포 4건: 2026-09-01 ~ 2026-09-17 | 같음 | `2026-09-17 13:40:05` (초 단위, 오프셋 없음) | 값마다 오프셋은 없고, 문서 머리말에 "시각은 모두 KST" 라고 적혀 있다(`deploy-history.md:3`) |

타임존 판단 근거

- `deploy-history.md`: 시각 값 자체에는 오프셋이 없다. 머리말의 "KST" 선언을 로그와 맞춰 봤다. v1.4.2 의 배포 시작 13:40:05 ~ 완료 13:42:31(`deploy-history.md:7`) 사이에 `app.log` 의 종료 13:41:48.214(`app.log:1240`) ~ 기동 완료 13:42:00.304(`app.log:1264`)가 들어가고, 기동 줄에 버전 `v1.4.2` 가 찍힌다(`app.log:1245`). 그래서 KST 로 본다. 배포 파이프라인 서버의 시계가 API 서버와 맞는지는 이 로그로 확인할 수 없다.

KST 변환 규칙

| 파일 | 규칙 |
|---|---|
| `app.log` | 그대로 사용 |
| `nginx-access.log` | 그대로 사용 |
| `deploy-history.md` | 그대로 사용(머리말 선언 + 위 대조). 초 단위이고 다른 시스템의 시계이므로, 로그 줄과 초 단위 선후를 다툴 때는 "(선후 불확실)"로 적는다 |

시각 정밀도: `app.log` 는 밀리초, `nginx-access.log` 는 초다. 같은 초 안의 선후는 두 파일 사이에서 정할 수 없다.

### 1.2 분 단위 건수

세는 기준

| 파일 | 센 줄 |
|---|---|
| `app.log` | 로그 레벨 `WARN` · `ERROR` 줄(시각이 붙은 줄만, 스택 트레이스 줄 제외) |
| `nginx-access.log` | 상태 코드 5xx (이 파일에는 500 · 502 만 있다. 504 · 499 는 0건) |

전체 분포

| 파일 | 항목 | 건수 |
|---|---|---|
| `app.log` | `INFO` / `WARN` / `ERROR` | 1,194 / 35 / 46 |
| `nginx-access.log` | 200 / 400 / 404 / 409 / 500 / 502 | 19,278 / 29 / 969 / 35 / 46 / 8 |
| `nginx-access.log` | `GET` / `POST` | 20,213 / 152 |

평소 수준(09:00 ~ 13:39, 280분)

| 파일 | 평소 수준 |
|---|---|
| `nginx-access.log` 전체 요청 | 분당 평균 59.9건(최소 40 · 최대 82, 340분 전체 기준). 10분 단위로는 516 ~ 682건 |
| `nginx-access.log` 5xx | 280분 중 4개 분에만 있고 합계 7건: 09:52 3건, 10:31 1건, 11:17 1건, 11:18 2건. 전부 500, 502 는 0건. 나머지 276분은 0건 |
| `app.log` `ERROR` | 위 500 과 같은 분에 같은 수(7건). 나머지 분은 0건 |
| `app.log` `WARN` | 31건, 분당 0 ~ 2건. 메시지는 한 종류(`state conflict: 마감된 과제는 재배포할 수 없습니다`) |

평소 수준을 벗어난 구간(13:40 이후, 60분)

| 분 | nginx 500 | nginx 502 | app `ERROR` |
|---|---|---|---|
| 13:41 | 0 | 8 | 0 |
| 13:46 | 2 | 0 | 2 |
| 13:47 | 1 | 0 | 1 |
| 13:50 | 4 | 0 | 4 |
| 13:52 | 1 | 0 | 1 |
| 13:53 | 1 | 0 | 1 |
| 13:55 | 3 | 0 | 3 |
| 13:58 | 2 | 0 | 2 |
| 14:02 | 2 | 0 | 2 |
| 14:03 | 4 | 0 | 4 |
| 14:06 | 1 | 0 | 1 |
| 14:11 | 3 | 0 | 3 |
| 14:15 | 1 | 0 | 1 |
| 14:16 | 2 | 0 | 2 |
| 14:22 | 3 | 0 | 3 |
| 14:27 | 2 | 0 | 2 |
| 14:28 | 1 | 0 | 1 |
| 14:31 | 2 | 0 | 2 |
| 14:32 | 1 | 0 | 1 |
| 14:34 | 3 | 0 | 3 |
| 합계 | 39 | 8 | 39 |

- 500 은 13:46 이후 19개 분에 39건이다. 평소 280분에 7건(분당 0.025건) → 13:46 ~ 14:34 의 49분에 39건(분당 0.8건).
- 표에 없는 분은 5xx · `ERROR` 가 0건이다.
- 같은 60분의 전체 요청 수는 10분 단위 549 ~ 665건으로, 평소 범위(516 ~ 682) 안이다.
- `WARN` 은 13:40 이후 4건(13:59, 14:24, 14:30, 14:35)으로 평소와 같은 메시지다.

### 1.3 이상 구간 · 앞뒤 여유 · 줄 범위

| 구분 | 시각(KST) | 근거 |
|---|---|---|
| 이상 구간 ① | 13:41:51 ~ 13:41:59 | 502 8건(`nginx-access.log:16865` ~ `16872`) |
| 이상 구간 ② | 13:46:38 ~ 14:34:45 | 500 39건(`nginx-access.log:17155` ~ `20084`) |
| 수집 구간 | 13:30:00 ~ 14:39:58(파일 끝) | 앞 여유: 배포 시작(13:40:05) 10분 전부터. 뒤 여유: 마지막 500 뒤 5분 13초에서 파일이 끝나 더 붙일 수 없다 |
| 비교 구간 | 09:52, 10:31, 11:17 ~ 11:18 | 배포 전 500 7건. 같은 종류의 오류인지 비교하려고 포함 |

파일별 줄 범위

| 파일 | 수집 구간 | 비교 구간 |
|---|---|---|
| `app.log` | 1202 ~ 2938 | 194 ~ 300, 451 ~ 489, 655 ~ 771 |
| `nginx-access.log` | 16065 ~ 20365 | 3150 ~ 3174, 5563, 8442 ~ 8468 |
| `deploy-history.md` | 1 ~ 39(전체) | — |

**뒤 여유에 대한 주의**: 500 은 2 ~ 4건씩 묶여 나오고 묶음 사이 간격이 121 ~ 332초다. 마지막 500(14:34:45) 뒤 파일 끝(14:39:58)까지 313초뿐이라, 이 로그로는 500 이 멈췄는지 알 수 없다.

### 1.4 로그에 든 민감 값(값은 옮기지 않음)

| 파일 | 내용 | 위치 |
|---|---|---|
| `app.log` | 재배포 사유(`reason=`)에 학생 식별자가 찍힌다. 73줄 | 첫 줄 `app.log:28` |
| `nginx-access.log` | 쿼리 문자열 `student=` 에 학생 식별자가 든 요청 4,297줄 | 첫 줄 `nginx-access.log:11` |
| `deploy-history.md` | 배포 명령에 `Authorization` 토큰 값이 그대로 적혀 있다 | `deploy-history.md:20` |
| `deploy-history.md` | 배포자 · 승인자 이메일 | `deploy-history.md:7` ~ `10`, `21`, `28`, `34`, `39` |
| `deploy-history.md` | 배포 대상 · 대기 호스트의 IP | `deploy-history.md:3`, `20` |

## 2. 타임라인

- 로그와 배포 이력에 적힌 사실만 적었다. 원인 해석은 3절에 있다.
- 시각은 모두 KST. `app.log` 는 밀리초, `nginx-access.log` · `deploy-history.md` 는 초 단위다.
- 발췌는 원문에서 잘라 왔고 생략한 곳은 `…` 로 표시했다. IP · 학생 식별자 · 이메일 · 토큰은 마스킹 규칙에 따라 별칭으로 바꿨다.
- 별칭: 재배포 `POST` 를 보낸 클라이언트 IP 는 로그 전체에서 2개뿐이다. 요청이 많은 쪽을 `교사단말A`, 다른 쪽을 `교사단말B` 로 부른다. 배포 대상 호스트는 `API-1`, 대기 호스트는 `API-2` 다.

### 2.1 배포 전 (09:00 ~ 13:40:04)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 09:07:01.507 | `app.log:28` | 재배포 성공 로그의 첫 줄. 사유에 학생 식별자가 찍힌다 | `redistributed distribution 8 (assignment 6, class 2) reason=학생A 결석으로 재배포` |
| 09:15:53.787 | `app.log:59` | 학급 1 의 배포 43 재배포 성공(배포 전 첫 발생). 배포 전 학급 1 재배포 성공은 배포 42 가 5건, 배포 43 이 13건 = 18건 | `redistributed distribution 43 (assignment 6, class 1) reason=학생A 결석으로 재배포` |
| 09:52:17 | `nginx-access.log:3150` | 배포 41 재배포 요청이 500. `교사단말A` 가 보냄. 09:52 에 3건(`3150`, `3162`, `3174`), 11:17 ~ 11:18 에 3건(`8442`, `8466`, `8468`), 합계 6건 | `교사단말A - - [17/Sep/2026:09:52:17 +0900] "POST /api/distributions/41/redistribute HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions/41" …` |
| 09:52:17.335 | `app.log:194` ~ `195` | 위 요청의 `ERROR`. 예외는 `Assignment` 식별자 9 가 없다는 내용 | `unhandled exception on /api/distributions/41/redistribute` / `org.springframework.orm.jpa.JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist` |
| 09:52:17.335 | `app.log:204` ~ `206`, `214` | 위 스택 트레이스의 `com.example` 프레임. 배포 전 재배포 500 6건이 모두 같은 경로 | `…findWithDetailsById(Unknown Source)` / `at com.example.assignment.DistributionService.loadOrThrow(DistributionService.java:65)` / `at com.example.assignment.DistributionService.redistribute(DistributionService.java:51)` / `at com.example.assignment.DistributionController.redistribute(DistributionController.java:38)` |
| 10:31:05 | `nginx-access.log:5563` | 배포 41 단건 조회도 500. 1건 | `교사단말A - - [17/Sep/2026:10:31:05 +0900] "GET /api/distributions/41 HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions" …` |
| 10:31:05.909 | `app.log:451` ~ `452` | 위 요청의 `ERROR`. 예외 메시지는 09:52 와 같다(식별자 9). 프레임은 `loadOrThrow(…:65)` ← `getDistribution(…:32)` | `unhandled exception on /api/distributions/41` |
| 13:24:45.671 | `app.log:1191` | 학급 1 리포트 생성 로그의 마지막 줄. 로그 전체에서 17건이고 이 뒤로는 없다 | `built class report for class 1…` |

배포 전 재배포 `POST` 응답(전체 103건): 200 이 66건(배포 5 · 6 · 7 · 8 · 42 · 43 · 44), 409 가 31건(배포 1 · 2 · 3), 500 이 6건(배포 41 만), 그 밖 0건. 200 응답의 본문 크기는 170 ~ 200바이트다.

### 2.2 배포 · 재기동 (13:40:05 ~ 13:42:31)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 13:38 | `deploy-history.md:21` | v1.4.2 승인 | `승인: [EMAIL] (13:38)` |
| 13:40:05 | `deploy-history.md:7` | v1.4.2 배포 시작. 변경 파일은 `DistributionController.java` 하나 | `v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 \| [EMAIL] \| 재배포 응답에 학급 배포 이력(history) 포함 — `DistributionController.redistribute` \| …` |
| (배포 기록) | `deploy-history.md:15` ~ `16` | 변경 내용: 재배포 응답 형식 변경, Controller 에서 `listByClass` 호출 추가 | `변경 지점: `DistributionController.redistribute` 에서 재배포 후 `DistributionService.listByClass(classId)` 호출 추가.` |
| (배포 기록) | `deploy-history.md:17` ~ `18` | 테스트 · 배포 방식 | `스테이징 DB(시드 데이터)에서 재배포 3건 수동 확인.` / `배포 방식: 단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎.` |
| (배포 기록) | `deploy-history.md:20` | 배포 명령. 대상 호스트는 `API-1`. 토큰 값이 문서에 그대로 적혀 있다 | `curl -s -X POST https://deploy.example.com/api/v1/apps/item-bank-api/releases -H "Authorization: [TOKEN]" -d '{"version":"1.4.2","host":"API-1"}'` |
| 13:41:20 | `nginx-access.log:16829` | 재기동 전 마지막 재배포 요청. 학급 1 의 배포 43, 200, 본문 183바이트 | `교사단말A - - [17/Sep/2026:13:41:20 +0900] "POST /api/distributions/43/redistribute HTTP/1.1" 200 183 …` |
| 13:41:20.994 | `app.log:1238` | 위 요청의 성공 로그 | `redistributed distribution 43 (assignment 6, class 1) reason=학생B 요청 — 제출 화면 오류` |
| 13:41:48.214 | `app.log:1240` | 종료 시작 | `[tomcat-shutdown] o.s.b.w.e.tomcat.GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete` |
| 13:41:50.144 ~ .224 | `app.log:1241` ~ `1244` | 종료 완료, JPA · 커넥션 풀 닫힘 | `Graceful shutdown complete` / `itembank-pool - Shutdown completed.` |
| 13:41:50 | `nginx-access.log:16862` ~ `16864` | 200 응답 3건. 종료 완료(13:41:50.144)와 같은 초 **(선후 불확실)** | `… [17/Sep/2026:13:41:50 +0900] "GET / HTTP/1.1" 200 …` |
| 13:41:51 ~ 13:41:59 | `nginx-access.log:16865` ~ `16872` | 502 8건. 전부 `GET`, 경로 7종(`/`, `/api/units`, `/api/units/…/items`, `/api/items/5`, `/api/distributions/5`, `/api/distributions/8`). 본문 157바이트. 이 9초 동안 다른 상태 코드의 요청은 없다 | `10.20.x.x - - [17/Sep/2026:13:41:51 +0900] "GET /api/units/M5-2/items?student=학생C HTTP/1.1" 502 157 "https://lms.example.com/student/home" …` |
| 13:41:55.902 | `app.log:1245` | v1.4.2 기동 시작 | `Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1 (/app/app.jar started by app in /app)` |
| 13:41:55.906 | `app.log:1246` | 활성 프로필 없음 | `No active profile set, falling back to 1 default profile: "default"` |
| 13:41:58.351 ~ .535 | `app.log:1258` ~ `1260` | 커넥션 풀 기동 | `itembank-pool - Starting...` / `itembank-pool - Added connection org.mariadb.jdbc.Connection@4c1e7a3b` / `itembank-pool - Start completed.` |
| 13:42:00.304 | `app.log:1264` | 기동 완료. 종료 시작부터 12.09초 | `Started ItemBankApplication in 4.921 seconds (process running for 5.488)` |
| 13:42:01 | `nginx-access.log:16873` | 재기동 뒤 첫 200 | `10.20.x.x - - [17/Sep/2026:13:42:01 +0900] "GET /api/units HTTP/1.1" 200 604 …` |
| 13:42:10 | `nginx-access.log:16884`, `app.log:1266` | 재기동 뒤 첫 재배포 요청. 학급 3 의 배포 7, 200, 본문 802바이트(배포 전 170 ~ 200바이트) | `교사단말B - - [17/Sep/2026:13:42:10 +0900] "POST /api/distributions/7/redistribute HTTP/1.1" 200 802 …` / `redistributed distribution 7 (assignment 5, class 3) reason=보강 수업 후 재배포` |
| 13:42:31 | `deploy-history.md:7` | 배포 완료로 기록됨 | (위 13:40:05 줄과 같은 줄) |

### 2.3 배포 뒤 500 구간 (13:46:38 ~ 14:34:45)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 13:46:38.107 | `app.log:1274` | 배포 뒤 학급 1 의 첫 재배포. 재배포 성공 로그가 먼저 찍힌다 | `[nio-8080-exec-1] c.e.assignment.DistributionService : redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` |
| 13:46:38.113 | `app.log:1275` ~ `1276` | 6밀리초 뒤 같은 스레드에서 `ERROR`. 예외 메시지는 배포 전 배포 41 의 것과 같다(식별자 9) | `[nio-8080-exec-1] c.example.common.GlobalExceptionHandler : unhandled exception on /api/distributions/42/redistribute` / `org.springframework.orm.jpa.JpaObjectRetrievalFailureException: Entity `com.example.assignment.Assignment` with identifier value `9` does not exist` |
| 13:46:38.113 | `app.log:1284` ~ `1285`, `1293` ~ `1294` | 위 스택 트레이스의 `com.example` 프레임. 배포 전과 경로가 다르다 | `…findByClassRoomIdOrderByDistributedAtAsc(Unknown Source)` / `at com.example.assignment.DistributionService.listByClass(DistributionService.java:38)` / `at com.example.assignment.DistributionController.redistribute(DistributionController.java:40)` |
| 13:46:38 | `nginx-access.log:17155` | 같은 요청의 nginx 기록. 500, 본문 176바이트 | `교사단말A - - [17/Sep/2026:13:46:38 +0900] "POST /api/distributions/42/redistribute HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions/42" …` |
| 13:46:38 ~ 14:34:45 | `nginx-access.log:17155` (첫 발생) | 같은 500 의 반복. 합계 39건: 배포 42 가 19건, 배포 43 이 20건. 전부 `POST …/redistribute`, 전부 `교사단말A`. 분당 1 ~ 4건(1.2 의 표). 2 ~ 4건씩 13묶음으로 나오고, 묶음 안 간격은 1 ~ 25초, 묶음 사이 간격은 121 ~ 332초 | (위 줄과 같은 모양) |
| 13:46:38 ~ 14:34:45 | `app.log:1275` (첫 발생) | `ERROR` 39건. 39건 모두 `listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(DistributionController.java:40)` 프레임이 있고, 예외의 식별자 값은 39건 모두 9 | (위 13:46:38.113 줄과 같은 모양) |
| 13:46:38 ~ 14:34:45 | `app.log:1274` (첫 발생) | 배포 뒤에도 학급 1 의 재배포 성공 로그가 39건 찍혔다(배포 42 가 19건, 배포 43 이 20건). 500 의 건수와 같다 | `redistributed distribution 42 (assignment 4, class 1) …` |
| 13:46:58.034 | `app.log:1312` | 첫 500 뒤 20초에 같은 배포 42 로 다시 요청이 와서 성공 로그가 또 찍힘(사유는 다름). 뒤따르는 `ERROR` 는 `app.log:1313` | `redistributed distribution 42 (assignment 4, class 1) reason=학생A 결석으로 재배포` |
| 13:50:00 | `nginx-access.log:17353`, `app.log:1393` | 같은 `교사단말A` 가 학급 2 의 배포 8 을 재배포한 요청은 200, 본문 908바이트 | `교사단말A - - [17/Sep/2026:13:50:00 +0900] "POST /api/distributions/8/redistribute HTTP/1.1" 200 908 …` / `redistributed distribution 8 (assignment 6, class 2) …` |
| 13:42:10 ~ 14:22:46 | `nginx-access.log:16884`, `17353`, `17424`, `17734`, `19238`, `19347` | 배포 뒤 학급 2 · 3 의 재배포는 6건 모두 200(배포 5 · 7 · 8 · 44). 본문 640 ~ 910바이트 | (위 줄과 같은 모양) |
| 13:59:30.918 | `app.log:1837` (배포 뒤 첫 발생) | 마감된 과제 재배포의 409 `WARN`. 배포 뒤 4건(`1837`, `2550`, `2679`, `2925`)으로 평소 수준 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distribut…` |
| 14:34:45.433 / .439 | `app.log:2886` ~ `2887`, `nginx-access.log:20084` | 로그에 남은 마지막 500. 성공 로그 6밀리초 뒤 `ERROR` 인 것도 첫 건과 같다 | `redistributed distribution 43 (assignment 6, class 1) reason=학생B 요청 — 제출 화면 오류` / `unhandled exception on /api/distributions/43/redistribute` |
| 14:39:58 | `nginx-access.log:20365` | 로그 끝. 마지막 500 뒤 313초 | `10.20.x.x - - [17/Sep/2026:14:39:58 +0900] "GET /api/units HTTP/1.1" 200 613 …` |

배포 뒤 재배포 `POST` 응답(전체 49건, 13:42:10 이후)

| 대상 | 요청 | 200 | 409 | 500 |
|---|---|---|---|---|
| 학급 1 (배포 42 · 43) | 39 | 0 | 0 | 39 |
| 학급 2 · 3 (배포 5 · 7 · 8 · 44) | 6 | 6 | 0 | 0 |
| 배포 2 · 3 (마감) | 4 | 0 | 4 | 0 |

- 학급 번호는 `app.log` 의 성공 로그(`… (assignment N, class N)`)에서 읽었다. 배포 41 · 1 · 2 · 3 은 성공 로그가 없어 어느 학급인지 이 로그로 알 수 없다.
- 배포 뒤 500 은 재배포 `POST` 에만 있다. `GET` 요청의 5xx 는 13:42:01 이후 0건이다.

### 2.4 찾았지만 로그에 없던 것

| 찾은 것 | 검색 대상 · 검색어 | 결과 |
|---|---|---|
| 13:41 재기동 말고 다른 재시작 | `app.log` 에서 `Starting ItemBankApplication`, `Commencing graceful shutdown` | 각 1건(`app.log:1245`, `app.log:1240`). 다른 재시작 0건. 09:00 이전의 기동 줄은 이 파일에 없다 |
| 롤백 · 이전 버전 재기동 | `app.log` 에서 `rollback`, `Rollback`, `v1.4.1` | 0건. `deploy-history.md` 에도 v1.4.2 뒤의 배포 줄이 없다 |
| 대기 호스트로 전환 | `app.log` 에서 `api-2` | 0건. `nginx-access.log` 는 combined 형식이라 upstream 주소 필드가 없어 어느 서버가 응답했는지 알 수 없다 |
| 설정 재적재 | `app.log` 에서 `Refreshing`, `reload` | 0건 |
| 커넥션 풀 고갈 · 타임아웃 | `app.log` 에서 `HikariPool.*not available`, `SQLTransientConnection`, `timeout`, `Deadlock`, `OutOfMemory` | 전부 0건 |
| 느린 응답 · 끊긴 요청 | `nginx-access.log` 의 상태 504, 499 | 0건. 이 파일에는 응답 시간 필드가 없다 |
| 전체 트래픽 증가 | `nginx-access.log` 10분 단위 요청 수 | 13:40 이후 549 ~ 665건. 평소 516 ~ 682건 범위 안 |
| 다른 종류의 예외 | `app.log` 의 예외 머리 줄(시각 없는 줄 중 `at`, `...` 로 시작하지 않는 줄) | 2종뿐, 각 46건: `JpaObjectRetrievalFailureException`, `Caused by: org.hibernate.FetchNotFoundException`. 식별자 값은 46건 모두 9 |
| 배포 뒤 배포 41 요청 | `nginx-access.log` 에서 `/api/distributions/41` (13:42 이후) | 0건. 마지막 요청은 11:18:02(`nginx-access.log:8468`) |
| 배포 42 · 43 단건 조회 | `nginx-access.log` 에서 `GET /api/distributions/42`, `…/43` | 로그 전체에서 0건 |
| 배포 뒤 학급 1 리포트 | `nginx-access.log` 에서 `/api/classes/1/report` (13:42 이후) | 0건(배포 전 17건 모두 200). 학급 2 · 3 리포트는 배포 뒤 10건 모두 200 |
| DB 쪽 로그 | 폴더의 파일 목록 | DB 로그 · nginx error 로그는 이 묶음에 없다 |

## 3. 가설과 검증

- 여기부터는 추론이다. 1 · 2절의 사실과 구분해 읽어야 한다.
- 다룰 증상은 둘이다. **증상 S1**: 13:41:51 ~ 13:41:59 의 502 8건. **증상 S2**: 13:46:38 이후 학급 1 의 재배포 `POST` 가 39건 모두 500 이고, 그때마다 재배포 성공 로그는 찍힌 것.
- 건수가 가장 많은 메시지(`Assignment … identifier value 9 does not exist`)는 증상으로 본다. 같은 메시지가 배포 전에도 7건 있었으므로(2.1), 이 메시지가 있다는 것만으로는 "왜 13:46 부터 늘었는가"가 설명되지 않는다.
- 이 중간본에서는 가설과 반증 조건 · 확인 방법까지만 적었다. 반증 확인을 실행하지 않았고, 유지 · 기각을 표시하지 않았다.

### 가설 표

| ID | 계층 | 가설 | 지지 근거 | 반증 조건(이것이 보이면 틀렸다) | 확인 방법 |
|---|---|---|---|---|---|
| H1 | 코드(v1.4.2 변경분) | v1.4.2 가 `DistributionController.redistribute` 에 넣은 `listByClass` 호출이 학급 1 에서 예외를 내고, 그 예외가 그대로 500 이 된다. 재배포 자체는 그 전에 끝나 있어서, 응답은 500 인데 재배포는 반영된다(S2) | 배포 뒤 `ERROR` 39건 모두 `listByClass(DistributionService.java:38)` ← `DistributionController.redistribute(…:40)` (`app.log:1285`, `1294`). 배포 전 프레임에는 `listByClass` 가 없다(`app.log:205` ~ `206`, `214`). 변경 내용이 배포 기록과 맞는다(`deploy-history.md:16`). 성공 로그 6밀리초 뒤 같은 스레드에서 `ERROR` (`app.log:1274` ~ `1275`). 배포 전 학급 1 재배포는 18건 성공(`app.log:59` 등), 재기동 전 마지막도 성공(`app.log:1238`) | ① 배포 뒤 `ERROR` 중 `listByClass` 프레임이 없는 것이 있다. ② 배포 뒤 학급 1 재배포가 200 으로 끝난 건이 있다. ③ 배포 전에 배포 42 · 43 의 재배포가 500 이었던 건이 있다. ④ 저장소의 v1.4.2 코드에서 `listByClass` 호출이 재배포 트랜잭션 안에 있어 실패 시 재배포도 되돌려진다(그러면 "500 인데 반영됨"이 틀린다) | 로그: `ERROR` 줄별로 뒤따르는 트레이스에 `listByClass` 가 있는지 세기, 재배포 `POST` 를 학급 · 상태 · 배포 전후로 집계. 코드: `modern/api/…/assignment/DistributionController.java`, `DistributionService.java` 의 `redistribute` · `listByClass` 와 `@Transactional` 경계 읽기(이 중간본에서는 하지 않음). 사고 당시 배포본이 저장소 코드와 같은지는 배포 담당자에게 요청 |
| H2 | DB · 데이터 | 학급 1 에 속한 배포 가운데 `Assignment` 9 를 가리키는 행이 있는데 그 과제 행이 없다(깨진 참조). 이 데이터는 배포 전부터 있었고, 그동안은 그 배포(배포 41 로 추정)를 직접 건드린 요청만 실패했다. 학급 전체 이력을 읽는 순간 같은 학급의 다른 배포 요청도 실패한다 | 예외의 식별자 값이 46건 모두 9(`app.log:195`, `452`, `1276`). 배포 전 500 7건은 전부 배포 41(`nginx-access.log:3150`, `5563`). 학급 2 · 3 의 재배포는 배포 뒤 6건 모두 200(`nginx-access.log:16884`, `17353` 등). 스테이징은 시드 데이터로만 확인했다고 적혀 있다(`deploy-history.md:17`) | ① 배포 41 이 학급 1 이 아니다. ② `Assignment` 9 행이 있다(그러면 조회 방식 쪽 문제다). ③ 학급 1 에 배포 41 말고도 과제 9 를 가리키는 배포가 있거나, 깨진 참조가 다른 학급에도 있는데 그 학급은 성공했다. ④ 그 행이 13:40 이후에 만들어지거나 지워졌다(그러면 "배포 전부터 있던 데이터"가 틀린다) | 로그만으로는 배포 41 의 학급을 알 수 없다(2.3 의 주). 저장소의 `db/` 시드 · 스키마에서 배포 41 과 과제 9, 외래 키 유무 확인(로컬 값). 운영 DB 의 해당 행 · 변경 시각은 DB 담당자에게 요청 |
| H3 | 트래픽 · 클라이언트 | 500 의 건수(39건)는 `교사단말A` 한 곳이 실패 응답을 보고 같은 재배포를 다시 누른 결과다. 트래픽이 원인은 아니고 건수와 부작용(재배포가 요청 수만큼 거듭 반영됨)을 키웠다 | 39건 모두 `교사단말A`(`nginx-access.log:17155` 등). 2 ~ 4건씩 13묶음, 묶음 안 간격 1 ~ 25초. 요청마다 성공 로그가 찍힘(`app.log:1274`, `1312`). 전체 요청 수는 평소 범위(1.2) | ① 같은 묶음의 요청이 서로 다른 단말 · 사용자에게서 왔다(IP 하나 뒤에 여러 사용자가 있는 경우). ② 묶음 안 요청이 사람이 아니라 클라이언트의 자동 재시도다(간격이 일정하다). ③ 500 일 때 재배포가 실제로는 반영되지 않았다(H1 의 반증 ④와 같다) | 로그: 묶음별 간격 분포, User-Agent · Referer 가 묶음 안에서 같은지 확인. `modern/web` 에 재배포 호출의 자동 재시도가 있는지 확인. 운영 DB 의 배포 42 · 43 재배포 횟수는 DB 담당자에게 요청. 교사 화면에서 실제로 무엇이 보였는지는 교무부 · 화면 담당자에게 요청 |
| H4 | 인프라(nginx · 호스트) | S1 의 502 는 단일 호스트를 내렸다 올리는 동안 nginx 가 보낼 upstream 이 없어서 생긴 것이고, S2 와는 별개 사건이다. 반대로 "S2 도 재기동 후유증(기동 직후 불완전한 상태, 커넥션 풀)이다"라는 설명도 이 계층의 후보다 | 502 는 종료 완료(13:41:50.144, `app.log:1241`)와 기동 완료(13:42:00.304, `app.log:1264`) 사이 9초에만 있다(`nginx-access.log:16865` ~ `16872`). 배포 방식이 "단일 호스트 재기동(무중단 아님)" (`deploy-history.md:18`). 대기 호스트는 "평소 정지"(`deploy-history.md:3`) | S1 쪽: ① 13:42:01 이후에도 502 가 있다. ② 502 가 특정 경로에만 있다. S2 가 재기동 후유증이라는 쪽: ③ 500 이 `app.log` 에 애플리케이션 예외로 찍혀 있다(인프라 오류면 앱 로그에 없어야 한다). ④ 풀 · 타임아웃 오류가 0건이다. ⑤ 500 이 기동 직후가 아니라 49분 내내 같은 비율로 난다. ⑥ 같은 시간대 다른 경로는 정상이다 | 로그: 13:41:48 ~ 13:42:05 요청의 상태 코드 나열, 502 의 경로 분포, 2.4 의 검색 결과 재확인. nginx upstream 설정과 error 로그(이 묶음에 없음)는 인프라 담당자에게 요청 |
| H5 | 배포 · 설정 | 재기동하면서 코드 말고 설정이 달라졌다(프로필, DB 접속 대상). 그래서 배포 뒤의 앱이 배포 전과 다른 데이터를 보고 있다 | 기동 로그에 활성 프로필이 없다고 찍힌다(`app.log:1246`). 재기동 뒤에야 500 이 는다(1.2) | ① 배포 전후의 예외가 같은 엔티티 · 같은 식별자를 가리킨다(같은 데이터를 보고 있다는 뜻). ② 배포 전후로 같은 배포 ID 의 학급 · 과제 번호가 성공 로그에서 같다. ③ v1.4.2 의 변경 파일에 설정 파일이 없다. ④ 이전 기동 때도 프로필이 `default` 였다 | 로그: 배포 전후 예외 메시지 · 성공 로그의 `(assignment N, class N)` 비교. `deploy-history.md:7` 의 변경 파일 열 확인. ④는 이 로그에 이전 기동 줄이 없어(2.4) 확인할 수 없다. 이전 기동 로그 · 배포 환경 변수는 배포 담당자에게 요청 |

### 가설을 내지 않은 계층

| 계층 | 이유 |
|---|---|
| DB 성능 · 쿼리(느린 쿼리, 락, 풀 고갈) | DB 계층에서는 데이터 가설(H2)만 냈다. 성능 쪽은 걸 만한 줄이 없다. 풀 · 타임아웃 · 데드락 검색이 전부 0건이고 504 · 499 도 0건이다(2.4). 다만 응답 시간 필드와 DB 로그가 이 묶음에 없어서, "느리지 않았다"를 로그로 보인 것은 아니다 |
| 외부 연동 | 500 의 스택 트레이스에 외부 호출 프레임이 없다. 프레임은 JPA 조회에서 끝난다(`app.log:1284`) |

### 다음 단계에서 할 일(이 중간본에서는 하지 않음)

- 가설별 반증 조건을 로그로 확인하고 실행 기록(E1 …)으로 남긴 뒤 유지 / 기각 / 판단 불가를 표시한다.
- 유지된 가설을 `modern/` 소스 · `application.yml` · `db/` 와 대조한다.
- 4절 이후(원인, 근거 로그 줄, 수정안, 재발 방지, 모니터링, 확인하지 못한 것)와 맨 위 요약을 쓴다.

마스킹 적용: IP 주소 10건 · 이메일 2건 · 학생 식별자 6건 · 토큰 · Authorization 값 1건 · 비밀번호 · 접속 문자열 0건
