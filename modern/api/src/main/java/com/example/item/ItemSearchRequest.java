package com.example.item;

import java.util.List;
import java.util.Map;
import org.springframework.util.MultiValueMap;

/**
 * {@code GET /api/items/search} 요청. 레거시 {@code search.php} 의 GET 파라미터 7개를 받은 문자열 그대로 담는다.
 * 파라미터가 없으면 null 이다. trim · 자르기 · 변환은 {@link ItemSearchService} 가 한다.
 * 레거시는 어떤 입력도 오류로 막지 않으므로(경고 후 조회) Bean Validation 제약을 두지 않는다.
 */
public record ItemSearchRequest(
    String q,
    String unit,
    String level,
    String tag,
    String sort,
    String dir,
    String page) {

    /** 쿼리 파라미터 → 요청. 파라미터 이름은 레거시와 같다. */
    public static ItemSearchRequest from(MultiValueMap<String, String> params) {
        return new ItemSearchRequest(
            pick(params, "q"),
            pick(params, "unit"),
            pick(params, "level"),
            pick(params, "tag"),
            pick(params, "sort"),
            pick(params, "dir"),
            pick(params, "page"));
    }

    /**
     * PHP {@code $_GET} 과 같게 값 하나를 고른다(BR-03, {@code search.php:53-80}).
     * <ul>
     *   <li>{@code name=a&name=b} → 뒤 값 b (PHP 는 같은 이름을 덮어쓴다)</li>
     *   <li>{@code name[]=a&name[]=b} → 첫 값 a ({@code reset()})</li>
     * </ul>
     * 두 형태가 섞이면 파라미터 이름이 처음 나온 순서대로 덮어쓴다(이름 사이의 원래 순서는 알 수 없다).
     */
    private static String pick(MultiValueMap<String, String> params, String name) {
        String picked = null;
        boolean arrayPicked = false;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (key.equals(name)) {
                picked = values.get(values.size() - 1);
                arrayPicked = false;
            } else if (!arrayPicked && key.startsWith(name + "[") && key.endsWith("]")) {
                picked = values.get(0);
                arrayPicked = true;
            }
        }
        return picked;
    }
}
