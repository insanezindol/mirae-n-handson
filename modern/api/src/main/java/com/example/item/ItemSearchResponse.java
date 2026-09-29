package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답.
 * {@code count} 는 현재 페이지 행 수가 아니라 조건에 맞는 전체 건수다.
 * {@code message} 는 전체 건수가 0 이면 "검색 결과가 없습니다", 아니면 null 이다(BR-22).
 */
public record ItemSearchResponse(
    List<ItemSearchRowResponse> rows,
    long count,
    String message) {
}
