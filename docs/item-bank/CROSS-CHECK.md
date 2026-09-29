# 레거시 비즈니스 규칙 교차 검증 (CX)

- 대상: `legacy/item-bank-php` 의 `search.php`, `register.php`, `units.php`
- 방법: 위 코드만 읽고 추출했다. `docs/` 는 읽지 않았고, 외부 라이브러리(`vendor/`)도 읽지 않았다.
- 원칙: 코드에 있는 규칙만 적는다. 주석과 코드가 다르면 코드를 기준으로 하고, 다른 점은 "주석과의 차이"로 표시한다.

## A. 검색 조건 조합 (search.php)

### CX-01 조건은 AND 로 결합한다
- 규칙: 키워드 · 단원 · 난이도 · 태그 조건은 모두 `AND` 로 이어 붙는다. 조건이 없으면 `WHERE 1=1` 뿐이다.
- 근거: `legacy/item-bank-php/search.php:44`, `:98`, `:118`
- 코드:
  ```php
  $where    = " WHERE 1=1";
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  $where .= " AND unit_code = ?";
  ```

### CX-02 키워드는 제목 또는 지문의 부분 일치다
- 규칙: 키워드는 앞뒤 공백을 제거한 뒤 `%키워드%` 로 제목과 지문 중 하나라도 맞으면 통과한다. 100자를 넘으면 앞 100자만 쓴다. `%` · `_` 는 이스케이프하지 않아 와일드카드로 동작한다.
- 근거: `search.php:85-98`
- 코드:
  ```php
  $q = trim($q);
  if (mb_strlen($q, 'UTF-8') > 100) { $q = mb_substr($q, 0, 100, 'UTF-8'); ...
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  ```

### CX-03 단원은 코드 정확 일치이고, 형식이 틀려도 그대로 조회한다
- 규칙: 단원은 `unit_code = ?` 로 정확히 비교한다. 형식(`^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$`)이 틀리거나 소문자여도 막지 않고 경고만 남기며, 입력값 그대로 조회한다(대소문자 변환 없음). 등록되지 않은 코드도 경고만 하고 조회한다.
- 근거: `search.php:109-121`, `:141-143`
- 코드:
  ```php
  if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) { // 형식이 달라도 그대로 조회한다
  if ($unit !== strtoupper($unit)) { $warnings[] = '... (입력값 그대로 조회합니다)';
  $where .= " AND unit_code = ?";
  ```

### CX-04 난이도가 비어 있으면 5 등급 문항을 제외한다 (주석과 코드가 다름)
- 규칙: 난이도를 지정하지 않으면 `level < 5` 조건이 붙어 난이도 5 문항은 검색되지 않는다. 난이도 5 문항은 `level=5` 를 명시해야만 나온다.
- 주석과의 차이: 주석은 "전체 난이도 검색 (1~5 모두 포함)" 이라고 하지만 코드는 `level < 5` 이다. 코드를 기준으로 한다.
- 근거: `search.php:213-216`
- 코드:
  ```php
  // 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
  if ($level == '') {
      $where .= " AND level < 5";
  ```

### CX-05 난이도는 1~5 정수 일치이고, 범위 밖 값도 그대로 비교한다
- 규칙: `1`~`5` 한 글자면 `level = ?` 로 비교한다. 그 밖의 값은 경고만 하고 `(int)` 변환한 값으로 `level = ?` 비교를 한다(대개 0건). 이 경우에는 `level < 5` 조건이 붙지 않는다.
- 근거: `search.php:217-230`
- 코드:
  ```php
  else if (preg_match('/^[1-5]$/', $level)) { $where .= " AND level = ?"; ...
  // 1~5 밖의 값은 그대로 정수로 비교한다 (대개 0건)
  $values[] = (int)$level;
  ```

### CX-06 태그는 이름 정확 일치이며 단일 태그만 받는다
- 규칙: 태그는 `tag.name = ?` 로 정확히 일치하는 문항만 찾는다(`EXISTS` 서브쿼리). 값은 하나만 받고(배열이면 첫 값), 50자를 넘으면 앞 50자만 쓴다. 등록되지 않은 태그도 경고만 하고 조회한다(0건).
- 근거: `search.php:235-248`, 파라미터 꺼내기 `:65-68`
- 코드:
  ```php
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
          . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
  ```

### CX-07 모든 파라미터는 배열로 들어오면 첫 값만 쓴다
- 규칙: `q, unit, level, tag, sort, dir, page` 는 배열이면 `reset()` 의 첫 값만 사용한다.
- 근거: `search.php:53-80`
- 코드:
  ```php
  $unit = is_array($params['unit']) ? (string)reset($params['unit']) : (string)$params['unit'];
  ```

### CX-08 검색은 v_item_public 뷰만 대상으로 한다
- 규칙: 목록 조회와 건수 조회 모두 `v_item_public` 뷰를 `FROM` 으로 쓴다. `item` 테이블을 직접 조회하지 않는다.
- 근거: `search.php:522`, `:526`
- 코드:
  ```php
  $from   = " FROM v_item_public";
  $countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
  ```
- 확인 못 한 점: 뷰의 정의(어떤 상태를 제외하는지)는 읽은 범위(`legacy/item-bank-php` PHP 파일)에 없다. 상태 제외는 CX-17, CX-19 에서 다른 코드로만 뒷받침된다.

## B. 목록의 기본 정렬 · 페이지 · 제외

### CX-09 기본 정렬은 난이도 내림차순, 같으면 번호 오름차순이다
- 규칙: `sort` 가 비어 있으면 `level DESC, id ASC`. 알 수 없는 `sort` 값도 경고 후 같은 정렬을 쓴다.
- 근거: `search.php:317-326`
- 코드:
  ```php
  case '':
      // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
      $orderBy = " ORDER BY level DESC, id ASC";
  ```

### CX-10 정렬 기준은 5 가지이고 방향 기본값이 기준마다 다르다
- 규칙: `sort` 는 `id | title | unit | level | created`(대소문자 무시). `dir` 이 `asc` · `desc` 가 아니면 경고 후 비운다. 방향을 안 주면 `id · title · unit` 은 오름차순, `level · created` 는 내림차순이다. `unit` 정렬은 방향에 관계없이 보조 정렬이 `level DESC, id ASC` 이고, `title · level · created` 는 보조 정렬이 `id ASC` 이다.
- 근거: `search.php:266-273`, `:277-315`
- 코드:
  ```php
  $orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
  $orderBy = " ORDER BY level DESC, id ASC";       // level: dir 이 asc 가 아니면
  $orderBy = " ORDER BY created_at DESC, id ASC";  // created: dir 이 asc 가 아니면
  ```

### CX-11 한 페이지 20건이고 페이지 번호는 1~999 로 보정한다
- 규칙: `LIMIT 20 OFFSET (page-1)*20`. 숫자가 아니면 1페이지, 1 미만은 1, 999 초과는 999 로 보정하며 경고를 남긴다.
- 근거: `search.php:336-349`, `:523`
- 코드:
  ```php
  if ($page > 999) { $page = 999; ... }
  $offset = ($page - 1) * 20;
  $limit  = " LIMIT 20 OFFSET " . (int)$offset;
  ```

### CX-12 결과가 0건이면 표 대신 안내 문구를 낸다
- 규칙: 총 건수가 0 이면 `검색 결과가 없습니다` 만 표시하고 표를 그리지 않는다. 건수는 `검색 결과 N건` (전체 건수, 현재 페이지 행 수 아님).
- 근거: `search.php:655-660`
- 코드:
  ```php
  echo '<p id="count">검색 결과 ' . (int)$total . '건</p>' . "\n";
  if ($total === 0) { echo '<p id="message">검색 결과가 없습니다</p>' ...
  ```

## C. 등록 시 검증 (register.php)

수정 화면은 읽은 범위에서 찾지 못했다(메뉴에 등록 · 검색 · 단원 목록만 있다: `index.php:9-11`). 따라서 "수정 시 검증" 규칙은 없다.

### CX-13 제목은 5~200자, 지문은 필수다
- 규칙: 제목은 앞뒤 공백 제거 후 5자 미만 또는 200자 초과면 오류. 지문은 앞뒤 공백 제거 후 빈 문자열이면 오류. 제목 안쪽 공백은 글자 수에 포함한다.
- 근거: `register.php:41-42`, `:49-58`
- 코드:
  ```php
  if (mb_strlen($title, 'UTF-8') < 5) {
  if (mb_strlen($title, 'UTF-8') > 200) {
  if ($stem === '') {
  ```

### CX-14 단원은 필수이고 존재하는 단원 id 여야 한다
- 규칙: 폼 값 `unit_id` 가 `unit` 테이블의 id 목록 중 하나와 문자열로 일치해야 한다. 아니면 오류.
- 근거: `register.php:60-68`
- 코드:
  ```php
  if ((string)$u['id'] === $unitId) { $unitOk = true; }
  if (!$unitOk) { $errors[] = '단원을 선택해야 합니다.';
  ```

### CX-15 난이도는 1~5 한 자리 정수여야 한다
- 규칙: `^[1-5]$` 에 맞지 않으면 오류. (검색과 달리 범위 밖 값을 허용하지 않는다.)
- 근거: `register.php:70-72`
- 코드:
  ```php
  if (!preg_match('/^[1-5]$/', $level)) {
      $errors[] = '난이도는 1~5 사이여야 합니다.';
  ```

### CX-16 태그는 선택이고, 목록에 없는 태그 id 는 오류 없이 버린다
- 규칙: 태그는 필수가 아니다. 존재하는 태그 id 만 골라 저장하고, 없는 id 는 오류 없이 무시한다. 중복 id 가 들어와도 걸러내지 않는다.
- 근거: `register.php:74-81`, `:104-112`
- 코드:
  ```php
  if ((string)$t['id'] === (string)$tid) { $validTagIds[] = (int)$tid; }
  if (!empty($validTagIds)) { ... INSERT INTO item_tag
  ```

### CX-17 등록한 문항은 검수중(R) 상태로 저장된다
- 규칙: 신규 문항의 `status` 는 항상 `'R'` 이다. 등록 직후에는 공개 상태(`'A'`)가 아니다.
- 근거: `register.php:92-94`
- 코드:
  ```php
  "INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
   VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 참고: "검수 완료 전까지 검색 화면에 나오지 않는다"(`register.php:84`)는 주석이다. 이를 실제로 보장하는 것은 뷰 정의인데 읽은 범위에 없어 확인하지 못했다.

### CX-18 새 문항 id 는 최대 id + 1 이다
- 규칙: id 는 자동 증가가 아니라 `MAX(id)+1` 을 `FOR UPDATE` 로 잠근 트랜잭션 안에서 구해 직접 넣는다. 문항과 태그 저장은 한 트랜잭션이며 하나라도 실패하면 롤백한다.
- 근거: `register.php:85-90`, `:114`, `:120`
- 코드:
  ```php
  $res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
  ```

## D. 제외 조건 (units.php)

### CX-19 단원별 문항 수는 status='A' 만 센다
- 규칙: 단원 목록의 "공개 문항 수" 는 `status = 'A'` 인 문항만 센다. 검수중 · 삭제 문항은 세지 않는다. 문항이 없는 단원도 0 으로 목록에 나온다. 단원은 학년, 코드 오름차순.
- 근거: `units.php:15-19`
- 코드:
  ```php
  (SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
  ORDER BY u.grade ASC, u.code ASC
  ```

## E. 확인하지 못한 것
- `v_item_public` 정의(상태 필터 여부)는 읽은 PHP 파일에 없어 규칙으로 쓰지 않았다.
- 수정(edit/update) 화면은 읽은 범위에서 찾지 못했다. 파일 목록 조회(Bash)가 실패해 추정한 파일명만 열어 봤으므로, 다른 이름의 수정 파일이 있을 가능성은 남아 있다.
- `legacy/` 아래 파일은 수정하지 않았다.
