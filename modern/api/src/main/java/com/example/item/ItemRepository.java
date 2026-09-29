package com.example.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Integer> {

    /** 단건 조회 — 단원 · 태그를 함께 가져온다(OSIV 꺼져 있음). */
    @EntityGraph(attributePaths = {"unit", "tags"})
    Optional<Item> findWithDetailsById(Integer id);

    /** 단원 코드 + 상태로 조회. 정렬은 난이도 내림차순, 같은 난이도면 id 오름차순. */
    @EntityGraph(attributePaths = {"unit", "tags"})
    @Query("""
        select i from Item i
        where i.unit.code = :unitCode and i.status = :status
        order by i.level desc, i.id asc
        """)
    List<Item> findByUnitCodeAndStatus(@Param("unitCode") String unitCode, @Param("status") String status);

    long countByUnitIdAndStatus(Integer unitId, String status);

    /** 문항 검색 조건. null 인 파라미터는 조건에서 빠진다. 조건끼리는 AND(BR-01). */
    String SEARCH_CONDITION = """
        where i.status = :status
          and (:keyword is null or i.title like :keyword or i.stem like :keyword)
          and (:unitCode is null or u.code = :unitCode)
          and (:levelEq is null or i.level = :levelEq)
          and (:levelBelow is null or i.level < :levelBelow)
          and (:tagName is null or exists (
                select 1 from Item i2 join i2.tags t where i2 = i and t.name = :tagName))
        """;

    /**
     * 문항 검색(레거시 {@code search.php}). 단원 코드 · 태그 이름 비교와 정렬은 DB 콜레이션을 따르도록 SQL 안에서 한다.
     * 태그는 페이지 조회 뒤 트랜잭션 안에서 지연 로딩한다(컬렉션 fetch join 은 메모리 페이징이 되므로 쓰지 않는다).
     */
    @Query(
        value = "select i from Item i join fetch i.unit u " + SEARCH_CONDITION,
        countQuery = "select count(i) from Item i join i.unit u " + SEARCH_CONDITION)
    Page<Item> search(
        @Param("status") String status,
        @Param("keyword") String keyword,
        @Param("unitCode") String unitCode,
        @Param("levelEq") Integer levelEq,
        @Param("levelBelow") Integer levelBelow,
        @Param("tagName") String tagName,
        Pageable pageable);
}
