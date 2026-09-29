package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 컨트롤러 슬라이스 — 서비스는 목. 파라미터 바인딩과 응답 JSON 모양을 확인한다. */
@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    private ItemSearchRequest capturedRequest() {
        ArgumentCaptor<ItemSearchRequest> captor = ArgumentCaptor.forClass(ItemSearchRequest.class);
        verify(itemSearchService).search(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("GET /api/items/search → 200, {rows, count, message} 이고 rows 에는 id · title · unit · level · tags 만")
    void returnsNormalizedShape() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(
            List.of(new ItemSearchRowResponse(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"))), 1, null));

        mockMvc.perform(get("/api/items/search").param("q", "분수").param("unit", "M5-1").param("level", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.message").doesNotExist())
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.rows[0].id").value(12))
            .andExpect(jsonPath("$.rows[0].title").value("대분수의 덧셈"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(3))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());

        assertThat(capturedRequest()).isEqualTo(new ItemSearchRequest("분수", "M5-1", "3", null, null, null, null));
    }

    @Test
    @DisplayName("0건이면 200 과 message \"검색 결과가 없습니다\"")
    void zeroResultsReturn200WithMessage() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("unit", "Z9-99"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("숫자가 아닌 level · page 도 400 이 아니라 문자열 그대로 서비스로 넘긴다")
    void nonNumericValuesAreNotRejected() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("level", "abc").param("page", "-1"))
            .andExpect(status().isOk());

        ItemSearchRequest request = capturedRequest();
        assertThat(request.level()).isEqualTo("abc");
        assertThat(request.page()).isEqualTo("-1");
    }

    @Test
    @DisplayName("배열 파라미터 q[] 는 첫 값, 같은 이름이 반복되면 마지막 값(PHP $_GET 과 같음)")
    void picksValuesLikePhp() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("q[]", "소수", "분수").param("tag", "계산", "개념"))
            .andExpect(status().isOk());

        ItemSearchRequest request = capturedRequest();
        assertThat(request.q()).isEqualTo("소수");
        assertThat(request.tag()).isEqualTo("개념");
    }

    @Test
    @DisplayName("빈 값 파라미터는 빈 문자열, 없는 파라미터는 null 로 넘긴다")
    void distinguishesEmptyFromMissing() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").param("level", ""))
            .andExpect(status().isOk());

        ItemSearchRequest request = capturedRequest();
        assertThat(request.level()).isEmpty();
        assertThat(request.page()).isNull();
    }
}
