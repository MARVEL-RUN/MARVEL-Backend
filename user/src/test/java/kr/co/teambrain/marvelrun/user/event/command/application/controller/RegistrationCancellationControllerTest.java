package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import java.util.List;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 취소 API의 경로·본인확인 입력·응답 계약을 검증한다. 실제 권한·환불은 DB 테스트가 담당한다. */
class RegistrationCancellationControllerTest {
    private final RegistrationCancellationCommandService service = mock(RegistrationCancellationCommandService.class);
    private MockMvc mvc;

    /** 실제 요청 바인딩·Bean Validation을 사용하는 MVC 환경을 구성한다. */
    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.standaloneSetup(new RegistrationCancellationController(service)).build(); }

    /** 개인 취소는 수정할 종목이나 금액 없이 현재 본인확인 입력을 전달한다. */
    @Test
    void personalCancellationUsesCurrentAccessOnly() throws Exception {
        when(service.cancelPersonal(eq("event"), eq("registration"), any()))
                .thenReturn(new RegistrationModificationSettlementResult(List.of(), List.of(), List.of()));
        mvc.perform(post("/v1/public/events/event/registrations/registration/cancellation")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"참가자","birth":"1990-01-01","phNum":"010-0000-0000","password":"Test1234!"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(0));
        verify(service).cancelPersonal("event", "registration",
                new RegistrationAccessRequest("참가자", "1990-01-01", "010-0000-0000", "Test1234!"));
    }

    /** 단체 전체 취소는 프론트 명단 없이 단체 인증만 받고 대상 조회를 서버에 위임한다. */
    @Test
    void organizationCancellationDoesNotRequireFrontendRoster() throws Exception {
        when(service.cancelOrganization(eq("event"), eq("organization"), any()))
                .thenReturn(new RegistrationModificationSettlementResult(List.of(), List.of(), List.of()));
        mvc.perform(post("/v1/public/events/event/organizations/organization/cancellation")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"loginId":"group-login","password":"Test1234!"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(0));
        verify(service).cancelOrganization("event", "organization", new OrganizationAccessRequest("group-login", "Test1234!"));
    }

    /** 본인확인 누락은 금융 서비스를 호출하기 전에 거절한다. */
    @Test
    void missingAccessIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(post("/v1/public/events/event/registrations/registration/cancellation")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/public/events/event/organizations/organization/cancellation")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
