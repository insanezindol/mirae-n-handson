package com.example.item;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문항 검색 API — 레거시 {@code search.php} 의 이관. 컨트롤러는 서비스만 호출한다.
 * 파라미터는 모두 문자열로 받는다. 숫자 타입으로 받으면 {@code level=abc} 가 400 이 되지만 레거시는 200 · 0건이다.
 */
@RestController
@RequestMapping("/api/items")
public class ItemSearchController {

    private final ItemSearchService itemSearchService;

    public ItemSearchController(ItemSearchService itemSearchService) {
        this.itemSearchService = itemSearchService;
    }

    /** {@code GET /api/items/search?q=&unit=&level=&tag=&sort=&dir=&page=} — 공개 문항 검색. */
    @GetMapping("/search")
    public ItemSearchResponse search(@RequestParam MultiValueMap<String, String> params) {
        return itemSearchService.search(ItemSearchRequest.from(params));
    }
}
