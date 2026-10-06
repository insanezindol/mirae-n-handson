package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

/**
 * 문항 검색 쿼리 — H2(MariaDB 모드)에 실제 SQL 을 날린다.
 * H2 는 utf8mb4_unicode_ci 콜레이션이 없어 소문자 단원 코드(BR-41)는 여기서 확인하지 않는다(동작 보존 테스트가 확인).
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    private static final int LEVEL_UPPER_BOUND = 5;
    private static final int PAGE_SIZE = 20;
    private static final int SMALL_PAGE_SIZE = 2;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, word, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 분수", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수", 4, ItemStatus.DELETED, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 4, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private Page<Item> search(String keyword, String unitCode, Integer levelEq, Integer levelBelow, String tagName,
        Pageable pageable) {
        return itemRepository.search(ItemStatus.ACTIVE, keyword, unitCode, levelEq, levelBelow, tagName, pageable);
    }

    private static Pageable firstPage() {
        return PageRequest.of(0, PAGE_SIZE, ItemSearchService.resolveSort(null, null));
    }

    @Test
    @DisplayName("조건 없음 + level < 5: 공개 문항만, 난이도 5 제외, level DESC · id ASC")
    void defaultSearchExcludesNonActiveAndLevelFive() {
        Page<Item> page = search(null, null, null, LEVEL_UPPER_BOUND, null, firstPage());

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Item::getTitle)
            .containsExactly("분수 문장제", "소수 곱셈", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("키워드 · 단원 · 난이도 · 태그는 AND 로 묶인다")
    void combinesConditionsWithAnd() {
        Page<Item> page = search("%분수%", "M5-1", 4, null, "계산", firstPage());

        assertThat(page.getContent()).extracting(Item::getTitle).containsExactly("분수 문장제");
    }

    @Test
    @DisplayName("키워드는 지문에서도 찾고, % 는 와일드카드로 전체와 일치한다")
    void keywordMatchesStemAndPercentMatchesAll() {
        assertThat(search("%문제 본문%", null, LEVEL_UPPER_BOUND, null, null, firstPage()).getTotalElements())
            .isEqualTo(1);
        assertThat(search("%%%", null, null, null, null, firstPage()).getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("태그 이름이 정확히 같은 문항만, 없는 태그 · 없는 단원은 0건")
    void filtersByTagNameAndReturnsZeroForUnknown() {
        assertThat(search(null, null, null, null, "문장제", firstPage()).getContent()).extracting(Item::getTitle)
            .containsExactly("난이도 5 분수", "분수 문장제");
        assertThat(search(null, null, null, null, "없는태그", firstPage()).getTotalElements()).isZero();
        assertThat(search(null, "Z9-99", null, null, null, firstPage()).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("단원 정렬: unit_code 방향 뒤에 level DESC · id ASC, 태그는 id 순으로 로드")
    void sortsByUnitCodeThenLevel() {
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, ItemSearchService.resolveSort("unit", "desc"));

        Page<Item> page = search(null, null, null, null, null, pageable);

        assertThat(page.getContent()).extracting(Item::getTitle)
            .containsExactly("소수 곱셈", "난이도 5 분수", "분수 문장제", "분모가 같은 분수의 덧셈");
        assertThat(page.getContent().get(2).getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }

    @Test
    @DisplayName("마지막 페이지를 넘으면 행은 비고 전체 건수는 그대로")
    void pageBeyondLastKeepsTotal() {
        Pageable pageable = PageRequest.of(SMALL_PAGE_SIZE, SMALL_PAGE_SIZE, ItemSearchService.resolveSort(null, null));

        Page<Item> page = search(null, null, null, LEVEL_UPPER_BOUND, null, pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("한 페이지의 태그는 행마다가 아니라 쿼리 한 번으로 묶어 가져온다")
    void loadsTagsOfAPageInOneBatch() {
        Page<Item> page = search(null, null, null, null, null, firstPage());
        Statistics statistics = entityManager.getEntityManager().getEntityManagerFactory()
            .unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        page.getContent().forEach(item -> item.getTags().size());

        assertThat(page.getContent()).hasSizeGreaterThan(1);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTags()).extracting(Tag::getName).containsExactly("문장제");
    }
}
