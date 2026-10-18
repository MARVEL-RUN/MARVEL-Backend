package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 외적 안내가 일반 옵션과 분리된 필수 코드 조회 계약인지 검증한다. */
class DiscountQueryControllerTest {
    private final DiscountQueryService service = mock(DiscountQueryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DiscountQueryController(service)).build();

    /** enum 전환 후에도 시나리오는 기존 문자열로 직렬화되고 연령은 정책 범위를 반영한다. */
    @ParameterizedTest
    @CsvSource({"0,13", "7,18"})
    void scenarioEnumsPreserveJsonContractAndDynamicAgeDescription(int minimum, int maximum) throws Exception {
        // 일반 설명은 연령에 한정하지 않고, 조건 설명은 정책값으로 구성한다.
        String ageDescription = DiscountExplanation.AGE_CONDITION.getConditionType().describeAgeRange(minimum, maximum);
        assertThat(ageDescription).isEqualTo("대회일 기준 만 " + minimum + "세 이상 " + maximum + "세 미만인 경우");
        PriceOptionResponse standard = new PriceOptionResponse(DiscountExplanation.STANDARD,
                DiscountExplanation.STANDARD.getFixedDescription(), DiscountModels.ApplicationType.PERSONAL,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, List.of());
        PriceOptionResponse age = new PriceOptionResponse(DiscountExplanation.AGE_CONDITION, ageDescription,
                DiscountModels.ApplicationType.PERSONAL, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, List.of());
        when(service.getExternalDiscountOptions("event", "code")).thenReturn(new DiscountOptionsResponse(
                DiscountOptionsResponse.LinkStatus.AVAILABLE, "안내", null, null, null, null,
                DiscountOptionsResponse.ApplicantEligibility.NOT_EVALUATED,
                List.of(new DiscountOptionsResponse.CategoryDiscountOptionsResponse("category", true, List.of(standard, age)))));

        // enum의 부가 필드는 JSON 객체로 노출하지 않는다.
        mvc.perform(get("/v1/public/events/event/discount-options").param("linkCode", "code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].priceOptions[0].scenario").value("STANDARD"))
                .andExpect(jsonPath("$.categories[0].priceOptions[0].description").value("모든 서비스 내 할인 조건에 해당하지 않는 경우"))
                .andExpect(jsonPath("$.categories[0].priceOptions[1].scenario").value("AGE_CONDITION"))
                .andExpect(jsonPath("$.categories[0].priceOptions[1].description").value(ageDescription));
    }

    /** 만료된 링크도 상태 안내를 정상 응답하며 참가자 자격은 미판정으로 표시한다. */
    @Test
    void expiredLinkReturnsInformationalResponse() throws Exception {
        when(service.getExternalDiscountOptions("event", "code")).thenReturn(new DiscountOptionsResponse(
            DiscountOptionsResponse.LinkStatus.EXPIRED, "종료된 링크", null, null, null, null,
            DiscountOptionsResponse.ApplicantEligibility.NOT_EVALUATED, List.of()));
        mvc.perform(get("/v1/public/events/event/discount-options").param("linkCode", "code"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.linkStatus").value("EXPIRED"))
            .andExpect(jsonPath("$.applicantEligibility").value("NOT_EVALUATED"));
    }

    /** 코드 없는 조회를 일반 가격 조회로 암묵 변환하지 않는다. */
    @Test
    void requiresLinkCode() throws Exception {
        mvc.perform(get("/v1/public/events/event/discount-options")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
