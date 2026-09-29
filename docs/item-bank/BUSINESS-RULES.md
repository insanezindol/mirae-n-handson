# legacy/item-bank-php 비즈니스 규칙 후보

> 선행 문서: [ARCHITECTURE.md](./ARCHITECTURE.md) (진입점 · 의존 관계), [ERD.md](./ERD.md) (테이블 · 읽기/쓰기 위치)
> 분석 범위: `legacy/item-bank-php/` 의 `search.php`(1~731행 전체), `register.php`, `units.php`, `inc/db.php`,
> 그리고 이 모듈이 조회하는 뷰 정의 `db/mariadb/init/01-schema.sql`.
> 근거 표기: 선행 문서와 같이 모듈 안 파일은 `legacy/item-bank-php/` 기준 상대 경로(`search.php:215`),
> 모듈 밖 파일은 저장소 루트 기준 경로(`db/mariadb/init/01-schema.sql:70`)로 적는다.
> 코드를 읽어서 뽑은 것이며, 실제로 실행해 확인하지는 않았다.

확신도
- **확실**: 조건문 · SQL 로 동작을 확인했다.
- **추정**: 이름 · 주석 · 스키마 설정으로 짐작했다. 실행으로 확인하지 않았다.

## 0. 먼저 알아 둘 것

- **문항 "수정" 기능은 모듈 안에 없다.** `item` 에 대한 `UPDATE` 문이 없다(ERD.md §3.2). 그래서 "수정 시 검증" 규칙은 뽑을 것이 없다. 등록 검증만 적는다.
- 검색 화면의 공개 문항 필터(`status = 'A'`)는 PHP 코드가 아니라 뷰 정의에 있다(`db/mariadb/init/01-schema.sql:70`).
- 검색 화면은 입력값이 이상해도 **오류로 막지 않고 경고만 남긴 뒤 조회를 그대로 한다**. 등록 화면은 반대로 오류가 하나라도 있으면 저장하지 않는다. 이 차이는 규칙마다 비고에 적었다.

## 1. 문항 검색 — 조건 조합 (`search.php`)

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-01 | 키워드 · 단원 · 난이도 · 태그 조건이 여러 개 주어지면 모두 `AND` 로 묶어 전부 만족하는 문항만 찾는다. | `search.php:44`, `:98`, `:118`, `:215`, `:218`, `:244` | `$where    = " WHERE 1=1";`<br>`$where .= " AND unit_code = ?";` | 확실 | OR 조합 · 여러 값(예: 태그 2개) 조합은 없다 |
| BR-02 | 검색 결과에는 상태가 `'A'` 인 문항만 나온다. | `search.php:522`, `:526`, `db/mariadb/init/01-schema.sql:52`, `:68-70` | `$from   = " FROM v_item_public";`<br>`FROM item i`<br>`WHERE i.status = 'A';` | 확실 | 필터가 뷰에만 있다. 같은 "공개만" 규칙이 `units.php:17` 에는 SQL 조건으로 직접 들어가 있다(BR-26). 뷰 주석은 "등록 화면도 이 뷰를 기준으로 삼는다"(`01-schema.sql:51`)지만 `register.php` 는 뷰를 쓰지 않는다 |
| BR-03 | 검색 파라미터가 배열(`?q[]=a&q[]=b`)로 들어오면 첫 값만 쓴다. | `search.php:55`, `:59`, `:63`, `:67`, `:71`, `:75`, `:79` | `$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];` | 확실 | 7개 파라미터 모두 같은 방식 |
| BR-04 | 키워드가 앞뒤 공백을 뺀 뒤 비어 있지 않으면, 제목 또는 지문에 키워드가 포함된 문항을 찾는다. | `search.php:85`, `:90`, `:97-98` | `$like = '%' . $q . '%';`<br>`$where .= " AND (title LIKE ? OR stem LIKE ?)";` | 확실 | |
| BR-05 | 키워드가 100자를 넘으면 앞 100자만 쓰고 경고를 띄운다. | `search.php:86-88`, `:494` | `if (mb_strlen($q, 'UTF-8') > 100) {`<br>`    $q = mb_substr($q, 0, 100, 'UTF-8');` | 확실 | 매직 넘버 100. 폼 입력칸 `maxlength="100"`(`:494`)과 서버 자르기 두 곳에 같은 값이 따로 적혀 있다 |
| BR-06 | 키워드에 `%` · `_` 가 있으면 이스케이프하지 않고 LIKE 와일드카드로 그대로 쓰고, 경고만 띄운다. | `search.php:94-97` | `if (strpos($q, '%') !== false \|\| strpos($q, '_') !== false) {`<br>`    $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';` | 확실 | 예: `q=%` 는 모든 문항과 일치 |
| BR-07 | 키워드가 한 글자이면 경고만 띄우고 검색은 그대로 한다. | `search.php:91-92` | `if (mb_strlen($q, 'UTF-8') === 1) {` | 확실 | 최소 길이 제한이 아니다 |
| BR-08 | 단원 코드가 앞뒤 공백을 뺀 뒤 비어 있지 않으면, 뷰의 `unit_code` 와 `=` 로 일치하는 문항만 찾는다. | `search.php:109-120` | `$where .= " AND unit_code = ?";` | 확실 | PHP 는 대소문자를 바꾸지 않고 입력값 그대로 바인딩한다(`:115-120`). 다만 비교 대상 `unit.code` 의 콜레이션이 `utf8mb4_unicode_ci`(`01-schema.sql:5`, `:18`)이고 연결 문자셋도 `utf8mb4`(`inc/db.php:27`)라서 DB 비교는 대소문자를 가리지 않는다 → 소문자 `m5-1` 도 `M5-1` 과 일치한다(BR-41) — **추정**(스키마 근거), 실행 미확인 |
| BR-09 | 단원 코드가 형식(`영문1 + 숫자1~2 + "-" + 숫자1~2`)에 맞지 않거나, 소문자가 섞였거나, `unit` 테이블에 없으면 경고만 띄우고 조회는 그대로 한다. | `search.php:111-117`, `:125`, `:141-142` | `if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {`<br>`    // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)` | 확실 | 정규식 속 `{1,2}` 가 매직 넘버. 형식 규칙은 이 경고에만 있고 DB 제약은 없다(`unit.code VARCHAR(16)`, `01-schema.sql:13`) |
| BR-41 | 단원 코드를 소문자로 넣으면(예: `m5-1`) "대문자로 입력하세요" 경고만 뜨고, 대문자 코드(`M5-1`) 단원의 문항이 결과로 나온다. 단원 이름 조회도 성공하므로 "등록되지 않은 단원 코드" 경고는 뜨지 않는다. | `search.php:115-118`, `:125`, `:141-142`, `db/mariadb/init/01-schema.sql:5`, `:18`, `inc/db.php:27` | `if ($unit !== strtoupper($unit)) {`<br>`    $warnings[] = '단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)';`<br>`$stmt = $conn->prepare("SELECT name, grade FROM unit WHERE code = ?");`<br>`) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;` | 추정 | PHP 는 대소문자를 바꾸지 않는다. 대소문자를 가리지 않는 것은 `unit.code` 컬럼 콜레이션(`_ci`) 때문이다. 스키마로 판단했고 실행으로는 확인하지 않았다. 경고 문구 "입력값 그대로 조회합니다"는 사실이지만, 사용자는 결과가 0건일 것으로 읽을 수 있다 |
| BR-10 | **난이도 값이 비어 있으면 난이도 5 문항을 결과에서 뺀다**(`level < 5`). | `search.php:213-216` | `// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)`<br>`if ($level == '') {`<br>`    $where .= " AND level < 5";` | 확실 | **주석과 코드 불일치**: 주석은 "1~5 모두 포함", 코드는 5 제외. 헤더 주석도 "빈값 허용"(`:8`)이라고만 한다. 파라미터 없이 처음 들어와도 적용된다. 이 조건은 요약(`$summary`)에 들어가지 않아 화면에 "조건 없이 검색했습니다"(`:356`)가 나오는데도 5가 빠진다. 폼의 빈 값 라벨은 "전체"(`:192`). 매직 넘버 5 |
| BR-11 | 난이도가 `1`~`5` 한 자리 숫자이면 그 난이도와 같은 문항만 찾는다. | `search.php:217-221` | `else if (preg_match('/^[1-5]$/', $level)) {`<br>`    $where .= " AND level = ?";` | 확실 | 같은 정규식이 `register.php:70` 에도 있다(BR-33) |
| BR-12 | 난이도가 비어 있지 않고 `1`~`5` 한 자리도 아니면, 경고를 띄우고 값을 정수로 바꿔 `level = 정수` 로 찾는다. | `search.php:223-229` | `$warnings[] = '난이도는 1~5 사이여야 합니다.';`<br>`$values[] = (int)$level;` | 확실 | 주석은 "대개 0건"(`:224`). 코드상 `abc` → 0, `-1` → -1 이라 0건이지만, `05` → 5, `3.9` → 3, `5abc` → 5 처럼 **경고와 함께 실제 결과가 나오는 값이 있다**. 이 분기에서는 BR-10 의 `level < 5` 가 붙지 않는다(`:214` 의 `if` 와 배타). 조건 요약에는 변환된 정수가 나온다(`:229` `$summary[] = '난이도 ' . (int)$level;`). 등록 화면은 같은 경우를 오류로 막는다(BR-33) |
| BR-13 | 태그 이름이 앞뒤 공백을 뺀 뒤 비어 있지 않으면, 그 이름과 정확히 같은 태그가 붙은 문항만 찾는다. | `search.php:235-236`, `:244-247` | `$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"`<br>`        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";` | 확실 | 태그는 id 가 아니라 이름으로 거른다. 한 번에 태그 하나만 받는다(BR-03). 대소문자는 BR-08 과 같은 콜레이션 영향 — 추정 |
| BR-14 | 태그 이름이 50자를 넘으면 앞 50자만 쓰고 경고를 띄운다. `%` · `_` 가 있으면 "부분 일치 미지원" 경고만 띄우고 `=` 비교를 그대로 한다. | `search.php:237-242` | `if (mb_strlen($tag, 'UTF-8') > 50) {`<br>`    $tag = mb_substr($tag, 0, 50, 'UTF-8');` | 확실 | 매직 넘버 50 = `tag.name VARCHAR(50)`(`01-schema.sql:37`)과 같은 값 |
| BR-15 | 태그 이름이 `tag` 테이블에 없으면 경고만 띄우고 조회는 그대로 한다(0건). | `search.php:250-260` | `// 등록된 태그인지 확인 (없어도 조회는 그대로 한다 → 0건)`<br>`if (!$tagKnown) {` | 확실 | |

## 2. 검색 목록 — 정렬 · 페이지 · 표시 (`search.php`)

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-16 | 정렬 기준이 비어 있으면 난이도 내림차순, 같은 난이도면 번호(id) 오름차순으로 정렬한다. | `search.php:317-320` | `case '':`<br>`    // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순`<br>`    $orderBy = " ORDER BY level DESC, id ASC";` | 확실 | 기본 정렬이 "어려운 문항부터"인데 기본 검색은 가장 어려운 5를 뺀다(BR-10). 결과적으로 기본 화면 맨 위는 난이도 4 |
| BR-17 | 정렬 기준이 `id`·`title`·`unit`·`level`·`created` 가 아니면 경고를 띄우고 기본 정렬(BR-16)을 쓴다. 정렬 기준 · 방향은 대소문자를 가리지 않는다. | `search.php:266-267`, `:322-326` | `default:`<br>`    $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';`<br>`    $orderBy = " ORDER BY level DESC, id ASC";` | 확실 | 기본 정렬 SQL 문자열이 `:319` 와 `:325` 두 곳에 따로 적혀 있다 |
| BR-18 | 정렬 기준별 ORDER BY 는 아래 표와 같고, 2차 키 `id` 는 방향과 상관없이 항상 오름차순이다. | `search.php:276-315` | `$orderBy = " ORDER BY unit_code DESC, level DESC, id ASC";`<br>`$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";` | 확실 | `unit` 정렬의 2차 키 `level DESC` 는 방향과 상관없이 고정. 표는 아래 §2.1 |
| BR-19 | 정렬 방향이 `asc`·`desc` 가 아니면 무시하고(비어 있지 않았다면 경고), 기본 방향을 쓴다: `level`·`created` 는 내림차순, `id`·`title`·`unit` 은 오름차순. | `search.php:267-273`, `:278`, `:302`, `:310`, `:328-331` | `if ($dir !== 'asc' && $dir !== 'desc') {`<br>`    if ($dir !== '') {`<br>`        $warnings[] = '정렬 방향은 asc 또는 desc 만 가능합니다.';`<br>`if ($dir === 'asc') {`<br>`$dir = ($sort === 'level' \|\| $sort === 'created') ? 'desc' : 'asc';` | 확실 | 빈 값이면 경고가 없다. 공백만 있는 값도 `trim` 뒤 빈 값이 되어 경고가 없다(`:267`). **같은 규칙이 두 곳에 따로 구현**: SQL 방향은 `switch` 안의 `if` 로(`id`·`title`·`unit` 은 `=== 'desc'` 검사, `level`·`created` 는 `=== 'asc'` 검사), 화면 표시용 방향은 `:330` 에서 다시 정한다. 두 곳이 어긋나면 화면의 ▲▼ 와 실제 정렬이 달라진다 |
| BR-20 | 열 머리글 정렬 링크는 현재 정렬 열이면 반대 방향, 아니면 `asc` 로 걸리되 난이도 열만 첫 클릭이 `desc` 다. 등록일(`created`) 열 머리글 링크는 없다. | `search.php:392-413` | `} else if ($key === 'level') {`<br>`    // 난이도는 첫 클릭에 내림차순`<br>`    $nextDir = 'desc';` | 확실 | `created` 정렬은 URL 로만 쓸 수 있다(헤더 주석 `:10` 에는 있음) |
| BR-21 | 한 페이지는 20건이다. 페이지 번호가 숫자가 아니면 경고와 함께 1페이지, 1보다 작으면 **경고 없이** 1페이지, 999보다 크면 경고와 함께 999페이지로 바꾼다. | `search.php:77-80`, `:336-349`, `:523` | `if (preg_match('/^[0-9]+$/', $pageRaw)) {`<br>`} else if ($pageRaw !== '' && $pageRaw !== '1') {`<br>`if ($page < 1) {`<br>`    $page = 1;`<br>`if ($page > 999) {`<br>`    $page = 999;`<br>`$offset = ($page - 1) * 20;` | 확실 | 매직 넘버 20 이 `:349`, `:523`, `:553` 세 곳에 따로 적혀 있다. `page=0` · `00` 은 경고 없이 1 로 바뀐다(`:342-344`). `page` 가 비었으면 경고 없음(`:339`). `-1` · `abc` 는 숫자가 아니라서 경고와 함께 1. `pageRaw` 는 trim 하지 않아 ` 2` 처럼 공백이 붙으면 경고와 함께 1. `:339` 의 `!== '1'` 검사는 `'1'` 이 `:337` 정규식에 먼저 걸리므로 실행될 일이 없는 조건이다 |
| BR-22 | 총 건수가 0이면 "검색 결과가 없습니다" 만 출력하고 표 · 페이지 링크를 출력하지 않는다. | `search.php:657-659` | `if ($total === 0) {`<br>`    echo '<p id="message">검색 결과가 없습니다</p>' . "\n";`<br>`    return;` | 확실 | 판단 기준이 현재 페이지 행 수가 아니라 총 건수다. 마지막 페이지를 넘는 `page` 면 행이 없는 빈 표가 나온다(코드 흐름상, 실행 미확인) |
| BR-23 | 총 페이지 수(`ceil(총건수/20)`)가 2 이상일 때만 페이지 링크를 출력하고, 링크에는 현재 검색 조건 · 정렬을 유지한다. | `search.php:423-430`, `:685-686` | `$pages = (int)ceil($total / $pageSize);`<br>`if ($pages > 1) {` | 확실 | 999페이지 상한(BR-21)과 달리 링크 개수에는 상한이 없다 |
| BR-24 | 결과 표의 태그 칸은 문항에 붙은 태그 이름을 태그 id 순으로 쉼표로 이어 보여 주고, 태그가 없으면 빈칸이다. | `db/mariadb/init/01-schema.sql:64-67`, `search.php:678` | `(SELECT GROUP_CONCAT(t.name ORDER BY t.id SEPARATOR ',')`<br>`echo '<td>' . h($r['tag_names'] === null ? '' : $r['tag_names']) . '</td>';` | 확실 | |

### 2.1 정렬 기준별 ORDER BY (BR-18 · BR-19 상세)

| `sort` | `dir` 생략 · 잘못된 값 | `dir=asc` | `dir=desc` | 근거 |
|---|---|---|---|---|
| (빈 값) · 알 수 없는 값 | `level DESC, id ASC` | 같음 | 같음 | `search.php:317-326` |
| `id` | `id ASC` | `id ASC` | `id DESC` | `:277-283` |
| `title` | `title ASC, id ASC` | 같음 | `title DESC, id ASC` | `:285-291` |
| `unit` | `unit_code ASC, level DESC, id ASC` | 같음 | `unit_code DESC, level DESC, id ASC` | `:293-299` |
| `level` | `level DESC, id ASC` | `level ASC, id ASC` | `level DESC, id ASC` | `:301-307` |
| `created` | `created_at DESC, id ASC` | `created_at ASC, id ASC` | `created_at DESC, id ASC` | `:309-315` |

## 3. 목록 기본 정렬 · 제외 조건 (단원 목록 · 선택 목록)

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-25 | 단원 목록(단원 목록 화면 · 등록 폼 선택 목록 · 검색 폼 선택 목록)은 학년 오름차순, 같은 학년이면 단원 코드 오름차순이다. | `units.php:16-19`, `register.php:27`, `search.php:156` | `ORDER BY u.grade ASC, u.code ASC`<br>`SELECT id, code, name FROM unit ORDER BY grade ASC, code ASC` | 확실 | 같은 정렬이 세 파일에 따로 적혀 있다. 검색 폼은 학년별 `optgroup` 으로 묶는다(`search.php:452-462`) |
| BR-26 | 단원 목록의 "공개 문항 수"는 그 단원의 문항 중 상태가 `'A'` 인 것만 센다(검수중 · 삭제 제외). | `units.php:15`, `units.php:17` | `(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count` | 확실 | "공개만" 규칙이 여기는 SQL 에, 검색은 뷰에 있다(BR-02). 이 수에는 **난이도 5가 포함**되지만 검색 기본값은 5를 뺀다(BR-10) → 단원 목록 수와, 그 단원으로 난이도 없이 검색한 건수가 다를 수 있다. 문항이 0개인 단원도 목록에 나온다(서브쿼리라 단원 행이 빠지지 않음) |
| BR-27 | 태그 목록(등록 폼 체크박스 · 검색 폼 선택 목록)은 태그 id 오름차순이다. | `register.php:34`, `search.php:178` | `SELECT id, name FROM tag ORDER BY id ASC`<br>`SELECT name FROM tag ORDER BY id ASC` | 확실 | 두 곳에 따로 적혀 있다 |

## 4. 문항 등록 — 검증 · 저장 (`register.php`)

| ID | 규칙 | 근거 | 근거 코드 | 확신도 | 비고 |
|---|---|---|---|---|---|
| BR-28 | 제목 · 지문 · 단원 · 난이도 입력값은 앞뒤 공백을 뺀 뒤 검증 · 저장한다. | `register.php:41-44` | `$title  = isset($_POST['title'])  ? trim((string)$_POST['title'])  : '';` | 확실 | 태그 값은 trim 하지 않는다(`:45` `$tagIds = isset($_POST['tags']) && is_array($_POST['tags']) ? $_POST['tags'] : array();`). 그래서 `" 3"` 처럼 공백이 붙은 태그 id 는 `:77` 의 `===` 비교에 걸리지 않아 오류 없이 버려진다(BR-34) |
| BR-29 | 제목이 5자 미만이면 오류로 막는다(글자 수 기준, 가운데 공백도 센다). | `register.php:48-51` | `// 제목은 5자 이상 (공백 제외 아님, 앞뒤 공백만 제거)`<br>`if (mb_strlen($title, 'UTF-8') < 5) {` | 확실 | 매직 넘버 5. 주석과 코드 일치. 폼 라벨 "제목 (5자 이상)"(`:143`)에도 같은 값이 따로 적혀 있다. 빈 제목도 이 오류 하나로 처리("필수" 문구 없음) |
| BR-30 | 제목이 200자를 넘으면 오류로 막는다. | `register.php:52-54` | `if (mb_strlen($title, 'UTF-8') > 200) {`<br>`    $errors[] = '제목은 200자를 넘을 수 없습니다.';` | 확실 | 매직 넘버 200 = `item.title VARCHAR(200)`(`01-schema.sql:23`). 폼 입력칸에 `maxlength` 는 없다(`:144`) |
| BR-31 | 지문이 비어 있으면 오류로 막는다. | `register.php:55-58` | `if ($stem === '') {`<br>`    $errors[] = '지문을 입력해야 합니다.';` | 확실 | 최소 · 최대 길이 검증은 없다(DB 는 `TEXT`, `01-schema.sql:24`) |
| BR-32 | 단원 id 가 단원 목록(`unit` 전체)에 있는 값과 문자열로 같지 않으면 오류로 막는다. | `register.php:60-68` | `if ((string)$u['id'] === $unitId) {`<br>`    $unitOk = true;`<br>`$errors[] = '단원을 선택해야 합니다.';` | 확실 | 선택 안 함 · 없는 id 모두 같은 문구. 문자열 비교라 `01` 처럼 앞에 0이 붙으면 거부된다 |
| BR-33 | 난이도가 `1`~`5` 한 자리 숫자가 아니면 오류로 막는다. | `register.php:69-72` | `if (!preg_match('/^[1-5]$/', $level)) {`<br>`    $errors[] = '난이도는 1~5 사이여야 합니다.';` | 확실 | **같은 규칙이 두 곳에 다르게 구현**: 검색(`search.php:217`, `:223-229`)은 같은 정규식을 쓰지만 벗어난 값을 오류가 아닌 경고로 처리하고 조회한다(BR-12). DB 에는 범위 제약이 없고 주석만 있다(`01-schema.sql:25`). 폼 선택 목록도 `1..5` 반복(`:163`) |
| BR-34 | 태그 id 는 태그 목록에 있는 것만 저장하고, 목록에 없는 id 는 오류 없이 버린다. 태그는 0개여도 된다. | `register.php:73-81`, `:104` | `if ((string)$t['id'] === (string)$tid) {`<br>`    $validTagIds[] = (int)$tid;`<br>`if (!empty($validTagIds)) {` | 확실 | 같은 태그 id 가 두 번 넘어오면 `$validTagIds` 에 두 번 들어가 `item_tag` PK(`01-schema.sql:45`) 위반 → 전체 롤백 · "저장 중 오류"(BR-39)가 될 것으로 보인다 — 코드 흐름상, 실행 미확인 |
| BR-35 | 검증 오류가 하나라도 있으면 저장하지 않고, 모인 오류를 모두 보여 준다. | `register.php:83`, `:133-139` | `if (empty($errors)) {` | 확실 | 한 번에 첫 오류만이 아니라 전부 표시 |
| BR-36 | 새 문항은 상태 `'R'`(검수중)로 저장되므로 검색 결과와 단원 목록 문항 수에 나오지 않는다. | `register.php:84`, `:94`, `:116` + `db/mariadb/init/01-schema.sql:70`, `units.php:17` | `VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"`<br>`$notice = '문항 #' . $newId . ' 을(를) 등록했습니다. 검수 완료 후 검색에 노출됩니다.';` | 확실 | `register.php:84` 의 "검색 화면에 나오지 않는다"는 주석이고, 실제로 막는 것은 뷰의 `WHERE i.status = 'A'`(`01-schema.sql:70`)와 `units.php:17` 의 `i.status = 'A'` 조건이다. 입력 폼에 상태 선택은 없다. `'R'` → `'A'` 로 바꾸는 코드는 모듈 안에 없다(ERD.md §4). `item.status` 의 DB 기본값은 `'A'`(`01-schema.sql:26`)지만 등록 코드가 `'R'` 을 명시하므로 쓰이지 않는다 |
| BR-37 | 새 문항 id 는 트랜잭션 안에서 `item` 의 최대 id + 1 로 정하고, 문항이 하나도 없으면 1이다. | `register.php:85`, `:87`, `:90` | `$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");` | 확실 | 상태와 상관없이 전체 `item` 기준(검수중 · 삭제 포함) |
| BR-38 | 등록 시 `created_at` 과 `updated_at` 을 모두 DB 서버의 현재 시각으로 넣는다. | `register.php:94` | `VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"` | 확실 | 애플리케이션 시각이 아니라 DB `NOW()` |
| BR-39 | 문항과 문항-태그 저장은 한 트랜잭션이며, 어느 하나라도 실패하면 모두 되돌리고 "저장 중 오류가 발생했습니다." 를 보여 준다. | `register.php:85`, `:99-100`, `:108-109`, `:114`, `:119-122` | `$conn->commit();`<br>`$conn->rollback();`<br>`$errors[] = '저장 중 오류가 발생했습니다.';` | 확실 | |
| BR-40 | 등록에 성공하면 입력 폼을 비우고, 실패(검증 · 저장)하면 입력값을 폼에 남긴다. | `register.php:117-118`, `:144`, `:148` | `$title = $stem = $unitId = $level = '';`<br>`$tagIds = array();` | 확실 | 화면 동작 규칙 |

### 4.1 문항 수정 · 삭제

- 모듈 안에 `item` · `item_tag` 에 대한 `UPDATE` · `DELETE` 문이 없다(ERD.md §3.2, §3.4). 수정 · 삭제 시 검증 규칙은 **코드에서 찾지 못했다**.

## 5. 매직 넘버

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `5` | `register.php:49`, `:143` | 제목 최소 글자 수 |
| `200` | `register.php:52-53` | 제목 최대 글자 수 (`item.title VARCHAR(200)` 과 같은 값, `01-schema.sql:23`) |
| `1-5` (정규식 `/^[1-5]$/`) | `register.php:70`, `search.php:217` | 난이도 허용 범위 |
| `1`, `5` (반복 범위) | `register.php:163`, `search.php:193-199` | 난이도 선택 목록 범위 · 라벨(1 매우 쉬움 ~ 5 최상) |
| `5` | `search.php:215` | 난이도 미지정 검색에서 제외하는 난이도(`level < 5`). 의도는 코드에 없음 |
| `100` | `search.php:86-88`, `:494` | 검색 키워드 최대 글자 수 |
| `1` | `search.php:91` | "결과가 많을 수 있음" 경고를 띄우는 키워드 길이 |
| `50` | `search.php:240-242` | 태그 이름 최대 글자 수 (`tag.name VARCHAR(50)` 과 같은 값, `01-schema.sql:37`) |
| `{1,2}` (정규식 `[A-Za-z][0-9]{1,2}-[0-9]{1,2}`) | `search.php:111` | 단원 코드 형식: 영문 1자 + 숫자 1~2자리(학년으로 추정) + `-` + 숫자 1~2자리 |
| `20` | `search.php:349`, `:523`, `:553` | 검색 결과 한 페이지 건수 |
| `999` | `search.php:345-347` | 검색 페이지 번호 상한 |
| `'A'` | `units.php:17`, `db/mariadb/init/01-schema.sql:70` | 공개 문항 상태 코드 |
| `'R'` | `register.php:94` | 검수중 상태 코드(새 문항의 초기 상태) |

## 6. 주석만 있거나 주석과 코드가 다른 것

규칙으로 올리지 않았거나, 규칙 비고에 적은 불일치를 모았다.

| 구분 | 내용 | 근거 |
|---|---|---|
| 주석만 있음 | `item.status` 의 `D=삭제` 의미. `'D'` 를 쓰거나 읽는 코드가 모듈 안에 없다(뷰 · `units.php` 는 `'A'` 만 본다) | `db/mariadb/init/01-schema.sql:26`, `:50` |
| 주석만 있음 | "검수 완료 후 검색에 노출" — 검수 완료(`'R'`→`'A'`) 처리 코드가 없다 | `register.php:116` |
| 주석만 있음 (DB 쪽) | `item.level` 1~5 범위. DB 에 CHECK 제약이 없고, 코드 검증은 `register.php:70` 뿐이다 | `db/mariadb/init/01-schema.sql:25` |
| 주석 ≠ 코드 | 난이도 미지정 시 "1~5 모두 포함" ↔ 실제 `level < 5` (BR-10) | `search.php:213-215` |
| 주석 ≠ 코드 | 뷰가 "등록 화면도 기준" ↔ `register.php` 는 뷰를 쓰지 않는다 (BR-02) | `db/mariadb/init/01-schema.sql:51` |
| 주석 ≠ 동작 (추정) | 단원 코드 "입력값 그대로 조회" ↔ PHP 는 그대로 넘기지만 대소문자 무시 콜레이션이라 소문자도 일치한다 (BR-08, BR-41) | `search.php:116`, `db/mariadb/init/01-schema.sql:18`, `inc/db.php:27` |
| 주석 ≠ 코드 (부분) | 범위 밖 난이도는 "대개 0건" ↔ `05`·`3.9`·`5abc` 등은 정수 변환 뒤 실제 결과가 나온다 (BR-12) | `search.php:224`, `:228` |
| 주석만 있음 | 등록 문항이 "검수 완료 전까지 검색 화면에 나오지 않는다"는 주석이고, 실제 보장은 뷰 조건이다 (BR-36) | `register.php:84`, `db/mariadb/init/01-schema.sql:70` |

## 7. 같은 규칙이 여러 곳에 다르게 구현된 것

| 규칙 | 구현 위치 | 차이 |
|---|---|---|
| 공개 문항만 노출 | 뷰 `v_item_public`(`01-schema.sql:70`) / `units.php:17` | 한쪽은 뷰, 한쪽은 SQL 조건 직접. 기준 변경 시 두 곳을 모두 고쳐야 한다 |
| 난이도 1~5 | `register.php:70` / `search.php:217`, `:223-229` | 등록은 오류로 막고, 검색은 경고 후 정수 변환 값으로 조회 |
| 난이도 제외 조건 | `search.php:215` / `units.php:17` | 검색 기본은 5 제외, 단원 목록 문항 수는 5 포함 |
| 기본 정렬 방향 | `search.php:276-315` (SQL) / `:330` (화면 표시) | 한 규칙을 두 번 구현 |
| 기본 정렬 SQL | `search.php:319` / `:325` | 같은 문자열 두 번 |
| 페이지 크기 20 | `search.php:349` / `:523` / `:553` | 같은 값 세 번 |
| 단원 목록 정렬 | `units.php:19` / `register.php:27` / `search.php:156` | 같은 정렬 세 번 |
| 태그 목록 정렬 | `register.php:34` / `search.php:178` | 같은 정렬 두 번 |

## 8. 검증 이력

`docs/item-bank/CROSS-CHECK.md`(독립 분석)와 비교해서 서로 다른 항목을 코드로 다시 확인했다(2026-09-29). 판정은 "BR" = 이 문서가 맞음, "CX" = CROSS-CHECK 가 맞음.

- BR-02 상태 필터: BR. CX 가 확인하지 못한 뷰 정의를 `01-schema.sql:52`, `:68-70` 에서 확인했다. 근거에 `:52` 를 추가했다.
- BR-08 단원 코드 대소문자: 둘 다 일부 맞음. PHP 는 값을 바꾸지 않고(CX), DB 콜레이션이 대소문자를 무시한다(BR). 근거에 `inc/db.php:27` · `01-schema.sql:5` 를 추가하고 BR-41 을 새로 만들었다. 확신도는 추정 그대로(실행 미확인).
- BR-12 범위 밖 난이도: BR. CX 가 짚은 "`level < 5` 가 붙지 않음"과 `5abc` · `-1` 예, 요약에 정수가 표시되는 점을 비고에 추가했다.
- BR-19 잘못된 dir 의 경고: BR. 인용 코드에 `:269-270` 을 추가하고, 공백만 있는 값은 경고가 없다는 점을 적었다.
- BR-21 페이지 보정의 경고: BR. 규칙 문장에 "1 미만은 경고 없이"를 넣고, `pageRaw` 를 trim 하지 않는 점과 `:339` 의 실행될 일 없는 조건을 비고에 추가했다.
- BR-25 단원 목록 정렬 범위: BR. 세 곳(`units.php:19`, `register.php:27`, `search.php:156`)을 다시 확인했고, 바꾼 내용은 없다.
- BR-28 등록 입력값 trim 범위: BR. 공백 붙은 태그 id 가 버려지는 점을 비고에 추가했다.
- BR-36 등록 문항 미노출 근거: BR. `register.php:84` 는 주석이고 실제 보장은 뷰 조건이라는 점을 비고와 §6 에 추가했다.

## 이관 회고

| 바뀐 지점 | 레거시 동작 | AI가 만든 동작 | 테스트가 잡았나 | 다음에 막을 방법 |
|---|---|---|---|---|
| 배열 파라미터 괄호 미인코딩 | `?q[]=소수&q[]=분수` 를 받아 첫 값(소수)으로 검색, 200 · 4건 | Tomcat 이 400 으로 막음. 본문은 ErrorResponse 가 아닌 Tomcat HTML | 못 잡음. 테스트는 URL 을 `URLSearchParams` 로 만들어 괄호가 항상 `%5B%5D` 로 인코딩됨(`characterization/lib/target.mjs:41-42`) | 동작 보존 테스트에 괄호를 인코딩하지 않은 원문 쿼리 문자열(`q[]=…`)을 그대로 보내는 케이스를 추가해, 브라우저 · curl 이 보내는 모양으로도 레거시와 비교한다 |
