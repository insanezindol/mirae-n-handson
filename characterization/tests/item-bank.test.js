// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// 규칙 ID(BR-xx)는 docs/item-bank/BUSINESS-RULES.md 를 따른다.
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 레거시 기본 주소).
//   이관 후에는 PATH_ALIASES 로 /search.php → /api/items/search 로 넘어간다.
// - 기대값은 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기(skipIf)를 두지 않는다.
// - 시드 데이터(db/mariadb/init/02-seed.sql)가 기준이다. status='A' AND level<5 가 정확히 20건(= 페이지 크기).
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

// 규칙의 경계값 (BUSINESS-RULES.md §5 매직 넘버)
const KEYWORD_MAX_LENGTH = 100; // BR-05
const TAG_MAX_LENGTH = 50; // BR-14
const PAGE_MAX = 999; // BR-21

// 이상한 값: 아주 긴 문자열
const VERY_LONG_KEYWORD_LENGTH = 5000;
const VERY_LONG_UNIT_CODE_LENGTH = 300; // unit.code VARCHAR(16) 보다 훨씬 길다

const search = (params) => fetchNormalized(MODULE, PATH, params);

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('키워드·단원·난이도 AND 조합, 검수중(R) 문항 제외 — q=분수 unit=M5-1 level=3 (BR-01·02·04·08·11)', async () => {
      expect(await search({ q: '분수', unit: 'M5-1', level: '3' })).toMatchSnapshot();
    });

    it('태그 이름 필터 + 제목 내림차순, 태그 칸 쉼표 연결 — tag=계산 sort=title dir=desc (BR-13·18·24)', async () => {
      expect(await search({ tag: '계산', sort: 'title', dir: 'desc' })).toMatchSnapshot();
    });

    it('난이도 5 직접 지정 + 단원 정렬(2차 키 level DESC) — level=5 sort=unit (BR-02·11·18·19)', async () => {
      expect(await search({ level: '5', sort: 'unit' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 범위 바로 밖 — level=6 은 경고 후 정수 비교, level<5 조건 없음 (BR-10·12)', async () => {
      expect(await search({ level: '6' })).toMatchSnapshot();
    });

    it('난이도 앞자리 0 — level=05 는 정규식 불통과지만 정수 5 로 조회 (BR-12)', async () => {
      expect(await search({ level: '05' })).toMatchSnapshot();
    });

    it(`키워드 정확히 ${KEYWORD_MAX_LENGTH}자 — 잘라내기 없음 (BR-05)`, async () => {
      expect(await search({ q: '가'.repeat(KEYWORD_MAX_LENGTH) })).toMatchSnapshot();
    });

    it(`키워드 ${KEYWORD_MAX_LENGTH + 1}자 — 앞 ${KEYWORD_MAX_LENGTH}자로 자르고 경고 (BR-05)`, async () => {
      expect(await search({ q: '가'.repeat(KEYWORD_MAX_LENGTH + 1) })).toMatchSnapshot();
    });

    it('키워드 한 글자 — 경고만 띄우고 검색 (BR-04·07)', async () => {
      expect(await search({ q: '곱' })).toMatchSnapshot();
    });

    it(`페이지 번호 상한과 같은 값 — page=${PAGE_MAX} (BR-21·22)`, async () => {
      expect(await search({ page: String(PAGE_MAX) })).toMatchSnapshot();
    });

    it(`페이지 번호 상한 초과 — page=${PAGE_MAX + 1} 은 ${PAGE_MAX} 로 보정 (BR-21·22)`, async () => {
      expect(await search({ page: String(PAGE_MAX + 1) })).toMatchSnapshot();
    });

    it('기본 검색 2페이지 — 총 20건(1페이지 분량)에서 마지막 페이지 다음 (BR-21·22·23)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it(`태그 이름 ${TAG_MAX_LENGTH + 1}자 — ${TAG_MAX_LENGTH}자로 자른 뒤 없는 태그 (BR-14·15)`, async () => {
      expect(await search({ tag: '가'.repeat(TAG_MAX_LENGTH + 1) })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('파라미터 없음 — 난이도 5 제외 · 기본 정렬 level DESC, id ASC (BR-10·16·21·23)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('7개 파라미터 모두 빈 문자열 — 누락과 같은지 (BR-10·16·19·21)', async () => {
      expect(await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' })).toMatchSnapshot();
    });

    it('공백만 있는 값 — q·unit·tag·dir 은 trim, page 는 trim 안 함 (BR-04·08·13·19·21)', async () => {
      expect(await search({ q: '  ', unit: ' ', tag: ' ', dir: ' ', page: ' ' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 — level=-1 은 경고 후 정수 비교, page=-1 은 숫자 아님으로 1페이지 (BR-12·21)', async () => {
      expect(await search({ level: '-1', page: '-1' })).toMatchSnapshot();
    });

    it(`아주 긴 문자열 — 키워드 ${VERY_LONG_KEYWORD_LENGTH}자 · 단원 코드 ${VERY_LONG_UNIT_CODE_LENGTH}자 (BR-05·09)`, async () => {
      const params = {
        q: 'a'.repeat(VERY_LONG_KEYWORD_LENGTH),
        unit: 'A'.repeat(VERY_LONG_UNIT_CODE_LENGTH),
      };
      expect(await search(params)).toMatchSnapshot();
    });

    it('형식은 맞는데 없는 단원 · 없는 태그 — unit=Z9-99 tag=없는태그 (BR-01·09·15)', async () => {
      expect(await search({ unit: 'Z9-99', tag: '없는태그' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 — unit=m5-1 이 콜레이션 때문에 M5-1 과 일치하는지 (BR-08·09·41)', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('알 수 없는 정렬 기준 · 방향 — sort=DROP dir=up 은 기본 정렬 (BR-17·19)', async () => {
      expect(await search({ sort: 'DROP', dir: 'up' })).toMatchSnapshot();
    });

    it('키워드 % — 이스케이프 없이 LIKE 와일드카드로 전체 일치 (BR-06)', async () => {
      expect(await search({ q: '%' })).toMatchSnapshot();
    });

    it('배열 파라미터 — q[]=소수&q[]=분수 는 첫 값만 사용 (BR-03)', async () => {
      expect(await search({ 'q[]': ['소수', '분수'] })).toMatchSnapshot();
    });
  });
});
