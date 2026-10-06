# RCA — a-connection-pool

대상 로그: `incident-logs/a-connection-pool/` (2026-09-16)

## 요약

- **무슨 일**: 2026-09-16 14:40:52 ~ 14:51:47 KST(약 11분) 동안 item-bank API 가 5xx 를 186건(500 49 · 502 82 · 504 55) 돌려줬다. 14:40 ~ 14:51 요청 509건 중 약 37%다. 첫 이상 신호는 14:37:52 에 있었다.
- **원인**: 학급 리포트(`ReportService.buildClassReport`)가 DB 조회를 마친 뒤에도 트랜잭션 안에서 서명 계산을 해 커넥션을 16 ~ 22초씩 쥐었다. 리포트 요청이 5건 겹치자 풀 5개가 전부 묶여 다른 API 가 3초 대기 뒤 실패했다.
- **확산**: 리포트 응답이 늦어지자 nginx 가 API 서버를 일시 제외했고, 502 가 리포트가 아닌 경로까지 퍼졌다.
- **확신 수준**: 중간. 로그 · 코드 · 설정이 서로 맞지만, 14:37:42 부터 왜 오래 걸리기 시작했는지와 사고 당시 배포본이 저장소 코드와 같은지는 확인하지 못했다.
- **조치 상태**: 코드는 고치지 않았다. 6 ~ 8절은 제안이다.

## 1. 수집 범위

이 절은 "어느 시간대의 로그를 볼 것인가"만 정한다. 원인 판단은 넣지 않았다.
모든 수치는 `wc` · `head` · `tail` · `grep` · `awk` 집계로 얻었고, 파일을 통째로 읽지 않았다.

### 1.1 파일별 기록 기간 · 시각 형식

| 파일 | 줄 수 | 기록 기간(원문 표기) | 기록 기간(KST) | 시각 형식 | 타임존 |
|---|---|---|---|---|---|
| `app.log` | 5,371 (시각이 붙은 줄 1,927 + 스택 트레이스 등 이어지는 줄 3,444) | 06:43:56.753 ~ 15:29:51.760 | 06:43:56 ~ 15:29:51 | `2026-09-16T06:43:56.753+09:00` (ISO-8601, 밀리초) | KST, 오프셋 `+09:00` 명시 (1,927줄 전부) |
| `nginx-access.log` | 21,086 | 06:43:56 ~ 15:29:59 | 06:43:56 ~ 15:29:59 | `[16/Sep/2026:06:43:56 +0900]` (초 단위) | KST, 오프셋 `+0900` 명시 (21,086줄 전부) |
| `nginx-error.log` | 168 | 09:12:03 ~ 14:51:47 | 09:12:03 ~ 14:51:47 | `2026/09/16 09:12:03` (초 단위) | 표기 없음. KST 로 판단(아래 근거) |
| `mariadb-slow.log` | 21 (쿼리 3건) | `# Time:` 0:14:09 ~ 5:37:52 | 09:14:07 ~ 14:37:52 | `# Time: 260916  0:14:09` (YYMMDD H:MM:SS) + `SET timestamp=<epoch 초>` | `# Time:` 은 UTC, `SET timestamp` 는 epoch |

타임존 판단 근거

- `nginx-error.log`: 오프셋 표기가 없다. `[error]` 줄이 14:41:06 ~ 14:51:47 에만 있고, `nginx-access.log`(`+0900`)의 5xx 가 14:40:52 ~ 14:51:47 에만 있어 분 단위 분포가 겹친다. 마지막 줄 시각(14:51:47)은 access 의 마지막 504 시각과 초까지 같다. 그래서 KST 로 본다. nginx 설정으로 확인한 것은 아니다.
- `mariadb-slow.log`: `SET timestamp` 를 KST 로 바꾼 값에 `Query_time` 을 더하면 `# Time:` 값 + 9시간과 맞는다.

| 쿼리 | `# Time:` (UTC, 종료 시각) | `SET timestamp` → KST (시작 시각) | `Query_time` |
|---|---|---|---|
| 1 | 0:14:09 | 09:14:07 | 2.32초 |
| 2 | 2:02:42 | 11:02:40 | 2.04초 |
| 3 | 5:37:52 | 14:37:42 | 9.81초 |

KST 변환 규칙

| 파일 | 규칙 |
|---|---|
| `app.log` | 그대로 사용 |
| `nginx-access.log` | 그대로 사용 |
| `nginx-error.log` | 그대로 사용(KST 로 간주). 오프셋이 없으므로 인용할 때 "KST 추정"이라고 적는다 |
| `mariadb-slow.log` | `# Time:` 값 + 9시간 = 쿼리 종료 시각(KST). 시작 시각은 `SET timestamp` 를 `TZ=Asia/Seoul date -d @<값>` 으로 변환 |

### 1.2 분 단위 건수

세는 기준

| 파일 | 센 줄 |
|---|---|
| `app.log` | 로그 레벨 `WARN` · `ERROR` 줄(시각이 붙은 줄만, 스택 트레이스 줄은 제외) |
| `nginx-access.log` | 상태 코드 500 · 502 · 504 |
| `nginx-error.log` | `[warn]` · `[error]` 줄 |
| `mariadb-slow.log` | 쿼리 3건뿐이라 분 단위 집계 대신 1.1 의 표로 대신한다 |

평소 수준(06:43 ~ 14:36)

| 파일 | 평소 수준 |
|---|---|
| `app.log` | `ERROR` 0건. `WARN` 은 분당 최대 1건, 8시간 동안 16건이며 모두 `state conflict`(409) 종류 |
| `nginx-access.log` | 5xx 0건. 요청량은 14시대 기준 분당 34 ~ 58건 |
| `nginx-error.log` | `[error]` 0건. `[warn]` 2건(09:12, 13:05) |

14:37 ~ 14:52 (KST) 분 단위 건수. 이 구간 밖에는 `app.log` `ERROR`, access 5xx, `nginx-error.log` `[error]` 가 한 건도 없다.

| 분 | app `WARN` | app `ERROR` | access 500 | access 502 | access 504 | access 전체 요청 | nginx-error `[warn]` | nginx-error `[error]` |
|---|---|---|---|---|---|---|---|---|
| 14:37 | 1 | 0 | 0 | 0 | 0 | 50 | 0 | 0 |
| 14:38 | 2 | 0 | 0 | 0 | 0 | 41 | 0 | 0 |
| 14:39 | 3 | 0 | 0 | 0 | 0 | 42 | 0 | 0 |
| 14:40 | 15 | 2 | 1 | 0 | 0 | 42 | 0 | 0 |
| 14:41 | 19 | 12 | 6 | 0 | 3 | 42 | 0 | 3 |
| 14:42 | 13 | 4 | 2 | 13 | 5 | 41 | 2 | 18 |
| 14:43 | 24 | 20 | 10 | 2 | 5 | 58 | 1 | 7 |
| 14:44 | 13 | 4 | 2 | 5 | 6 | 34 | 4 | 11 |
| 14:45 | 17 | 16 | 8 | 14 | 5 | 39 | 3 | 19 |
| 14:46 | 16 | 8 | 4 | 16 | 6 | 51 | 3 | 22 |
| 14:47 | 14 | 10 | 5 | 6 | 3 | 38 | 1 | 9 |
| 14:48 | 14 | 8 | 4 | 6 | 5 | 42 | 4 | 11 |
| 14:49 | 19 | 14 | 7 | 8 | 8 | 42 | 4 | 16 |
| 14:50 | 12 | 0 | 0 | 11 | 7 | 34 | 4 | 18 |
| 14:51 | 4 | 0 | 0 | 1 | 2 | 46 | 0 | 3 |
| 14:52 | 1 | 0 | 0 | 0 | 0 | 47 | 0 | 0 |
| 합계 | 187 | 98 | 49 | 82 | 55 | — | 26 | 137 |

- 14:52 의 `WARN` 1건은 평소에도 나오는 `state conflict`(409) 종류다.
- 전체 요청 수는 구간 안팎에서 분당 34 ~ 58건으로 같은 범위에 있다.

구간 안에서 처음 · 마지막으로 나온 줄(종류별)

| 파일:줄 | 시각(KST) | 내용 |
|---|---|---|
| `mariadb-slow.log:15` | 14:37:42 시작, 14:37:52 종료 | slow query 3번(9.81초) |
| `app.log:1243` | 14:37:52.199 | 첫 `ProxyLeakTask` `WARN` (평소에 없던 종류의 첫 줄) |
| `app.log:1522` | 14:40:52.319 | 첫 `ERROR` (`Connection is not available, request timed out`) |
| `nginx-access.log:18918` | 14:40:52 | 첫 5xx (500) |
| `nginx-error.log:6` | 14:41:06 | 첫 `[error]` (`upstream timed out`) |
| `app.log:4981` | 14:49:51.553 | 마지막 `ERROR` |
| `nginx-access.log:19381` | 14:51:47 | 마지막 5xx (504) |
| `nginx-error.log:168` | 14:51:47 | 마지막 `[error]` (파일의 마지막 줄) |
| `app.log:5267` | 14:51:49.148 | 마지막 `ProxyLeakTask` 줄(`INFO`) |

제안하는 이상 구간

| | 시각(KST) | 근거 |
|---|---|---|
| 시작 | **14:37:00** | 평소에 없던 종류의 `WARN` 이 14:37:52 에 처음 나오고, 같은 분에 slow query 3번이 시작한다 |
| 오류 급증 시작 | 14:40:52 | `app.log` 첫 `ERROR` 와 access 첫 500 이 같은 초에 나온다 |
| 끝 | **14:52:00** | 마지막 5xx · `[error]` 가 14:51:47, 마지막 `ProxyLeakTask` 줄이 14:51:49. 14:52 부터 15:29 까지 `ERROR` · 5xx 0건 |

### 1.3 앞뒤 여유

| | 여유 | 수집 구간 경계(KST) | 이유 |
|---|---|---|---|
| 앞 | 30분 | 14:07:00 | 구간 첫 신호들이 "일정 시간이 지난 뒤에야 찍히는" 종류다(slow query 는 종료 시점, 타임아웃은 대기 시간이 지난 뒤 기록). 실제 변화는 첫 줄보다 앞설 수 있다. 평소 요청 패턴과 비교할 분량도 필요하다 |
| 뒤 | 10분 | 15:02:00 | 14:52 이후 `ERROR` · 5xx 가 0건으로 돌아온 것을 확인하는 데 필요한 분량 |

제안 수집 구간: **2026-09-16 14:07:00 ~ 15:02:00 KST (55분)**

| 파일 | 줄 범위 | 줄 수 |
|---|---|---|
| `app.log` | 1135 ~ 5287 | 4,153 |
| `nginx-access.log` | 17405 ~ 19805 | 2,401 |
| `nginx-error.log` | 6 ~ 168 | 163 |
| `mariadb-slow.log` | 15 ~ 21 (쿼리 3번) | 7 |

구간 밖에 남는 것

- `mariadb-slow.log` 쿼리 1번(09:14 KST) · 2번(11:02 KST): 비교용으로 필요하면 따로 본다.
- `nginx-error.log` 1 ~ 5줄(09:12 ~ 13:05 KST 의 `[warn]` 2건, `[info]` 3건).

로그를 인용할 때 주의

- `nginx-error.log:2` 에 `Authorization: [TOKEN]` 토큰 일부가 들어 있다. 문서에 옮기지 않는다.
- `nginx-access.log` 의 요청 URL 에 학생 식별자(`STU-…`)가 들어 있다. 인용할 때 가린다.

## 2. 타임라인

대상: 1.3 의 수집 구간(2026-09-16 14:07:00 ~ 15:02:00 KST)에 든 줄만. 로그에 있는 사실만 적었고 원인 해석은 넣지 않았다.

읽는 법

- 시각은 모두 KST 다. `mariadb-slow.log` 는 1.1 의 규칙으로 변환했다.
- 출처의 `access` 는 `nginx-access.log`, `error` 는 `nginx-error.log`, `slow` 는 `mariadb-slow.log`, `app` 은 `app.log` 다.
- 반복되는 메시지는 첫 발생 줄 하나로 묶고, 요약에 분당 건수를 적었다.
- **(선후 불확실)**: 서로 다른 파일의 줄이 같은 초(또는 몇 초 이내)에 찍혀 어느 쪽이 먼저인지 정할 수 없는 곳이다. `app.log` 만 밀리초 단위이고 나머지 세 파일은 초 단위다.
- `nginx-access.log` 의 시각이 요청 도착 시각인지 응답 완료 시각인지는 로그만으로는 확정하지 못했다. 응답 시간 필드도 없다.
- 발췌의 `…` 는 생략, `학생A` · `학생B` 는 학생 식별자를 등장 순서대로 바꾼 별칭이다.

### 2.1 오류 급증 전 (14:07:00 ~ 14:40:51)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07:00 | access:17405 | 수집 구간 시작. 14:07 ~ 14:36 전체 요청 분당 32 ~ 56건(합 1,346건), 5xx 0건 | `"GET /api/units/M5-1/items HTTP/1.1" 200 1748` |
| 14:10:05.155 | app:1148 | 학급 리포트 생성 로그. 14:10 ~ 14:18 에 5건(분당 0 ~ 1건). 그날 13시대까지는 시간당 2 ~ 11건 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38.554 | app:1154, access:17605 | 재배포 409 `WARN`. 평소에도 나오는 종류. 구간 안 7건(14:11, 14:14, 14:33, 14:42, 14:50, 14:51, 14:52) | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3 …` |
| 14:18:13 | access:17914 | 급증 전 마지막 리포트 요청. 이후 14:38:01 까지 19분 48초 동안 리포트 요청 0건 | `"GET /api/classes/1/report HTTP/1.1" 200 258` |
| 14:19:38.987 | app:1182, access:17977 | 재배포 성공(distribution 7). 평소에도 나오는 종류(하루 29건) | `redistributed distribution 7 (assignment 5, class 3) reason=학생A …` |
| 14:37:42 | slow:20 | slow query 시작 시각(`SET timestamp`). 구간 안 slow query 는 이 1건뿐 | `SET timestamp=1789537062;` |
| 14:37:52 | slow:15, slow:18 | slow query 종료 기록. 9.81초, 반환 4행, 검사 1,284,310행. 그날 앞선 2건은 2.32초 · 2.04초. **(선후 불확실: 아래 app:1243 과 같은 초)** | `# Query_time: 9.812417 … Rows_sent: 4  Rows_examined: 1284310` |
| 14:37:52 | slow:21 | 위 slow query 의 SQL | `… from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id;` |
| 14:37:52.199 | app:1243 | `ProxyLeakTask` `WARN` 첫 발생(스레드 `exec-6`). 그날 이 시각 전에는 0건. 이후 분당 14:37 1 → 14:38 2 → 14:39 3 → 14:40 14 → 14:41 13 → 14:42 10 → 14:43 14 → 14:44 11 → 14:45 9 → 14:46 12 → 14:47 9 → 14:48 10 → 14:49 12 → 14:50 11 → 14:51 3 (합 134건). 대상 커넥션 ID 는 5개 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3 on …` |
| 14:37:52.199 | app:1253 ~ 1254 | 위 `WARN` 스택 트레이스에 있는 애플리케이션 프레임 | `at com.example.assignment.ReportController.classReport(ReportController.java:22)` |
| 14:38:01.900 | app:1258 | 같은 스레드(`exec-6`)의 학급 리포트 생성 로그. 리포트 생성 로그가 분당 14:38 3 → 14:39 3 → 14:40 10 → 14:41 15 로 늘고 14:51 까지 합 134건 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:01.902 | app:1259 | 누수로 보고된 커넥션 반환 `INFO`. 14:51:49 까지 134건 | `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3 …` |
| 14:38:01 | access:18801 | 리포트 요청 재개(200). **(선후 불확실: app:1258 · 1259 와 같은 초)** 분당 14:38 3 → 14:39 3 → 14:40 10 → 14:41 16 → 14:42 15 → 14:43 19 → 14:44 14 → 14:45 15 → 14:46 19 → 14:47 12 → 14:48 14 → 14:49 16 → 14:50 10 → 14:51 5 (합 171건). 같은 시간대 전체 요청은 분당 34 ~ 58건으로 변화 없음 | `"GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:01 | access:18801 | 리포트 요청에 Safari User-Agent 가 처음 나옴. 14:37 ~ 14:52 리포트 요청 171건 중 69건. 그 밖 시간대 리포트 요청 58건은 모두 Firefox User-Agent | `… Version/17.6 Safari/605.1.15` |
| 14:38:29.715 | app:1278, access:18817 | 재배포 성공(distribution 5). 평소에도 나오는 종류 | `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| 14:39:54 | access:18881 | 리포트 요청 클라이언트가 늘어남. 14:38 은 `교사단말A` 1곳, 14:39 `교사단말B` 추가, 14:40 `교사단말C`(access:18904) 추가 | `"GET /api/classes/1/report HTTP/1.1" 200 271` |
| 14:40:43 | access:18912 | `교사단말D` 의 첫 리포트 요청. 이 클라이언트의 리포트 요청은 14:37 ~ 14:52 에만 29건 있고 그 밖 시간대에는 0건(다른 경로 요청은 하루 내내 있음) | `"GET /api/classes/1/report HTTP/1.1" 200 255` |

### 2.2 오류 구간 (14:40:52 ~ 14:51:49)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:40:52.319 | app:1521 ~ 1522 | 커넥션 획득 타임아웃 `WARN` + `ERROR` 쌍 첫 발생. 분당 14:40 1 → 14:41 6 → 14:42 2 → 14:43 10 → 14:44 2 → 14:45 8 → 14:46 4 → 14:47 5 → 14:48 4 → 14:49 7 (합 49쌍). 모두 `3000ms`, `total=5, active=5, idle=0`, `waiting` 은 1 ~ 7 | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 14:40:52.320 | app:1523 | `unhandled exception` `ERROR` 첫 발생. 합 49건이고 분당 건수는 위 줄과 같다. 경로별: `/api/units/{code}/items` 19, `/api/units` 14, `/api/items/{id}` 7, `/api/classes/{id}/report` 5, `/api/distributions/{id}` 4 | `unhandled exception on /api/units/M6-2/items` |
| 14:40:52 | access:18918 | 첫 500. 합 49건, 분당 건수는 app 의 `unhandled exception` 과 같다. **(선후 불확실: app:1521 ~ 1523 과 같은 초)** | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| 14:41:06 | error:6 | `upstream timed out` 첫 발생(upstream `API-1:8080`). 분당 14:41 3 → 14:42 5 → 14:43 5 → 14:44 6 → 14:45 5 → 14:46 6 → 14:47 3 → 14:48 5 → 14:49 8 → 14:50 7 → 14:51 2 (합 55건) | `upstream timed out (110: Connection timed out) while reading response header…` |
| 14:41:06 | access:18933 | 첫 504. 합 55건, 모두 `/api/classes/{id}/report`. **(선후 불확실: error:6 과 같은 초)** | `"GET /api/classes/3/report HTTP/1.1" 504 167` |
| 14:42:01 | error:10 | `upstream server temporarily disabled` `[warn]` 첫 발생(upstream `API-1:8080`). 분당 1 ~ 4건, 14:50:52 까지 합 26건 | `upstream server temporarily disabled while reading response header…` |
| 14:42:01 | error:11 | `connect() failed` 첫 발생(upstream `API-2:8080`). 분당 1 ~ 2건, 14:50:52 까지 합 14건. error:10 과 같은 초이고 파일 안 순서는 error:10 이 먼저 | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:42:01 | access:18970 | 첫 502. 분당 14:42 13 → 14:43 2 → 14:44 5 → 14:45 14 → 14:46 16 → 14:47 6 → 14:48 6 → 14:49 8 → 14:50 11 → 14:51 1 (합 82건). **(선후 불확실: error:10 · 11 과 같은 초)** | `"GET /api/classes/2/report HTTP/1.1" 502 157` |
| 14:42:04 | error:12 | `no live upstreams` 첫 발생. 분당 14:42 11 → 14:43 1 → 14:44 4 → 14:45 12 → 14:46 14 → 14:47 5 → 14:48 5 → 14:49 6 → 14:50 9 → 14:51 1 (합 68건) | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:44:19.827 | app:2929 | 재배포 성공(distribution 8). 평소에도 나오는 종류 | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:49:51.552 | app:4980 ~ 4981 | 마지막 커넥션 획득 타임아웃 `ERROR` 와 마지막 `unhandled exception` | `unhandled exception on /api/units/M5-3/items` |
| 14:49:51 | access:19305 | 마지막 500. **(선후 불확실: app:4980 ~ 4981 과 같은 초)** | `"GET /api/units/M5-3/items HTTP/1.1" 500 162` |
| 14:50:52 | error:158 ~ 159 | 마지막 `temporarily disabled` `[warn]` 과 마지막 `connect() failed` | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:51:02 | error:166 | 마지막 `no live upstreams` | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:51:02 | access:19347 | 마지막 502. **(선후 불확실: error:166 과 같은 초)** | `"GET /api/units/M5-3/items?student=학생B HTTP/1.1" 502 157` |
| 14:51:37.674 | app:5251 | 마지막 `ProxyLeakTask` `WARN` | `Connection leak detection triggered for org.mariadb.jdbc.Connection@…` |
| 14:51:47 | error:168 | 마지막 `upstream timed out`. `nginx-error.log` 의 마지막 줄 | `upstream timed out (110: Connection timed out) while reading response header…` |
| 14:51:47 | access:19381 | 마지막 504이자 마지막 5xx. **(선후 불확실: error:168 과 같은 초)** | `"GET /api/classes/3/report HTTP/1.1" 504 167` |
| 14:51:49.146 | app:5266 ~ 5267 | 급증 구간의 마지막 리포트 생성 로그와 마지막 커넥션 반환 `INFO` | `built class report for class 3: 3 distributions, 10 submissions` |

### 2.3 오류 구간 뒤 (14:51:50 ~ 15:02:00)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:52:00 | access:19393 ~ 19805 | 14:52 ~ 15:01 전체 요청 분당 35 ~ 47건(합 413건), 5xx 0건. 리포트 요청은 3건(14:59 2건, 15:01 1건) | — (집계 행) |
| 14:52:09.185 | app:5269, access:19401 | 재배포 409 `WARN`. 평소에도 나오는 종류. 구간 안 마지막 `WARN` | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=4 …` |
| 15:01:20.671 | app:5287 | 구간 안 `app.log` 마지막 줄 | `built class report for class 3: 3 distributions, 10 submissions` |
| 15:01:54 | access:19805 | 구간 안 `nginx-access.log` 마지막 줄 | `"GET /api/items/13 HTTP/1.1" 200 689` |

### 2.4 찾았지만 로그에 없던 것

| 찾은 것 | 방법 | 결과 |
|---|---|---|
| 설정 재적재 · 재시작 · 배포 | 네 파일에서 `reload`, `reconfigur`, `signal`, `SIGHUP`, `restart`, `Started `, `shutdown`, `HikariPool-`, `Start completed`, `deploy` 를 대소문자 구분 없이 검색 | 네 파일 모두 0건 |
| 전체 트래픽 증가 | `nginx-access.log` 분당 요청 수 비교 | 구간 전 32 ~ 56건, 오류 구간 34 ~ 58건, 구간 뒤 35 ~ 47건. 늘어난 것은 `/api/classes/{id}/report` 요청뿐이다 |
| 구간 안 다른 slow query | `mariadb-slow.log` 의 `# Time:` 줄 | 14:37:52 의 1건뿐 |

타임라인을 만들다 본 것(이 절의 범위 밖)

- `app.log:1182` 등 재배포 로그의 `reason=` 값에 학생 식별자(`STU-…`)와 이메일이 그대로 찍혀 있다. 타임라인의 사건은 아니어서 여기에만 적어 둔다.

## 3. 가설과 검증

2절의 타임라인만 근거로 세운 가설이다. 각 표의 "실행 결과 · 판정" 열은 "확인 방법" 중 로그로 확인할 수 있는 것만 실행한 결과다(2026-10-06). `E1` ~ `E9` 는 이 절 끝 "실행 기록"의 번호다. 판정은 유지 / 기각 / 판단 불가(로그 부족) 중 하나이며, 로그만 본 판정이므로 근본 원인 확정이 아니다.

읽는 법

- 다음 메시지는 건수가 많지만 원인이 아니라 증상으로 다룬다: `Connection leak detection triggered`(134건), `Connection is not available, request timed out`(49건), `upstream timed out`(55건), `no live upstreams`(68건). 가설은 "왜 그 증상이 생겼는가"를 적는다.
- 지지 근거는 `로그 파일:줄번호` 로 적고, 괄호 안에 그 줄을 다룬 이 문서의 줄 번호(`L164` = 이 파일 156번째 줄)를 붙였다.
- 가설들은 서로 배타적이지 않다. 둘 이상이 함께 맞을 수 있고, H4 는 502 가 퍼진 범위만 설명한다.
- 확인 명령의 줄 범위 `17405,19805` 는 1.3 의 수집 구간이다. 경로는 저장소 루트 기준이다.
- 운영 DB · 운영 nginx 에 붙는 명령은 적지 않았다. 운영 값이 필요한 항목은 "담당자에게 요청"으로 적었다.

### H1. 애플리케이션 코드 계층

| 항목 | 내용 | 실행 결과 · 판정 |
|---|---|---|
| 가설 | `ReportService.buildClassReport` 가 트랜잭션을 연 채로 요청 1건마다 커넥션을 10초 가까이 쥐고 있어서, 리포트 요청 5건이 겹치면 풀(5개)이 전부 묶인다. | **유지 (문구 수정 필요)** — 14:37 ~ 14:51 의 리포트 134건이 모두 이 트랜잭션에서 커넥션을 16 ~ 22초 쥐었고, 그 시간이 slow log 로 설명되지 않는다(E1, E2, E4). 다만 같은 프로세스에서 그 밖 시간대 리포트 56건은 누수 경고 없이 끝났으므로 "요청 1건마다 항상"은 틀렸다(E3). 14:37:42 부터 무엇이 달라졌는지는 로그에 없다. |
| 지지 근거 | `app.log:1243`(L164) 누수 경고의 대상 커넥션이 5개뿐이다 · `app.log:1253 ~ 1254`(L165) 경고 스택의 애플리케이션 프레임이 `ReportController.classReport` → `ReportService.buildClassReport` 의 트랜잭션 시작 지점이다 · `app.log:1258`, `app.log:1259`(L166 ~ L167) 같은 스레드가 9.7초 뒤 리포트 생성 로그를 찍고 바로 커넥션을 반환한다 · 누수 경고 134건, 리포트 생성 로그 134건, 반환 로그 134건으로 수가 같다(L164, L166, L167) · `app.log:1521 ~ 1522`(L178) 타임아웃 49쌍이 모두 `total=5, active=5, idle=0` 이다 | E1: `triggered` 134건 = 반환 134건, 커넥션 ID 5개(24 ~ 29건씩), 반환 로그 바로 앞 줄은 134건 모두 `built class report` · E2: 경고 → 반환 간격 6 ~ 12초, 즉 쥔 시간 16 ~ 22초 |
| 반증 조건 | (a) `buildClassReport` 의 `@Transactional` 범위 안에 리포지토리 조회 말고 시간을 쓰는 코드(대기, 외부 호출, 반복 조회, 큰 계산)가 없다. (b) 커넥션을 쥔 시간이 전부 DB 쿼리 실행 시간으로 설명된다(H2 의 확인에서 쿼리가 실제로 그만큼 느린 것으로 나온다). (c) 같은 코드 · 같은 데이터 크기에서 리포트 1건이 10초(`leak-detection-threshold`)보다 훨씬 짧게 끝난다. | (a) 판단 불가 — 로그가 아니라 코드를 봐야 한다. (b) 성립하지 않음 — 구간 안 slow query 는 1건뿐이고, 그 1건도 쿼리가 끝난 뒤 9.7초를 더 쥐었다(E4). (c) 일부 성립 — 누수 경고 없는 리포트 56건(쥔 시간 10초 미만)이 있다(E3). 데이터 크기가 같았는지는 로그로 알 수 없다. |
| 확인 방법 | `modern/api/src/main/java/com/example/assignment/ReportService.java` 의 `buildClassReport` 와 `ReportController.java:22` 를 열어 트랜잭션 범위 안의 호출을 순서대로 적는다 · `git log --oneline -- modern/api/src/main/java/com/example/assignment/ReportService.java` 로 변경 이력을 본다 · 쥔 시간 분포: `grep -n 'ProxyLeakTask' incident-logs/a-connection-pool/app.log \| cut -c1-150` 에서 같은 커넥션 ID 의 `triggered` 시각과 `Previously reported` 시각 차이를 본다(차이 + 10초 = 쥔 시간) · 로컬 재현: `docker compose --profile modern up -d` 후 `cd modern/api && ./gradlew bootRun`, `curl -s -o /dev/null -w '%{time_total}\n' localhost:8080/api/classes/3/report` | 실행: E1 ~ E3(`app.log` 집계). 실행하지 않음: 코드 열람, `git log`, 로컬 재현(로그 확인이 아니다). **있었다면 판단할 수 있었던 로그**: 요청별 소요 시간(`app.log` 에 소요 시간 줄 0건, E3), 트랜잭션 안에서 실행된 SQL 과 각 실행 시간, 쥐고 있던 10여 초 동안의 스레드 덤프 |

### H2. DB · 쿼리 계층

| 항목 | 내용 | 실행 결과 · 판정 |
|---|---|---|
| 가설 | `submission` 을 `distribution_id` 로 찾는 리포트 쿼리가 운영 DB 에서 인덱스를 타지 못하고 테이블을 훑어서(4행을 돌려주려고 128만 행 검사) 쿼리마다 수 초가 걸리고, 그 시간만큼 커넥션이 묶인다. | **기각 (전제 1개)** — 커넥션을 10초 넘게 쥔 134건 중 slow log 에 대응하는 쿼리가 있는 것은 1건이다(E4). 전제: slow log 가 표본 추출 없이 전부 기록됐다. 이 전제는 로그로 확인하지 못했다. 그 1건이 왜 128만 행을 검사했는지는 설명되지 않은 채 남는다. |
| 지지 근거 | `mariadb-slow.log:15`, `mariadb-slow.log:18`(L162) 9.81초, `Rows_sent: 4`, `Rows_examined: 1284310`. 그날 앞선 2건은 2초대다 · `mariadb-slow.log:21`(L163) SQL 이 `submission … where distribution_id=5 order by student_id` 다 · `mariadb-slow.log:20`(L161) 쿼리 시작 14:37:42 가 구간의 첫 신호이고, 종료 14:37:52 가 `app.log:1243`(L164) 첫 누수 경고와 같은 초다 | E4: slow query 3건, `Query_time` 2.318446 · 2.041903 · 9.812417. 구간 안은 3번째 1건 |
| 반증 조건 | (a) 운영 `submission` 에 `distribution_id` 인덱스가 있고 `EXPLAIN` 이 그 인덱스를 쓴다(`type=ref`, `rows` 가 수십 행 수준). (b) slow log 기록 기준(`long_query_time`)이 2초 이하이고 표본 추출(`log_slow_rate_limit`)이 꺼져 있는데, 커넥션을 10초 넘게 쥔 134건에 대응하는 slow query 가 구간 안에 1건뿐이다(L213). 즉 나머지 133건은 쿼리가 느리지 않았다. (c) 운영 `submission` 전체 행 수가 128만에 못 미친다(한 번 훑은 것으로는 `Rows_examined` 가 설명되지 않는다). | (a) 판단 불가 — 운영 스키마 · 실행 계획은 로그에 없다. (b) 성립(전제부) — 2.04초짜리가 기록돼 있으므로 기록 기준은 2.04초 이하인데, 134건에 대응하는 것은 1건이다. `log_slow_rate_limit` 은 slow log 에 설정값 줄이 0건이라 확인하지 못했다(E4). (c) 판단 불가 — 행 수는 로그에 없다. |
| 확인 방법 | 저장소 스키마: `sed -n '105,115p' db/mariadb/init/01-schema.sql` · 쿼리 모양: `modern/api/src/main/java/com/example/assignment/SubmissionRepository.java` 와 `Submission.java` · 구간 안 slow query 수: `grep -c '^# Time:' incident-logs/a-connection-pool/mariadb-slow.log`, 앞선 2건의 기준 확인은 `sed -n '1,14p' incident-logs/a-connection-pool/mariadb-slow.log` · 로컬 실행 계획: `docker compose exec mariadb mariadb -uroot -p[SECRET] itembank -e "EXPLAIN select * from submission where distribution_id=5 order by student_id; SELECT COUNT(*) FROM submission;"` (로컬 데이터는 운영과 크기가 다르다) · 운영 값은 DBA 에게 요청: `SHOW INDEX FROM submission`, 같은 `EXPLAIN`, `SHOW VARIABLES LIKE 'long_query_time'`, `SHOW VARIABLES LIKE 'log_slow_rate_limit'`, 9월 16일 14:37 전후의 `submission` 행 수 | 실행: E4(`mariadb-slow.log` 집계, `app.log` 로거 집계). 실행하지 않음: 스키마 · 리포지토리 열람, 로컬 `EXPLAIN`, DBA 요청. **있었다면 판단할 수 있었던 로그**: slow log 의 기준값(`long_query_time`, `log_slow_rate_limit`), 14:37 ~ 14:52 의 general log 또는 애플리케이션 SQL 로그(쿼리별 실행 시간), 운영 `SHOW INDEX` · `EXPLAIN` 결과 |

### H3. 트래픽 · 클라이언트 계층

| 항목 | 내용 | 실행 결과 · 판정 |
|---|---|---|
| 가설 | 14:38 부터 평소 쓰지 않던 클라이언트(Safari, `교사단말D` 등)가 학급 리포트를 짧은 간격으로 반복 요청해서(자동 새로 고침 또는 실패 시 재시도), 동시에 처리 중인 리포트 요청 수가 풀 크기 5를 넘었다. | **기각 (느려진 출발 원인으로서)** — 리포트 요청이 1건씩 차례로만 처리되던 14:37:42 ~ 14:38:57 에 이미 3건 모두 누수 경고가 났다(E6). 요청 증가 자체는 사실이고(14시대 179건, 다른 시간대 2 ~ 11건, E5) 풀 5개를 다 묶는 데 필요한 조건이므로 **증폭 요인으로는 남긴다.** 반복 요청이 자동인지 사람이 누른 것인지는 판단 불가. |
| 지지 근거 | `nginx-access.log:17914`(L159) 14:18:13 이후 19분 48초 동안 리포트 요청 0건 · `nginx-access.log:18801`(L168) 리포트 요청이 분당 3 → 10 → 16 → 19건으로 늘고, 전체 요청 수는 그대로다 · `nginx-access.log:18801`(L169) Safari User-Agent 가 구간 안에서만 나온다(171건 중 69건) · `nginx-access.log:18881`, `nginx-access.log:18904`(L171) 리포트 요청 클라이언트가 1곳 → 3곳으로 늘어난다 · `nginx-access.log:18912`(L172) `교사단말D` 의 리포트 요청 29건이 구간 안에만 있다 · L212 늘어난 것은 리포트 경로뿐이다 | E5: 시간대별 리포트 요청 07 ~ 13시 2 ~ 11건, 14시 179건 · 구간 안 요청 클라이언트는 4곳(`교사단말B` 67건, `교사단말A` 40건, `교사단말C` 35건, `교사단말D` 29건) · User-Agent 는 Firefox 110건, Safari 69건 |
| 반증 조건 | (a) 리포트 요청이 1건만 처리 중이던 시점(14:37:42 ~ 14:38:01, 요청 클라이언트 1곳)에 이미 커넥션을 10초 넘게 쥐었다(`app.log:1243`). 그렇다면 요청 수 증가는 출발점이 아니라 느려진 뒤의 결과(재시도)일 수 있다. (b) 클라이언트별 요청 간격이 일정하지 않고, 같은 클라이언트의 요청이 직전 요청의 504 · 502 뒤에만 몰린다(사람이 다시 누른 모양). (c) 평소(13시대까지 시간당 2 ~ 11건)와 같은 요청 수에서도 누수 경고가 난다. | (a) 성립 — 14:37:30 ~ 14:38:30 에 끝난 리포트 요청은 2건, 모두 `교사단말A` 이고 차례로 처리됐는데 둘 다 누수 경고가 났다(E6). (b) 성립하지 않음 — `교사단말D` 의 요청 간격 28개 중 22개가 15 ~ 25초이고, 직전 응답이 200 이든 504 든 같다(E7). (c) 성립 — 14:38 은 분당 3건(클라이언트 1곳)인데 3건 모두 누수 경고가 났다(E6). |
| 확인 방법 | 클라이언트 · 분 · 상태별 건수: `sed -n '17405,19805p' incident-logs/a-connection-pool/nginx-access.log \| awk '$7 ~ /^\/api\/classes\/[0-9]+\/report/ {print substr($4,14,5), $1, $9}' \| sort \| uniq -c` · 한 클라이언트의 요청 간격: 위 명령의 `substr($4,14,5)` 를 `substr($4,14,8)` 로 바꾸고 `grep ' 교사단말D '` · User-Agent 별 건수: `sed -n '17405,19805p' incident-logs/a-connection-pool/nginx-access.log \| grep '/report HTTP' \| awk -F'"' '{print $6}' \| sort \| uniq -c` · 첫 누수 경고 때 처리 중이던 리포트 요청 수: `sed -n '1230,1260p' incident-logs/a-connection-pool/app.log \| cut -c1-160` · 리포트 화면은 이 저장소(`modern/web`)에 없다(Referer 가 `lms.example.com/teacher/…`). 그 화면의 자동 새로 고침 · 재시도 로직과 9월 16일 배포 여부는 LMS 프런트 담당자에게 요청 | 실행: E5 ~ E7(`nginx-access.log` · `app.log` 집계). 실행하지 않음: LMS 프런트 담당자 요청. **있었다면 판단할 수 있었던 로그**(자동 반복인지): 요청 도착 시각과 소요 시간(`$request_time`. 지금 access 로그는 combined 형식이라 없다, E7), 프런트의 요청 발생 로그 또는 화면 코드 |

### H4. 인프라(nginx upstream) 계층 — 502 가 퍼진 범위에 대한 가설

| 항목 | 내용 | 실행 결과 · 판정 |
|---|---|---|
| 가설 | 리포트 응답이 nginx 의 응답 대기 시간을 넘자 nginx 가 하나뿐인 정상 서버 `API-1` 을 일시 제외했고, 나머지 서버 `API-2` 는 연결을 거부하는 상태여서, 보낼 서버가 없는 동안 리포트가 아닌 경로까지 502 가 났다. | **유지** — 502 82건 중 50건이 리포트가 아닌 경로이고(E8), `no live upstreams` 68건이 모두 `temporarily disabled` 뒤 0 ~ 10초 안에 있으며, `temporarily disabled` 26건의 바로 앞 줄이 모두 `upstream timed out` 이다(E9). `API-2` 가 평소에 살아 있던 서버인지는 판단 불가. |
| 지지 근거 | `nginx-error.log:6`(L181) `API-1:8080` 에 대한 `upstream timed out` · `nginx-error.log:10`(L183) 같은 서버 `temporarily disabled` 26건 · `nginx-error.log:11`(L184) `API-2:8080` 에 `Connection refused` 14건, `temporarily disabled` 와 같은 초 · `nginx-error.log:12`(L186) `no live upstreams` 68건 · `nginx-access.log:18970`(L185) 502 합 82건으로 5xx 중 가장 많다 · `nginx-access.log:19347`(L192) 리포트가 아닌 `/api/units/…` 경로의 502 | E9: `upstream timed out` 55, `temporarily disabled` 26, `connect() failed` 14, `no live upstreams` 68. `API-2` 는 14줄(14:42:01 ~ 14:50:52)에만 나오고 전부 `connect() failed` 다 |
| 반증 조건 | (a) 502 가 리포트 경로에만 있다(L192 가 예외적인 1건이다). (b) `no live upstreams` 가 `temporarily disabled` 직후 구간(`fail_timeout`)에 몰려 있지 않고 고르게 흩어져 있다. (c) nginx 설정의 `max_fails` 가 0 이다(서버 제외 기능이 꺼져 있다). (d) `API-2` 가 평소에는 요청을 받던 서버이고 이 시각에만 내려가 있었다(그렇다면 별도 사건이다). | (a) 성립하지 않음 — 502 의 경로는 리포트 32건, 그 밖 50건(E8). 500 49건도 5개 경로에 퍼져 있고, 504 55건만 전부 리포트다. (b) 성립하지 않음 — 간격 분포가 0 ~ 10초에 모두 들어 있다(E9). (c) 성립하지 않음으로 본다 — 설정 파일은 없지만 `temporarily disabled` 가 26번 찍혔으므로 제외 기능은 켜져 있다. (d) 판단 불가 — `nginx-error.log` 는 09:12 부터인데 `API-2` 는 사고 구간에만 나온다. 평소에 요청을 받았는지는 알 수 없다. |
| 확인 방법 | 502 의 경로 분포(학생 식별자가 든 쿼리 문자열은 잘라 낸다): `awk '$9==502 {sub(/\?.*/,"",$7); print $7}' incident-logs/a-connection-pool/nginx-access.log \| sed -E 's#/[0-9]+(/\|$)#/{id}\1#' \| sort \| uniq -c` · 제외 → 보낼 서버 없음 순서: `grep -nE 'temporarily disabled\|no live upstreams\|connect\(\) failed' incident-logs/a-connection-pool/nginx-error.log \| cut -c1-90` · `API-2` 가 그날 다른 시각에 나오는지: `grep -c 'API-2' incident-logs/a-connection-pool/nginx-error.log` (access 로그에는 upstream 주소가 없다) · nginx 설정 파일은 이 저장소에 없다. 인프라 담당자에게 `upstream item_bank_api` 블록(`max_fails`, `fail_timeout`, `backup`)과 `proxy_read_timeout`, 9월 16일 `API-2` 의 상태를 요청 | 실행: E8 ~ E9(`nginx-access.log` · `nginx-error.log` 집계). 실행하지 않음: 인프라 담당자 요청. **있었다면 판단할 수 있었던 로그**((d) 에 대해): `$upstream_addr` 가 들어간 access 로그(평소 `API-2` 로 간 요청 수), `API-2` 서버의 애플리케이션 · 기동 로그, nginx `upstream` 설정 |

### 가설을 내지 않은 계층

| 계층 | 이유 |
|---|---|
| 배포 · 설정 변경(API 서버 쪽) | 타임라인에 지지하는 줄이 없다. L211 에서 재시작 · 재적재 · 배포 흔적을 네 파일 모두에서 찾지 못했다. 다만 네 로그는 프런트 배포를 기록하지 않으므로, 클라이언트 쪽 배포 여부는 H3 의 확인 방법에 넣었다. |
| 풀 설정값 자체(`maximum-pool-size: 5`, `connection-timeout: 3000`) | `modern/api/src/main/resources/application.yml:14 ~ 15` 의 값이 로그의 `total=5`, `3000ms` 와 같다. 이 값은 사고 전 8시간 동안 같은 값으로 오류 없이 돌았으므로(1.2 평소 수준) 단독 가설로 세우지 않았다. H1 ~ H3 이 맞을 때 고갈이 드러나는 조건으로만 본다. |

### 판정 요약

2026-10-06 에 로그로 확인할 수 있는 항목만 실행했다. 코드 열람 · 로컬 재현 · 담당자 요청은 실행하지 않았다.

| 가설 | 판정 | 이유 한 줄 |
|---|---|---|
| H1 애플리케이션 코드 | 유지 (문구 수정 필요) | 구간 안 리포트 134건 전부가 `buildClassReport` 트랜잭션에서 커넥션을 16 ~ 22초 쥐었고 slow log 로 설명되지 않는다. 다만 그 밖 56건은 10초 미만이어서 "항상"은 아니다 |
| H2 DB · 쿼리 | 기각 (전제: slow log 가 표본 추출 없이 기록됨) | 10초 넘게 쥔 134건 중 대응하는 slow query 가 1건뿐이다 |
| H3 트래픽 · 클라이언트 | 기각 (출발 원인으로서). 증폭 요인으로는 남김 | 리포트 요청이 1건씩 차례로 처리될 때 이미 누수 경고가 났다 |
| H4 인프라(nginx upstream) | 유지 | 502 82건 중 50건이 리포트 외 경로이고, `no live upstreams` 68건이 모두 서버 제외 뒤 10초 안에 있다 |

로그만으로는 답하지 못한 것

| 질문 | 있었다면 판단할 수 있었던 로그 |
|---|---|
| 14:18:13 까지는 10초 미만이던 리포트가 14:37:42 부터 왜 16 ~ 22초씩 커넥션을 쥐었는가 | 요청별 소요 시간, 트랜잭션 안 SQL 과 실행 시간, 스레드 덤프 |
| slow log 가 구간 안의 느린 쿼리를 전부 담고 있는가(H2 기각의 전제) | `long_query_time` · `log_slow_rate_limit` 값, 같은 시간대 general log |
| 14:37:42 의 쿼리 1건은 왜 4행을 돌려주려고 128만 행을 검사했는가 | 운영 `SHOW INDEX FROM submission`, `EXPLAIN`, 그 시각의 `submission` 행 수 |
| 15 ~ 25초 간격의 반복 요청은 화면이 자동으로 보낸 것인가, 사람이 다시 누른 것인가 | 요청 도착 시각 · 소요 시간(`$request_time`), 프런트 요청 로그 |
| `API-2` 는 평소에 요청을 받던 서버인가 | `$upstream_addr` 가 든 access 로그, 그 서버의 기동 로그 |

확인하다 알게 된 것

- `nginx-access.log` 의 시각은 응답 완료 시각으로 보인다. `nginx-access.log:18801` 이 14:38:01 인데, 같은 요청의 커넥션은 14:37:42 쯤 획득됐고(`app.log:1243` 경고 시각 − 10초) `app.log:1258` 의 완료가 14:38:01.900 이다. 2절 "읽는 법"의 미확정 항목에 대한 근거 1건이며, 2절은 고치지 않았다.

### 실행 기록

모든 명령은 저장소 루트에서 `L=incident-logs/a-connection-pool` 을 둔 상태로 실행했다. 출력은 그대로 옮긴 뒤 마스킹 규칙(IP · 학생 식별자 등)만 적용했고, 긴 줄은 `cut` 으로 자른 것이다. 명령 안의 IP 도 별칭으로 바뀌어 있으므로 다시 실행하려면 원본 로그의 값을 넣어야 한다.

**E1. 누수 경고 · 반환 건수와 대상 커넥션 (H1)**

```
$ grep -c 'Connection leak detection triggered' $L/app.log
134
$ grep -c 'Previously reported leaked connection' $L/app.log
134
$ grep 'Connection leak detection triggered' $L/app.log | grep -o 'Connection@[0-9a-f]*' | sort | uniq -c
     29 Connection@2ac19f07
     26 Connection@3f6c2a91
     26 Connection@5b0e9e0c
     29 Connection@6e8f14d3
     24 Connection@71d4b8e2
$ grep -B1 'Previously reported leaked connection' $L/app.log | grep -v 'Previously reported' | grep -v '^--' | awk '{print $9, $10, $11, $12}' | cut -c1-70 | sort | uniq -c
    134 built class report for
$ grep 'leak detection triggered' $L/app.log | sed -n '1p;$p' | cut -c1-29
2026-09-16T14:37:52.199+09:00
2026-09-16T14:51:37.674+09:00
```

**E2. 경고 → 반환 간격(초) 분포 (H1)**

경고는 획득 10초 뒤(`application.yml:18` 의 `leak-detection-threshold: 10000`)에 찍히므로 쥔 시간은 이 값 + 10초다. 왼쪽이 건수, 오른쪽이 초(반올림)다.

```
$ grep 'ProxyLeakTask' $L/app.log | awk '{ts=$1; match($0,/Connection@[0-9a-f]+/); id=substr($0,RSTART,RLENGTH); split(substr(ts,12,12),t,":"); s=t[1]*3600+t[2]*60+t[3]; if($0 ~ /triggered/){st[id]=s} else if(id in st){d=s-st[id]; printf "%.0f\n", d; delete st[id]}}' | sort -n | uniq -c
      8 6
     20 7
     25 8
     26 9
     20 10
     34 11
      1 12
```

**E3. 리포트 생성 로그의 시간대별 건수, 소요 시간 로그 유무 (H1)**

```
$ grep -c 'built class report' $L/app.log
190
$ grep 'built class report' $L/app.log | cut -c12-13 | sort | uniq -c | tr '\n' ' '
      2 07       5 08       2 09      10 10       7 11      11 12       7 13     142 14       4 15
$ grep 'built class report' $L/app.log | awk '{t=substr($1,12,5); if(t>="14:37"&&t<="14:51")c++} END{print c}'
134
$ grep -iE 'took [0-9]|elapsed|duration|[0-9]+ ?ms\b' $L/app.log | sed 's/^.*\] //' | cut -c1-60 | sed -E 's/[0-9a-f]{6,}//g; s/waiting=[0-9]+/waiting=N/' | sort | uniq -c
     49 Caused by: java.sql.SQLTransientConnectionException: itemban
     49 [n/a]
     49 o.h.engine.jdbc.spi.SqlExceptionHelper   : itembank-pool - C
```

- 190건 중 14:37 ~ 14:51 의 134건은 누수 경고 134건과 수가 같다. 나머지 56건(190 − 134)은 누수 경고가 없다.
- `ms` 가 든 줄은 커넥션 획득 타임아웃(`3000ms`) 49건의 세 줄뿐이다. 요청 소요 시간을 적은 줄은 없다.

**E4. slow query 건수 · 기준값 표기 · `app.log` 의 SQL 로그 유무 (H2, H1 반증 조건 b)**

```
$ grep -c '^# Time:' $L/mariadb-slow.log
3
$ grep -n '^# Query_time' $L/mariadb-slow.log
4:# Query_time: 2.318446  Lock_time: 0.000071  Rows_sent: 7  Rows_examined: 41822
11:# Query_time: 2.041903  Lock_time: 0.912004  Rows_sent: 5  Rows_examined: 5
18:# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310
$ grep -n -i 'long_query_time\|rate_limit\|started with' $L/mariadb-slow.log | head
(출력 없음: 0건)
$ grep -E '^2026-' $L/app.log | awk '{for(i=1;i<=NF;i++) if($i==":"){print $(i-1); break}}' | sort | uniq -c | sort -rn
   1342 c.example.common.GlobalExceptionHandler
    268 com.zaxxer.hikari.pool.ProxyLeakTask
    190 com.example.assignment.ReportService
     98 o.h.engine.jdbc.spi.SqlExceptionHelper
     29 c.e.assignment.DistributionService
```

- 3번째 쿼리는 14:37:42 에 시작해 14:37:52 에 끝났다(1.1). 같은 요청의 커넥션은 14:38:01.902 에 반환됐다(`app.log:1259`). 쿼리가 끝난 뒤 9.7초를 더 쥐었다.
- `app.log` 에는 SQL 문이나 쿼리 실행 시간을 찍는 로거가 없다(`SqlExceptionHelper` 98줄은 커넥션 획득 타임아웃 49쌍이다).

**E5. 리포트 요청의 시간대 · 클라이언트 · User-Agent 분포 (H3)**

`R` 은 리포트 경로 조건이다: `R='$7 ~ /^\/api\/classes\/[0-9]+\/report/'`

```
$ awk "$R"' {print substr($4,14,2)}' $L/nginx-access.log | sort | uniq -c
      2 07
      5 08
      2 09
     11 10
      7 11
     11 12
      8 13
    179 14
      4 15
$ sed -n '17405,19805p' $L/nginx-access.log | awk "$R"' {t=substr($4,14,5); if(t>="14:37"&&t<="14:51") print $1, $9}' | sort | uniq -c
     27 교사단말B 200
      3 교사단말B 500
     14 교사단말B 502
     23 교사단말B 504
     22 교사단말A 200
      1 교사단말A 500
      8 교사단말A 502
      9 교사단말A 504
     19 교사단말C 200
      8 교사단말C 502
      8 교사단말C 504
     11 교사단말D 200
      1 교사단말D 500
      2 교사단말D 502
     15 교사단말D 504
$ sed -n '17405,19805p' $L/nginx-access.log | grep '/report HTTP' | awk -F'"' '{print $6}' | sed -E 's/.*(Firefox\/[0-9.]+|Version\/[0-9.]+ Safari\/[0-9.]+).*/\1/' | sort | uniq -c
    110 Firefox/130.0
     69 Version/17.6 Safari/605.1.15
```

**E6. 요청이 늘기 전에 이미 누수 경고가 났는가 (H3 반증 조건 a, c)**

```
$ sed -n '17405,19805p' $L/nginx-access.log | awk "$R"' {print substr($4,14,5), $1, $9}' | sort | uniq -c | awk '$2>="14:37" && $2<="14:41"'
      3 14:38 교사단말A 200
      1 14:39 교사단말B 200
      2 14:39 교사단말A 200
      5 14:40 교사단말B 200
      2 14:40 교사단말A 200
      2 14:40 교사단말C 200
      1 14:40 교사단말D 200
      5 14:41 교사단말B 200
      1 14:41 교사단말B 500
      1 14:41 교사단말A 200
      2 14:41 교사단말A 504
      3 14:41 교사단말C 200
      3 14:41 교사단말D 200
      1 14:41 교사단말D 504
$ awk "$R"' {t=substr($4,14,8); if(t>="14:37:30"&&t<="14:38:30") print NR, t, $1, $9}' $L/nginx-access.log
18801 14:38:01 교사단말A 200
18815 14:38:27 교사단말A 200
$ sed -n '1230,1300p' $L/app.log | grep -n 'built class report\|ProxyLeakTask' | cut -c1-140
14:2026-09-16T14:37:52.199+09:00  WARN 1 --- [item-bank-api] [ool housekeeper] com.zaxxer.hikari.pool.ProxyLeakTask     : Connection leak de
29:2026-09-16T14:38:01.900+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-6] com.example.assignment.ReportService     : built class report
30:2026-09-16T14:38:01.902+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-6] com.zaxxer.hikari.pool.ProxyLeakTask     : Previously reporte
32:2026-09-16T14:38:20.016+09:00  WARN 1 --- [item-bank-api] [ool housekeeper] com.zaxxer.hikari.pool.ProxyLeakTask     : Connection leak de
47:2026-09-16T14:38:27.072+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-3] com.example.assignment.ReportService     : built class report
48:2026-09-16T14:38:27.074+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-3] com.zaxxer.hikari.pool.ProxyLeakTask     : Previously reporte
51:2026-09-16T14:38:51.641+09:00  WARN 1 --- [item-bank-api] [ool housekeeper] com.zaxxer.hikari.pool.ProxyLeakTask     : Connection leak de
66:2026-09-16T14:38:57.712+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-6] com.example.assignment.ReportService     : built class report
67:2026-09-16T14:38:57.714+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-6] com.zaxxer.hikari.pool.ProxyLeakTask     : Previously reporte
70:2026-09-16T14:39:18.720+09:00  WARN 1 --- [item-bank-api] [ool housekeeper] com.zaxxer.hikari.pool.ProxyLeakTask     : Connection leak de
```

- 14:38 의 3건은 경고(획득 + 10초) 14:37:52 → 반환 14:38:01, 경고 14:38:20 → 반환 14:38:27, 경고 14:38:51 → 반환 14:38:57 이다. 앞 요청이 커넥션을 반환한 뒤에 다음 요청이 획득했으므로 겹치지 않는다.

**E7. 한 클라이언트의 요청 간격, access 로그의 응답 시간 필드 유무 (H3 반증 조건 b)**

```
$ awk '$7 ~ /^\/api\/classes\/[0-9]+\/report/ && $1=="교사단말D" {split(substr($4,14,8),t,":"); s=t[1]*3600+t[2]*60+t[3]; if(p) printf "%d(%s) ", s-p, st; p=s; st=$9}' $L/nginx-access.log
20(200) 23(200) 15(504) 18(200) 21(200) 2(504) 41(502) 18(504) 1(500) 35(504) 1(504) 37(502) 19(504) 15(200) 20(504) 21(504) 17(200) 21(504) 22(504) 17(504) 18(200) 17(504) 25(200) 23(504) 21(504) 22(200) 19(504) 21(200)
$ awk -F'"' '{print NF}' $L/nginx-access.log | sort | uniq -c
  21086 7
```

- 숫자는 직전 요청과의 간격(초), 괄호는 직전 요청의 상태 코드다. 28개 중 22개가 15 ~ 25초이고, 나머지 6개는 1 · 1 · 2초와 35 · 37 · 41초다.
- 21,086줄 모두 큰따옴표로 나눈 필드가 7개(combined 형식)다. 응답 시간 필드가 없어 요청 도착 시각을 알 수 없다.

**E8. 5xx 의 경로 분포 (H4 반증 조건 a)**

```
$ awk '$9==502 {sub(/\?.*/,"",$7); print $7}' $L/nginx-access.log | sed -E 's#/[0-9]+(/|$)#/{id}\1#; s#/units/[A-Z0-9-]+#/units/{code}#' | sort | uniq -c
      2 /
     32 /api/classes/{id}/report
      9 /api/distributions/{id}
      1 /api/item/{id}
     13 /api/items/{id}
     12 /api/units
     12 /api/units/{code}/items
      1 /robots.txt
$ (같은 명령, $9==500)
      5 /api/classes/{id}/report
      4 /api/distributions/{id}
      7 /api/items/{id}
     14 /api/units
     19 /api/units/{code}/items
$ (같은 명령, $9==504)
     55 /api/classes/{id}/report
```

**E9. nginx upstream 오류의 건수와 순서 (H4 반증 조건 b, c, d)**

```
$ for p in 'upstream timed out' 'temporarily disabled' 'connect\(\) failed' 'no live upstreams'; do printf '%s: ' "$p"; grep -cE "$p" $L/nginx-error.log; done
upstream timed out: 55
temporarily disabled: 26
connect\(\) failed: 14
no live upstreams: 68
$ grep -c 'API-2' $L/nginx-error.log
14
$ grep -c 'API-1' $L/nginx-error.log
83
$ grep -n 'API-2' $L/nginx-error.log | sed -n '1p;$p' | cut -c1-60
11:2026/09/16 14:42:01 [error] 29#29: *225045 connect() fail
159:2026/09/16 14:50:52 [error] 29#29: *225778 connect() fai
$ grep -B1 'temporarily disabled' $L/nginx-error.log | grep -v 'temporarily disabled' | grep -v '^--' | grep -oE 'upstream timed out|connect\(\) failed|no live upstreams' | sort | uniq -c
     26 upstream timed out
$ grep -E 'temporarily disabled|no live upstreams' $L/nginx-error.log | awk '{split($2,t,":"); s=t[1]*3600+t[2]*60+t[3]; if($0 ~ /temporarily disabled/){last=s} else {print s-last}}' | sort -n | uniq -c
      1 0
      8 1
     11 2
      7 3
      6 4
     10 5
      4 6
      6 7
      7 8
      4 9
      4 10
```

- 마지막 표는 `no live upstreams` 줄마다 "직전 `temporarily disabled` 로부터 지난 초"를 센 것이다. 왼쪽이 건수, 오른쪽이 초이고 합은 68이다.

### 판정

"판정 요약"에서 유지된 H1 · H4 를 코드 · 설정과 대조했다(2026-10-06). 파일을 읽기만 했고 벤치마크 · 테스트 · 애플리케이션 실행은 하지 않았다. 설정값은 파일에 적힌 값만 옮겼다.

대조한 곳

| 단서(로그) | 찾아 읽은 곳 |
|---|---|
| `app.log:1253 ~ 1254` 스택의 `ReportController.classReport(ReportController.java:22)`, `ReportService.buildClassReport` | `modern/api/src/main/java/com/example/assignment/ReportController.java`, `ReportService.java` |
| `mariadb-slow.log:21` 의 `submission … where distribution_id=? order by student_id` | `SubmissionRepository.java`, `Submission.java`, `db/mariadb/init/01-schema.sql` |
| `app.log:1522` 의 `3000ms`, `total=5`, 풀 이름 `itembank-pool`, 누수 경고 | `modern/api/src/main/resources/application.yml` 의 `spring.datasource.hikari.*`, `spring.jpa.open-in-view` |
| `nginx-error.log:12` 의 upstream 이름 `item_bank_api`, `API-1` · `API-2` | 저장소 전체에서 nginx 설정 파일과 `proxy_read_timeout` · `max_fails` · `fail_timeout` · `item_bank_api` 를 검색 |

#### H1 — 코드 · 설정이 가설을 지지한다

| 근거 | 파일:줄 | 인용 |
|---|---|---|
| 리포트 생성 전체가 한 트랜잭션이다 | `ReportService.java:41 ~ 42` | `@Transactional` / `public ClassReport buildClassReport(Integer classId) {` |
| DB 조회는 메서드 앞부분에서 끝난다 | `ReportService.java:43`, `:46`, `:53 ~ 54` | `classRoomRepository.findById(classId)` · `distributionRepository.findByClassRoomIdOrderByDistributedAtAsc(classId)` · `submissionRepository.findByDistributionIdOrderByStudentIdAsc(distribution.getId())` |
| 조회가 끝난 뒤 트랜잭션 안에서 서명 계산을 한다 | `ReportService.java:69 ~ 71` | `// TODO: 트랜잭션 밖으로 — 외부 집계 시스템 호출을 흉내내는 긴 루프.` / `//       DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` / `String signature = sign(payload.toString());` |
| 서명은 SHA-256 을 20만 번 반복한다 | `ReportService.java:23`, `:88 ~ 91` | `static final int SIGNATURE_ROUNDS = 200_000;` / `for (int round = 0; round < SIGNATURE_ROUNDS; round++) {` |
| 완료 로그는 서명 뒤, 메서드가 끝나기 직전에 찍힌다 | `ReportService.java:73` | `log.info("built class report for class {}: {} distributions, {} submissions",` |
| 호출부는 서비스 호출 한 줄이고, 스택의 줄 번호와 같다 | `ReportController.java:22` | `return reportService.buildClassReport(id);` |
| 풀 크기 · 대기 시간 · 누수 경고 기준 | `application.yml:14`, `:15`, `:18` | `maximum-pool-size: 5` · `connection-timeout: 3000` · `leak-detection-threshold: 10000` |
| 설정 파일의 주석 | `application.yml:12` | `# 풀이 작고 대기 시간이 짧아, 트랜잭션을 오래 쥐는 코드가 있으면 금방 고갈된다.` |
| 커넥션을 쥐는 범위는 요청 전체가 아니라 트랜잭션이다 | `application.yml:22` | `open-in-view: false` |

로그와 맞는 점

- 누수 경고 스택이 트랜잭션 시작에서 커넥션을 얻는 지점(`buildClassReport`)을 가리킨다(`app.log:1243` 스택). 코드에서 커넥션을 얻는 곳도 `:41` 의 `@Transactional` 뿐이다.
- 반환 로그 134건의 바로 앞 줄이 모두 `built class report` 다(E1). 코드에서도 `:73` 로그 다음에 메서드가 끝나며 트랜잭션이 닫힌다.
- slow query 가 끝난 뒤에도 9.7초를 더 쥐었다(E4). 코드에서 조회(`:54`) 뒤에 남은 일은 서명(`:71`)뿐이다.
- 타임아웃 메시지의 `3000ms`, `total=5`(`app.log:1522`)가 `application.yml:14 ~ 15` 의 값과 같다.

코드로 설명되지 않는 점

- 서명 루프가 실제로 몇 초 걸리는지는 코드만으로 알 수 없다(실행하지 않았다). 루프 횟수는 상수이고 입력 크기와도 무관하므로(`:88 ~ 91`), 코드만 보면 모든 요청이 비슷한 시간 동안 커넥션을 쥐어야 한다.
- 그런데 로그에서는 134건이 16 ~ 22초, 나머지 56건이 10초 미만이다(E2, E3). 14:37:42 부터 같은 코드가 왜 더 오래 걸렸는지는 코드에도 로그에도 없다. 그 시각 `API-1` 의 CPU 사용률 · GC 로그 · 스레드 덤프가 있으면 판단할 수 있다.
- 이 저장소의 `ReportService.java` 이력은 2026-09-22 의 초기 커밋 1건(`1260542`)뿐이고 사고일(2026-09-16)보다 뒤다. 사고 당시 배포본이 이 코드와 같다는 것은 스택의 줄 번호(`ReportController.java:22`) 1곳이 맞는다는 것 말고는 확인하지 못했다.

#### H4 — 관련 코드를 찾지 못했다

| 찾은 것 | 방법 | 결과 |
|---|---|---|
| nginx 설정 파일 | 저장소 전체(`node_modules`, `.git`, `build` 제외)에서 이름에 `nginx` 가 들었거나 `.conf` 로 끝나는 파일 검색 | `incident-logs/` 의 로그 2개 말고는 0건 |
| upstream · 타임아웃 설정 키 | `proxy_read_timeout`, `max_fails`, `fail_timeout`, `item_bank_api` 를 저장소 전체에서 검색 | `incident-logs/a-connection-pool/nginx-error.log` 와 이 문서 2곳뿐 |
| 컨테이너 구성 | `docker-compose.yml` 의 서비스 목록 | `mariadb`, `item-bank`, `assignment`, `mssql`, `grade`. nginx 서비스 없음 |

`proxy_read_timeout`, `max_fails`, `fail_timeout` 의 값과 `API-2` 의 역할(`backup` 여부)은 이 저장소에서 읽을 수 없어 적지 않았다. H4 는 로그 판정(유지) 그대로 두고, 코드 · 설정으로는 지지도 반박도 하지 못했다.

#### 기각한 가설에 대해 코드에서 함께 본 것

| 가설 | 본 곳 | 내용 |
|---|---|---|
| H2 | `SubmissionRepository.java:8`, `db/mariadb/init/01-schema.sql:112` | 쿼리는 `findByDistributionIdOrderByStudentIdAsc` 이고, 저장소 스키마에는 `KEY idx_submission_distribution (distribution_id)` 가 있다. 저장소 스키마 기준으로는 "인덱스가 없다"를 반박한다. 운영 DB 의 인덱스 상태는 확인하지 못했다 |
| H3 | `modern/web/src` 에서 `report` 검색 | 0건. 리포트 화면 코드는 이 저장소에 없다 |

#### 최종 판정

채택: **H1 (문구 수정)**

> `ReportService.buildClassReport` 가 DB 조회를 마친 뒤에도 `@Transactional` 범위 안에서 서명 계산(`sign`)을 하느라 커넥션을 반환하지 않는다. 리포트 1건이 커넥션을 16 ~ 22초 쥐는 동안 리포트 요청이 5건 겹치자 풀(`maximum-pool-size: 5`)이 전부 묶였고, 다른 경로의 요청이 3초(`connection-timeout: 3000`)를 기다리다 500 으로 실패했다.

확신 수준: **중간**

| | 이유 |
|---|---|
| 올리는 근거 | 로그의 스택(`app.log:1243`), 건수(누수 경고 134 = 반환 134 = 구간 안 리포트 134), 풀 상태(`active=5`), 코드의 트랜잭션 범위(`ReportService.java:41`, `:71`), 설정값(`application.yml:14 ~ 15`)이 서로 어긋나지 않는다. 코드 주석(`:69 ~ 70`)도 같은 문제를 적고 있다 |
| 낮추는 근거 | (1) 같은 코드가 14:37:42 전후로 왜 다른 시간 동안 커넥션을 쥐었는지 설명하지 못한다(56건은 10초 미만). 방아쇠가 된 조건이 따로 있을 수 있다. (2) 서명 루프의 실제 소요 시간을 재지 않았다. (3) 사고 당시 배포본과 이 저장소 코드가 같다는 것을 확인하지 못했다. (4) H2 기각이 slow log 가 전부 기록됐다는 전제에 기대고 있다 |

채택하지 않은 가설

| 가설 | 결론 | 사유 |
|---|---|---|
| H2 DB · 쿼리 | 기각 | 10초 넘게 쥔 134건 중 대응하는 slow query 가 1건뿐이고(E4), 그 1건도 쿼리 종료 뒤 9.7초를 더 쥐었다. 저장소 스키마에는 해당 인덱스가 있다(`01-schema.sql:112`). 전제: slow log 가 표본 추출 없이 기록됨(미확인) |
| H3 트래픽 · 클라이언트 | 기각 (출발 원인으로서) | 리포트 요청이 한 클라이언트에서 1건씩 차례로 처리될 때 이미 3건 모두 누수 경고가 났다(E6). 요청 증가(14시대 179건)는 풀 5개가 다 묶이는 데 필요했던 증폭 요인으로 기록한다 |
| H4 인프라(nginx upstream) | 근본 원인으로 채택하지 않음 (기각 아님) | 풀 고갈이 아니라 502 82건이 리포트 외 경로로 퍼진 경로를 설명하는 가설이다. 로그로는 유지되지만(E8, E9) 설정 파일이 저장소에 없어 대조하지 못했다. H1 의 결과가 커진 2차 경로로 따로 다룬다 |

확신 수준을 올리려면 필요한 것

- 14:37 ~ 14:52 의 `API-1` CPU 사용률 · GC 로그(왜 그때부터 16 ~ 22초였는가)
- 사고 당시 배포된 `item-bank-api` 의 버전(커밋 또는 이미지 태그)
- DBA: `long_query_time`, `log_slow_rate_limit`, 운영 `submission` 의 인덱스
- 인프라: nginx `upstream item_bank_api` 블록과 `proxy_read_timeout`, `API-2` 의 9월 16일 상태

## 4. 원인

3절에서 채택한 H1 을 일어난 순서대로 적는다. 문장 번호는 5절의 근거와 짝을 이룬다.

### 4.1 직접 원인

- **C1.** `buildClassReport` 는 메서드 전체가 한 트랜잭션이고, DB 조회가 끝난 뒤에도 그 안에서 서명 계산(SHA-256 20만 번 반복)을 한다. 그동안 커넥션은 반환되지 않는다.
- **C2.** 14:37:42 에 19분 48초 만에 리포트 요청이 다시 들어왔고, 이 요청은 쿼리 9.81초가 끝난 뒤에도 9.7초를 더 쥐어 14:38:01 에야 커넥션을 반환했다.
- **C3.** 그 뒤 14:51 까지 리포트 134건이 모두 커넥션을 16 ~ 22초씩 쥐었다. 대상 커넥션은 풀의 5개 전부다.
- **C4.** 같은 시간에 리포트 요청이 분당 3건에서 10 ~ 19건으로 늘었다. 클라이언트 4곳이 15 ~ 25초 간격으로 요청을 되풀이했다.
- **C5.** 14:40:52 부터 커넥션 5개가 모두 사용 중이 됐고, 리포트와 무관한 요청이 3초를 기다리다 실패해 500 이 49건 났다.
- **C6.** 리포트 응답 자체도 nginx 의 대기 시간을 넘겨 504 가 55건 났다(모두 리포트 경로).
- **C7.** nginx 가 `API-1` 을 일시 제외했고 `API-2` 는 연결을 거부해, 보낼 서버가 없는 동안 502 가 82건 났다(50건은 리포트가 아닌 경로).
- **C8.** 리포트 요청이 줄자(14:50 10건, 14:51 5건) 14:49:51 에 500 이, 14:51:47 에 5xx 전체가 멈췄다. 사람이 조치한 흔적은 로그에 없다.

### 4.2 배경 원인

| | 배경 | 직접 원인과의 관계 |
|---|---|---|
| B1 | 풀이 5개, 획득 대기가 3초다 | 리포트 5건만 겹쳐도 전체 API 가 멈춘다 |
| B2 | 리포트 동시 실행 수를 제한하는 장치가 없다 | 리포트 한 종류가 풀을 독점할 수 있다 |
| B3 | 클라이언트가 응답 성공 · 실패와 무관하게 요청을 되풀이했다 | 묶인 커넥션 수를 5개까지 끌어올렸다 |
| B4 | nginx 가 타임아웃이 쌓이면 서버를 제외하는데, 남은 서버가 요청을 받지 못했다 | 리포트의 지연이 모든 경로의 502 로 커졌다 |
| B5 | 누수 경고가 14:37:52 부터 났지만 경보로 이어지지 않았다 | 첫 오류까지 3분의 여유를 쓰지 못했다 |

방아쇠는 확인하지 못했다. 같은 코드로 그날 다른 리포트 56건은 커넥션을 10초 미만으로 쥐었다(9절).

## 5. 근거 로그 줄

| 문장 | 파일:줄번호 | 발췌 |
|---|---|---|
| C1 | `modern/api/src/main/java/com/example/assignment/ReportService.java:41`, `:71`, `:23` | `@Transactional` · `String signature = sign(payload.toString());` · `SIGNATURE_ROUNDS = 200_000` |
| C1 | `app.log:1243`, `app.log:1253 ~ 1254` | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3 …` · `at com.example.assignment.ReportController.classReport(ReportController.java:22)` |
| C2 | `nginx-access.log:17914` → `nginx-access.log:18801` | 14:18:13 `"GET /api/classes/1/report HTTP/1.1" 200 258` 이후 14:38:01 까지 리포트 요청 0건 |
| C2 | `mariadb-slow.log:18`, `mariadb-slow.log:20` | `# Query_time: 9.812417 … Rows_sent: 4  Rows_examined: 1284310` · `SET timestamp=1789537062;`(14:37:42) |
| C2 | `app.log:1258 ~ 1259` | 14:38:01.900 `built class report for class 3: …` · 14:38:01.902 `Previously reported leaked connection …@6e8f14d3 …` |
| C3 | `app.log:1243` ~ `app.log:5251` (3절 E1, E2) | 누수 경고 134건 = 반환 134건, 커넥션 ID 5개, 경고 → 반환 간격 6 ~ 12초 |
| C4 | `nginx-access.log:18801`, `:18881`, `:18904`, `:18912` (3절 E5, E7) | 분당 3 → 3 → 10 → 16 → 15 → 19건. `교사단말A` → `교사단말B` → `교사단말C` → `교사단말D` 순으로 추가 |
| C5 | `app.log:1521 ~ 1523` | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` · `unhandled exception on /api/units/M6-2/items` |
| C5 | `nginx-access.log:18918` | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| C5 | `modern/api/src/main/resources/application.yml:14 ~ 15` | `maximum-pool-size: 5` · `connection-timeout: 3000` |
| C6 | `nginx-error.log:6`, `nginx-access.log:18933` | `upstream timed out (110: Connection timed out) while reading response header…` · `"GET /api/classes/3/report HTTP/1.1" 504 167` |
| C7 | `nginx-error.log:10`, `:11`, `:12` | `upstream server temporarily disabled …` · `connect() failed (111: Connection refused) …` · `no live upstreams while connecting to upstream …` |
| C7 | `nginx-access.log:18970`, `nginx-access.log:19347` (3절 E8) | `"GET /api/classes/2/report HTTP/1.1" 502 157` · `"GET /api/units/M5-3/items?student=학생B HTTP/1.1" 502 157` |
| C8 | `app.log:4980 ~ 4981`, `nginx-access.log:19381`, `app.log:5266 ~ 5267` | 마지막 `unhandled exception on /api/units/M5-3/items` · 마지막 `504` · 마지막 커넥션 반환 |
| B5 | `app.log:1243` → `app.log:1522` | 첫 누수 경고 14:37:52.199, 첫 `ERROR` 14:40:52.319 |

## 6. 수정안

지금 적용할 수정은 코드 1곳이다. 제안이며 적용하지 않았다.

| 파일 | 무엇을 | 어떻게 |
|---|---|---|
| `modern/api/src/main/java/com/example/assignment/ReportService.java:41 ~ 82` | 서명 계산을 트랜잭션 밖으로 뺀다 | 조회 · 집계(`:43 ~ 67`)만 `@Transactional(readOnly = true)` 메서드로 묶어 집계 결과와 서명 대상 문자열을 돌려주게 하고, `buildClassReport` 는 트랜잭션 없이 그 메서드를 부른 뒤 `sign` (`:71`)을 실행한다 |
| 같은 패키지의 새 클래스(예: `ReportQueryService`) | 조회 메서드를 둘 곳 | 같은 클래스 안에서 부르면 `@Transactional` 이 적용되지 않으므로 별도 빈으로 둔다. Controller → Service → Repository 방향은 그대로다 |
| `modern/api/src/test/java/com/example/assignment/ReportServiceTest.java` | 바뀐 동작의 테스트 | 기존 3개(`buildsAggregatedReport` 등)는 결과가 같아야 한다. 새 조회 메서드의 테스트 1개와 "서명은 조회 메서드가 끝난 뒤 호출된다"를 확인하는 테스트 1개를 추가한다 |

- 응답 본문(`ClassReport`)과 서명 값은 바뀌지 않는다.
- `application.yml` 의 풀 크기 · 대기 시간은 운영 값에 맞춘 값이라 고치지 않는다(저장소 규칙).
- nginx 설정은 저장소에 없어 수정안을 쓰지 못했다(9절).

## 7. 재발 방지

| 장치 | 막는 것 | 방법 |
|---|---|---|
| 트랜잭션 범위 규칙 | 커넥션을 쥔 채 오래 걸리는 일을 하는 코드 | `CLAUDE.md` 3절의 "`@Transactional` 메서드 안에서 외부 HTTP 호출" 금지에 "반복 계산 · 서명 · 파일 처리"를 더하고, 커밋 전 컨벤션 점검 항목에 넣는다 |
| 리포트 동시 실행 상한 | 한 종류의 요청이 풀을 독점하는 것(B2) | 리포트 생성의 동시 실행 수를 풀 크기보다 작게 제한하고, 넘으면 기다리지 않고 거절한다. 거절 상태 코드는 `GlobalExceptionHandler` 표에 새 줄이 필요해 합의가 먼저다 |
| 클라이언트 재요청 간격 | 느려질수록 요청이 쌓이는 것(B3) | LMS 리포트 화면이 자동으로 다시 요청하는지 확인하고, 그렇다면 실패 시 간격을 늘리게 한다(담당: LMS 프런트) |
| upstream 제외 정책 재검토 | 한 경로의 지연이 전체 502 로 커지는 것(B4) | 정상 서버가 1대뿐일 때 그 서버를 제외하지 않도록 `upstream` 설정을 검토하고, `API-2` 를 기동하거나 목록에서 뺀다(담당: 인프라) |
| 배포 전 동시 요청 점검 | 같은 문제가 운영에서 처음 드러나는 것 | 커넥션을 쓰는 새 엔드포인트는 풀 크기(5)를 넘는 동시 요청으로 한 번 호출해 보고 누수 경고가 없는지 확인하는 단계를 배포 점검표에 넣는다 |

## 8. 모니터링 항목

"이번이라면" 은 2절 타임라인에 임계값을 대입한 시각이다. 첫 `ERROR` 는 14:40:52, 첫 5xx 도 14:40:52 였다.

| 지표 | 출처 | 임계값 제안 | 이번이라면 | 첫 오류 대비 |
|---|---|---|---|---|
| 누수 경고(`ProxyLeakTask` `WARN`) 건수 | `app.log` | 5분 동안 3건 이상 | 14:38:51 (3번째 경고) | 2분 1초 전 |
| 5초 넘는 slow query | `mariadb-slow.log` | 1건 이상 | 14:37:52 | 3분 전 |
| 리포트 응답 시간 | nginx access (`$request_time` 추가 필요) | 10초 넘는 응답 1분에 2건 이상 | 14:38 분 (추정: 14:38:01 · 14:38:27 완료분이 각각 약 20초 · 17초) | 약 2분 전 |
| 풀 사용 중 커넥션 수 | Hikari 지표(수집 수단 추가 필요) | 5개 중 5개가 30초 이상 | 14:40 분대 (추정: 로그에 풀 지표가 없다) | 판단 불가 |
| 커넥션 획득 타임아웃 `ERROR` | `app.log` | 1건 이상 | 14:40:52 | 0초 |
| 5xx 비율 | `nginx-access.log` | 1분 요청의 5% 이상 | 14:42:00 (14:41 분: 42건 중 9건) | 1분 8초 뒤 |
| `upstream timed out` · `no live upstreams` | `nginx-error.log` | 각각 1건 이상 | 14:41:06 · 14:42:04 | 14초 뒤 · 1분 12초 뒤 |

- 앞의 세 지표가 오류보다 먼저 울린다. 평소 값은 누수 경고 0건, slow query 최대 2.3초다(1.2, 1.1).
- Hikari 지표를 내보내려면 `modern/api/build.gradle` 에 의존성 추가가 필요하다. 추가 전에 승인을 받는다.

## 9. 확인하지 못한 것

| 판단하지 못한 것 | 이유 | 다음을 위해 남길 로그 |
|---|---|---|
| 14:37:42 부터 리포트가 왜 16 ~ 22초씩 걸렸는가(그날 다른 56건은 10초 미만) | 요청 소요 시간과 서버 자원 기록이 없다 | 요청별 소요 시간 로그, `API-1` 의 CPU 사용률 · GC 로그 |
| 서명 계산이 실제로 몇 초 걸리는가 | 재지 않았다(별도 실행 없음) | 서명 구간의 소요 시간 로그 |
| 사고 당시 배포본이 저장소 코드와 같은가 | 저장소 이력이 사고일 뒤(2026-09-22)에 시작한다 | 기동 시 버전(커밋) 로그, 배포 이력 |
| slow log 가 느린 쿼리를 전부 담았는가(H2 기각의 전제) | 기준값이 로그에 없다 | `long_query_time` · `log_slow_rate_limit` 값 |
| 14:37:42 의 쿼리가 왜 4행에 128만 행을 검사했는가 | 운영 스키마 · 실행 계획이 없다 | 운영 `SHOW INDEX FROM submission`, `EXPLAIN` |
| 반복 요청이 자동인가, 사람이 다시 누른 것인가 | 요청 도착 시각이 없다 | nginx `$request_time`, 프런트 요청 로그 |
| nginx 의 대기 시간 · 서버 제외 기준, `API-2` 의 평소 상태 | 설정 파일이 저장소에 없다 | `upstream` 설정, access 로그의 `$upstream_addr` |
| 리포트 요청이 14:51 에 왜 줄었는가 | 로그에 조치 흔적이 없다 | 장애 대응 기록(누가 언제 무엇을 했는지) |

따로 처리할 것(이번 장애의 원인은 아니다)

- `app.log` 의 재배포 로그 `reason=` 값에 학생 식별자와 이메일이 찍힌다(`app.log:1182` 등). 저장소 로그 규칙 위반이다.
- `nginx-error.log:2` 에 `Authorization: [TOKEN]` 토큰 일부가 찍혀 있다.

---

마스킹 적용: IP 주소 82건 · 이메일 0건 · 학생 식별자 3건 · 토큰 · Authorization 값 2건 · 비밀번호 · 접속 문자열 1건
