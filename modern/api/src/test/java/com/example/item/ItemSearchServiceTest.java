package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    private static final long SEED_ACTIVE_BELOW_FIVE = 20;

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    /** 저장소에 넘어간 인자. */
    private record Captured(
        String status, String keyword, String unitCode, Integer levelEq, Integer levelBelow, String tagName,
        Pageable pageable) {
    }

    private Captured searchAndCapture(ItemSearchRequest request, List<Item> content, long total) {
        when(itemRepository.search(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> new PageImpl<>(content, inv.getArgument(6), total));

        itemSearchService.search(request);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> unitCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> levelEq = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> levelBelow = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> tagName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).search(status.capture(), keyword.capture(), unitCode.capture(), levelEq.capture(),
            levelBelow.capture(), tagName.capture(), pageable.capture());
        return new Captured(status.getValue(), keyword.getValue(), unitCode.getValue(), levelEq.getValue(),
            levelBelow.getValue(), tagName.getValue(), pageable.getValue());
    }

    private Captured searchAndCapture(ItemSearchRequest request) {
        return searchAndCapture(request, List.of(), 0);
    }

    private static ItemSearchRequest request(String q, String unit, String level, String tag) {
        return new ItemSearchRequest(q, unit, level, tag, null, null, null);
    }

    @Test
    @DisplayName("파라미터 없음: 공개 문항, 난이도 5 제외(level < 5), 기본 정렬, 1페이지 20건")
    void defaultSearchExcludesLevelFive() {
        Captured c = searchAndCapture(new ItemSearchRequest(null, null, null, null, null, null, null));

        assertThat(c.status()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(c.keyword()).isNull();
        assertThat(c.unitCode()).isNull();
        assertThat(c.levelEq()).isNull();
        assertThat(c.levelBelow()).isEqualTo(ItemSearchService.DEFAULT_LEVEL_UPPER_BOUND);
        assertThat(c.tagName()).isNull();
        assertThat(c.pageable().getPageNumber()).isZero();
        assertThat(c.pageable().getPageSize()).isEqualTo(ItemSearchService.PAGE_SIZE);
        assertThat(c.pageable().getSort().toString()).isEqualTo("level: DESC,id: ASC");
    }

    @Test
    @DisplayName("빈 문자열 · 공백만 있는 값은 조건 없음과 같다(난이도 공백도 level < 5)")
    void blankValuesAreIgnored() {
        Captured c = searchAndCapture(request("  ", " ", " \t", " "));

        assertThat(c.keyword()).isNull();
        assertThat(c.unitCode()).isNull();
        assertThat(c.levelEq()).isNull();
        assertThat(c.levelBelow()).isEqualTo(ItemSearchService.DEFAULT_LEVEL_UPPER_BOUND);
        assertThat(c.tagName()).isNull();
    }

    @Test
    @DisplayName("키워드: trim 후 앞뒤에 % 를 붙이고, % · _ 는 이스케이프하지 않는다")
    void keywordIsWrappedWithoutEscaping() {
        assertThat(searchAndCapture(request(" 분수 ", null, null, null)).keyword()).isEqualTo("%분수%");
    }

    @Test
    @DisplayName("키워드 % 는 그대로 와일드카드 — %%% 로 조회")
    void percentKeywordIsNotEscaped() {
        assertThat(searchAndCapture(request("%", null, null, null)).keyword()).isEqualTo("%%%");
    }

    @Test
    @DisplayName("키워드 101자는 앞 100자로 자른다")
    void keywordIsTruncatedToHundredChars() {
        String keyword = "가".repeat(ItemSearchService.KEYWORD_MAX_LENGTH + 1);

        Captured c = searchAndCapture(request(keyword, null, null, null));

        assertThat(c.keyword()).isEqualTo("%" + "가".repeat(ItemSearchService.KEYWORD_MAX_LENGTH) + "%");
    }

    @Test
    @DisplayName("단원 코드: trim 만 하고 대소문자는 바꾸지 않는다(비교는 DB 콜레이션)")
    void unitCodeIsPassedAsIs() {
        assertThat(searchAndCapture(request(null, " m5-1 ", null, null)).unitCode()).isEqualTo("m5-1");
    }

    @Test
    @DisplayName("태그 이름 51자는 앞 50자로 자른다")
    void tagIsTruncatedToFiftyChars() {
        String tag = "가".repeat(ItemSearchService.TAG_MAX_LENGTH + 1);

        assertThat(searchAndCapture(request(null, null, null, tag)).tagName())
            .isEqualTo("가".repeat(ItemSearchService.TAG_MAX_LENGTH));
    }

    @ParameterizedTest(name = "level={0} → level = {1}")
    @CsvSource({"3, 3", "5, 5", "05, 5", "6, 6", "-1, -1", "abc, 0", "3.9, 3", "5abc, 5", "+5, 5", "1e2, 100"})
    @DisplayName("난이도가 비어 있지 않으면 level = PHP (int) 변환 값, level < 5 는 붙지 않는다")
    void levelUsesPhpIntCast(String level, int expected) {
        Captured c = searchAndCapture(request(null, null, level, null));

        assertThat(c.levelEq()).isEqualTo(expected);
        assertThat(c.levelBelow()).isNull();
    }

    @ParameterizedTest(name = "page=\"{0}\" → {1}")
    @CsvSource(value = {"NULL, 1", "'', 1", "1, 1", "2, 2", "0, 1", "00, 1", "-1, 1", "' 2', 1", "abc, 1",
        "999, 999", "1000, 999", "99999999999999999999, 999"}, nullValues = "NULL")
    @DisplayName("페이지 번호: 숫자만이면 그 값, 아니면 1, 1 미만은 1, 999 초과는 999, trim 하지 않는다")
    void resolvesPage(String page, int expected) {
        assertThat(ItemSearchService.resolvePage(page)).isEqualTo(expected);
    }

    @Test
    @DisplayName("페이지 번호 끝의 줄바꿈 하나는 PHP 정규식처럼 숫자로 인정한다")
    void pageWithTrailingNewlineIsAccepted() {
        assertThat(ItemSearchService.resolvePage("2\n")).isEqualTo(2);
        assertThat(ItemSearchService.resolvePage("2\n\n")).isEqualTo(1);
    }

    @ParameterizedTest(name = "sort={0} dir={1} → {2}")
    @CsvSource(value = {
        "NULL, NULL, 'level: DESC,id: ASC'",
        "DROP, up, 'level: DESC,id: ASC'",
        "'', desc, 'level: DESC,id: ASC'",
        "id, NULL, 'id: ASC'",
        "id, desc, 'id: DESC'",
        "ID, DESC, 'id: DESC'",
        "title, NULL, 'title: ASC,id: ASC'",
        "title, desc, 'title: DESC,id: ASC'",
        "unit, NULL, 'unit.code: ASC,level: DESC,id: ASC'",
        "unit, desc, 'unit.code: DESC,level: DESC,id: ASC'",
        "level, NULL, 'level: DESC,id: ASC'",
        "level, asc, 'level: ASC,id: ASC'",
        "level, up, 'level: DESC,id: ASC'",
        "created, NULL, 'createdAt: DESC,id: ASC'",
        "created, asc, 'createdAt: ASC,id: ASC'",
        "' Title ', ' Desc ', 'title: DESC,id: ASC'"}, nullValues = "NULL")
    @DisplayName("정렬 기준 · 방향 → ORDER BY (BUSINESS-RULES §2.1 표)")
    void resolvesSort(String sort, String dir, String expected) {
        assertThat(ItemSearchService.resolveSort(sort, dir).toString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("결과: rows 는 id · title · unit · level · tags, count 는 전체 건수, message 는 null")
    void mapsRowsAndTotal() {
        Item item = ItemFixtures.item(12, unit, "대분수의 덧셈", 3, ItemStatus.ACTIVE,
            ItemFixtures.tag(1, "계산"), ItemFixtures.tag(4, "오답률높음"));
        when(itemRepository.search(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> new PageImpl<>(List.of(item), inv.getArgument(6), SEED_ACTIVE_BELOW_FIVE));

        ItemSearchResponse response = itemSearchService.search(request(null, null, null, null));

        assertThat(response.rows()).containsExactly(
            new ItemSearchRowResponse(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음")));
        assertThat(response.count()).isEqualTo(SEED_ACTIVE_BELOW_FIVE);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("마지막 페이지를 넘으면 rows 는 비고 count 는 전체 건수, message 는 null")
    void pageBeyondLastKeepsTotal() {
        Page<Item> empty = new PageImpl<>(List.of(), Pageable.ofSize(ItemSearchService.PAGE_SIZE).withPage(1),
            SEED_ACTIVE_BELOW_FIVE);
        when(itemRepository.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(empty);

        ItemSearchResponse response = itemSearchService.search(
            new ItemSearchRequest(null, null, null, null, null, null, "2"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(SEED_ACTIVE_BELOW_FIVE);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("전체 0건이면 message 는 \"검색 결과가 없습니다\"")
    void zeroResultsHaveMessage() {
        when(itemRepository.search(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(6), 0));

        ItemSearchResponse response = itemSearchService.search(request(null, "Z9-99", "6", "없는태그"));

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
    }
}
