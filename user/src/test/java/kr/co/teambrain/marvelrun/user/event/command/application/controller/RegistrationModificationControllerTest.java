package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationCommandService;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRetryPreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 매핑·요청 검증·응답 계약을 검증한다. 실제 금융 및 권한 판정은 별도 DB 테스트가 담당한다. */
class RegistrationModificationControllerTest {
    private final RegistrationModificationCommandService modifications = mock(RegistrationModificationCommandService.class);
    private final PaymentRetryPreparationService retries = mock(PaymentRetryPreparationService.class);
    private MockMvc mvc;

    /** 컨트롤러와 실제 MVC 요청 바인딩을 구성한다. 외부 서버는 실행하지 않는다. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RegistrationModificationController(modifications, retries)).build();
    }

    /** 개인 수정 요청은 DB 처리만 하는 서비스가 아닌 환불까지 연결하는 facade로 전달한다. */
    @Test
    void personalModificationReturnsSingleOrder() throws Exception {
        when(modifications.modifyPersonal(eq("event"), eq("registration"), any())).thenReturn(result());
        mvc.perform(patch("/v1/public/events/event/registrations/registration")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"access":{"name":"참가자","birth":"1990-01-01","phNum":"010-1234-5678","password":"test"},
                 "eventCategoryId":"category","selectedSouvenirList":[{"souvenirId":"shirt","selectedSize":"S"}],
                 "name":"수정이름","birth":"1990-01-01","phNum":"010-1234-5678","gender":"M"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].amount").value(60000));
        verify(modifications).modifyPersonal(eq("event"), eq("registration"), any(RegistrationModificationRequest.class));
        verifyNoInteractions(retries);
    }

    /** 단체는 구 명단이나 계산 금액 없이 최종 명단 하나만 받아 기존 서버 검증으로 전달한다. */
    @Test
    void organizationAcceptsOnlyDesiredFinalList() throws Exception {
        when(modifications.modifyOrganization(eq("event"), eq("org"), any())).thenReturn(result());
        mvc.perform(patch("/v1/public/events/event/organizations/org/registrations")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"test@example.com","address":"테스트 주소","addressDetail":"상세",
                 "leaderName":"테스트 단체장","leaderBirth":"1990-01-01","leaderPhNum":"010-0000-0000",
                 "access":{"loginId":"leader","password":"test"},"registrations":[
                 {"registrationId":"existing","eventCategoryId":"category",
                  "selectedSouvenirList":[{"souvenirId":"shirt","selectedSize":"S"}],
                  "name":"기존참가자","phNum":"010-1234-5678","birth":"1990-01-01","gender":"M"},
                 {"registrationId":null,"eventCategoryId":"category",
                  "selectedSouvenirList":[{"souvenirId":"shirt","selectedSize":"S"}],
                  "name":"신규참가자","phNum":"010-1234-5678","birth":"1991-01-01","gender":"M"}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(1));
        ArgumentCaptor<OrgRegistrationModificationRequest> captured = ArgumentCaptor.forClass(OrgRegistrationModificationRequest.class);
        verify(modifications).modifyOrganization(eq("event"), eq("org"), captured.capture());
        assertThat(captured.getValue().registrations()).hasSize(2);
        assertThat(captured.getValue().registrations().get(0).registrationId()).isEqualTo("existing");
        assertThat(captured.getValue().registrations().get(1).registrationId()).isNull();
    }

    /** 인증정보가 없는 개인 수정 요청을 업무 서비스 호출 전에 거절한다. */
    @Test
    void missingPersonalAccessIsBadRequest() throws Exception {
        mvc.perform(patch("/v1/public/events/event/registrations/registration")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(modifications, retries);
    }

    /** 빈 최종 명단은 현재 계약상 단체 전체 취소로 해석하지 않는다. */
    @Test
    void emptyOrganizationListIsBadRequest() throws Exception {
        mvc.perform(patch("/v1/public/events/event/organizations/org/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {"email":"test@example.com","address":"테스트 주소","leaderName":"테스트 단체장",
                 "leaderBirth":"1990-01-01","leaderPhNum":"010-0000-0000",
                 "access":{"loginId":"leader","password":"test"},"registrations":[]}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(modifications, retries);
    }

    /** 개인 재준비는 본인확인 정보와 원 주문 ID를 전달한다. */
    @Test
    void personalRetryUsesSharedPreparation() throws Exception {
        when(retries.preparePersonal(eq("event"), eq("registration"), eq("failed"), any())).thenReturn(order());
        mvc.perform(post("/v1/public/events/event/registrations/registration/payments/failed/retry")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"참가자","birth":"1990-01-01","phNum":"010-1234-5678","password":"test"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value("ready"));
        verify(retries).preparePersonal(eq("event"), eq("registration"), eq("failed"), any(RegistrationAccessRequest.class));
    }

    /** 단체 재준비도 목적별 분기 API 없이 주문 한 건을 반환한다. */
    @Test
    void organizationRetryReturnsOneOrder() throws Exception {
        when(retries.prepareOrganization(eq("event"), eq("org"), eq("failed"), any())).thenReturn(order());
        mvc.perform(post("/v1/public/events/event/organizations/org/payments/failed/retry")
                .contentType(MediaType.APPLICATION_JSON).content("{\"loginId\":\"leader\",\"password\":\"test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(60000));
        verify(retries).prepareOrganization(eq("event"), eq("org"), eq("failed"), any(OrganizationAccessRequest.class));
    }

    /** 주문이 하나인 수정 응답을 구성한다. */
    private RegistrationModificationSettlementResult result() {
        return new RegistrationModificationSettlementResult(List.of(), List.of(order()), List.of());
    }

    /** 기존 수정 응답의 결제창 주문 형식을 사용한다. */
    private Order order() {
        return new Order("ready", "order", "참가비", new BigDecimal("60000"));
    }
}
