package kr.co.teambrain.marvelrun.user.discount.query;

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
