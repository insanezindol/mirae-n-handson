package com.example.item;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 서비스 — 레거시 {@code legacy/item-bank-php/search.php} 의 이관.
 * 레거시 동작(의심 동작 포함)을 그대로 옮긴다. 규칙 ID 는 {@code docs/item-bank/BUSINESS-RULES.md} 를 따른다.
 * 레거시는 입력값이 이상해도 오류로 막지 않고 조회하므로, 이 서비스도 예외를 던지지 않고 0건으로 응답한다.
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    /** 키워드 최대 글자 수(BR-05). */
    static final int KEYWORD_MAX_LENGTH = 100;
    /** 태그 이름 최대 글자 수(BR-14). */
    static final int TAG_MAX_LENGTH = 50;
    /** 한 페이지 건수(BR-21). */
    static final int PAGE_SIZE = 20;
    /** 페이지 번호 상한(BR-21). */
    static final int PAGE_MAX = 999;
    /** 난이도를 비우면 이 난이도 이상을 뺀다(BR-10, {@code level < 5}). */
    static final int DEFAULT_LEVEL_UPPER_BOUND = 5;
    /** 전체 건수가 0 일 때 문구(BR-22). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    /** 난이도 정규식 {@code /^[1-5]$/}. PHP 처럼 {@code $} 는 끝의 \n 앞에서도 맞는다(UNIX_LINES). */
    private static final Pattern LEVEL_PATTERN = Pattern.compile("^[1-5]$", Pattern.UNIX_LINES);
    /** 페이지 정규식 {@code /^[0-9]+$/}. */
    private static final Pattern PAGE_PATTERN = Pattern.compile("^[0-9]+$", Pattern.UNIX_LINES);
    /** 페이지 파라미터가 없을 때의 값({@code search.php:77}). */
    private static final String DEFAULT_PAGE_RAW = "1";

    private static final String SORT_ID = "id";
    private static final String SORT_TITLE = "title";
    private static final String SORT_UNIT = "unit";
    private static final String SORT_LEVEL = "level";
    private static final String SORT_CREATED = "created";
    private static final String DIR_ASC = "asc";
    private static final String DIR_DESC = "desc";

    /** 기본 정렬: 난이도 내림차순, 같으면 id 오름차순(BR-16). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id"));

    private final ItemRepository itemRepository;

    public ItemSearchService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /** 문항 검색. 공개({@code status='A'}) 문항만 찾는다(BR-02). */
    public ItemSearchResponse search(ItemSearchRequest request) {
        int page = resolvePage(request.page());
        Page<Item> result = itemRepository.search(
            ItemStatus.ACTIVE,
            resolveKeyword(request.q()),
            blankToNull(PhpStrings.trim(request.unit())),
            resolveLevelEq(request.level()),
            resolveLevelBelow(request.level()),
            resolveTag(request.tag()),
            PageRequest.of(page - 1, PAGE_SIZE, resolveSort(request.sort(), request.dir())));

        List<ItemSearchRowResponse> rows = result.getContent().stream()
            .map(ItemSearchRowResponse::from)
            .toList();
        long total = result.getTotalElements();
        log.debug("item search page={} rows={} total={}", page, rows.size(), total);
        return new ItemSearchResponse(rows, total, total == 0 ? NO_RESULT_MESSAGE : null);
    }

    /**
     * 키워드 → LIKE 패턴(BR-04 · 05 · 06). trim 후 100자로 자르고, 비었으면 조건 없음.
     * {@code %} · {@code _} 는 이스케이프하지 않고 와일드카드로 쓴다({@code search.php:85-98}).
     */
    private static String resolveKeyword(String raw) {
        String q = PhpStrings.truncate(PhpStrings.trim(raw), KEYWORD_MAX_LENGTH);
        return q.isEmpty() ? null : "%" + q + "%";
    }

    /** 난이도 = 조건(BR-11 · 12). 비었으면 null, {@code 1~5} 가 아니면 PHP {@code (int)} 변환 값({@code search.php:217-229}). */
    private static Integer resolveLevelEq(String raw) {
        String level = PhpStrings.trim(raw);
        if (level.isEmpty()) {
            return null;
        }
        if (LEVEL_PATTERN.matcher(level).find()) {
            return Integer.valueOf(level.substring(0, 1));
        }
        return PhpStrings.toInt(level);
    }

    /** 난이도 &lt; 조건(BR-10). 난이도가 비었을 때만 {@code level < 5} ({@code search.php:214-215}). */
    private static Integer resolveLevelBelow(String raw) {
        return PhpStrings.trim(raw).isEmpty() ? DEFAULT_LEVEL_UPPER_BOUND : null;
    }

    /** 태그 이름(BR-13 · 14). trim 후 50자로 자르고, 비었으면 조건 없음({@code search.php:235-247}). */
    private static String resolveTag(String raw) {
        return blankToNull(PhpStrings.truncate(PhpStrings.trim(raw), TAG_MAX_LENGTH));
    }

    /**
     * 정렬(BR-16 ~ 19, {@code search.php:266-326}). 기준 · 방향은 trim 후 ASCII 소문자로 비교한다.
     * 방향이 asc · desc 가 아니면 무시한다. 2차 키 id 는 방향과 상관없이 오름차순이다.
     */
    static Sort resolveSort(String rawSort, String rawDir) {
        String sort = PhpStrings.asciiLower(PhpStrings.trim(rawSort));
        String dir = PhpStrings.asciiLower(PhpStrings.trim(rawDir));
        boolean desc = DIR_DESC.equals(dir);
        boolean asc = DIR_ASC.equals(dir);
        return switch (sort) {
            case SORT_ID -> Sort.by(desc ? Sort.Order.desc("id") : Sort.Order.asc("id"));
            case SORT_TITLE -> Sort.by(
                desc ? Sort.Order.desc("title") : Sort.Order.asc("title"),
                Sort.Order.asc("id"));
            case SORT_UNIT -> Sort.by(
                desc ? Sort.Order.desc("unit.code") : Sort.Order.asc("unit.code"),
                Sort.Order.desc("level"),
                Sort.Order.asc("id"));
            case SORT_LEVEL -> Sort.by(
                asc ? Sort.Order.asc("level") : Sort.Order.desc("level"),
                Sort.Order.asc("id"));
            case SORT_CREATED -> Sort.by(
                asc ? Sort.Order.asc("createdAt") : Sort.Order.desc("createdAt"),
                Sort.Order.asc("id"));
            default -> DEFAULT_SORT;
        };
    }

    /**
     * 페이지 번호(BR-21, {@code search.php:336-348}). trim 하지 않는다.
     * 숫자만이면 그 값, 아니면 1. 1 미만은 1, 999 초과는 999.
     */
    static int resolvePage(String raw) {
        String pageRaw = raw == null ? DEFAULT_PAGE_RAW : raw;
        if (!PAGE_PATTERN.matcher(pageRaw).find()) {
            return 1;
        }
        BigInteger page = new BigInteger(pageRaw.strip());
        if (page.compareTo(BigInteger.ONE) < 0) {
            return 1;
        }
        return page.min(BigInteger.valueOf(PAGE_MAX)).intValue();
    }

    private static String blankToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
