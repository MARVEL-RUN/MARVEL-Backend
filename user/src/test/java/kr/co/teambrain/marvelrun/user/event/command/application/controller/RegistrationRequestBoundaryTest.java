package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import org.junit.jupiter.api.Disabled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationQueryResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.OrgRegistrationParticipantResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.query.controller.OrgRegistrationQueryController;
import kr.co.teambrain.marvelrun.user.event.query.controller.RegistrationQueryController;
import kr.co.teambrain.marvelrun.user.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRetryPreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MVC 검증이 인증·생성 요청의 필수값 오류를 서비스 호출 전에 차단하는지 확인한다. */
class RegistrationRequestBoundaryTest {
    private static final String ROOT = "/v1/public/events/event";
    private final ObjectMapper json = new ObjectMapper();
    private final RegistrationQueryService queries = mock(RegistrationQueryService.class);
    private final RegistrationModificationCommandService modifications = mock(RegistrationModificationCommandService.class);
    private final PaymentRetryPreparationService retries = mock(PaymentRetryPreparationService.class);
    private final RegistrationStartGroupModificationService startGroups = mock(RegistrationStartGroupModificationService.class);
    private final AdditionalPaymentPreparationService additional = mock(AdditionalPaymentPreparationService.class);
    private final RegistrationCancellationCommandService cancellations = mock(RegistrationCancellationCommandService.class);
    private final RegistrationCommandService personalCreation = mock(RegistrationCommandService.class);
    private final OrgRegistrationCommandService organizationCreation = mock(OrgRegistrationCommandService.class);
    private MockMvc mvc;

    /** 그룹 입력 누락 및 구버전 필드 전달 모두 개인 신청·일반 수정을 방해하지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsPersonalRequestsWithoutActiveGroupFields(boolean legacy) throws Exception {
        // 나머지 필수 입력을 갖춘 요청을 준비한다.
        ObjectNode body = (ObjectNode) json.readTree("""
                {"eventCategoryId":"category","selectedSouvenirList":[{"souvenirId":"souvenir","selectedSize":"M"}],
                 "name":"테스트","phNum":"010-1234-5678","birth":"1990-01-01","gender":"M",
                 "password":"test-password","address":"주소","guardianConsent":false,
                 "termsEssentialAgreed":true,"termsMarketingAgreed":false,"termsMarketingChannelAgreed":false}
                """);
        if (legacy) { body.put("startGroupId", "obsolete").put("startGroupUnselected", false); }

        // 외부 그룹값은 DTO에 들어오지 않으며 생성·일반 수정은 계속 호출된다.
        mvc.perform(post(ROOT + "/registrations").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated());
        verify(personalCreation).register(eq("event"), argThat(request ->
                request.startGroupId() == null && request.startGroupUnselected() == null));
        body.remove(List.of("password", "termsEssentialAgreed", "termsMarketingAgreed", "termsMarketingChannelAgreed"));
        body.set("access", personalAccess());
        mvc.perform(patch(ROOT + "/registrations/r").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());
        verify(modifications).modifyPersonalRegistration(eq("event"), eq("r"), argThat(request ->
                request.startGroupId() == null && request.startGroupUnselected() == null));
        verifyNoInteractions(startGroups);
    }

    /** 그룹 전용 경로의 매핑은 제거되어 기존 주소로 호출할 수 없다. */
    @Test void groupOnlyEndpointsAreNotMapped() throws Exception {
        for (String path : List.of("/registrations/r/start-group", "/organizations/o/registrations/start-groups")) {
            assertThat(mvc.perform(patch(ROOT + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getHandler()).isNull();
        }
        verifyNoInteractions(startGroups);
    }

    /** 신청 상세·단체원·선택지 응답에서 그룹 키 자체가 직렬화되지 않는다. */
    @Test void publicResponsesDoNotExposeGroupFields() throws Exception {
        String stored = "{\"startGroupId\":\"old\",\"startGroupCode\":\"B\",\"startGroupUnselected\":true}";
        for (Class<?> type : List.of(RegistrationQueryResponse.class, OrgRegistrationParticipantResponse.class)) {
            String response = json.writeValueAsString(json.readValue(stored, type));
            assertThat(response).doesNotContain("startGroup");
        }
        RegistrationOptionResponse options = new RegistrationOptionResponse(List.of(
                new RegistrationOptionResponse.EventCategoryOption(1, "category", "5km", "일반", true,
                        null, List.of(), List.of(new RegistrationOptionResponse.StartGroupOption("old", "B", "안내")))),
                true, null);
        assertThat(json.writeValueAsString(options)).doesNotContain("startGroups", "안내");
    }

    /** 서버와 DB 없이 운영 컨트롤러의 요청 바인딩 및 Bean Validation을 연결한다. */
    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.standaloneSetup(
                new RegistrationQueryController(queries), new OrgRegistrationQueryController(queries),
                new RegistrationModificationController(modifications, retries, startGroups),
                new AdditionalPaymentController(additional), new RegistrationCancellationController(cancellations),
                new RegistrationCommandController(personalCreation, mock(RegistrationPasswordChangeService.class)),
                new OrgRegistrationCommandController(organizationCreation, mock(RegistrationPasswordChangeService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    /** 직접 AccessRequest를 받는 모든 API에서 각 필드의 null·빈 문자열·공백을 차단한다. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidDirectAccessFields(String invalid) throws Exception {
        for (String field : List.of("name", "birth", "phNum", "password")) {
            ObjectNode access = personalAccess();
            access.put(field, invalid);
            for (String endpoint : personalEndpoints()) {
                reject(endpoint, false, access, field);
            }
        }
        for (String field : List.of("loginId", "password")) {
            ObjectNode access = organizationAccess();
            access.put(field, invalid);
            for (String endpoint : organizationEndpoints()) {
                reject(endpoint, false, access, field);
            }
        }
        assertServicesUntouched();
    }

    /** 수정 DTO의 중첩 access와 단체 생성 account 내부까지 검증이 전파되는지 확인한다. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidNestedAndCreationFields(String invalid) throws Exception {
        for (String field : List.of("name", "birth", "phNum", "password")) {
            ObjectNode access = personalAccess();
            access.put(field, invalid);
            ObjectNode body = json.createObjectNode();
            body.set("access", access);
            reject("/registrations/r", true, body, "access." + field);
        }
        for (String field : List.of("loginId", "password")) {
            ObjectNode access = organizationAccess();
            access.put(field, invalid);
            ObjectNode body = json.createObjectNode();
            body.set("access", access);
            reject("/organizations/o/registrations", true, body, "access." + field);
        }
        reject("/registrations", false, json.createObjectNode().put("password", invalid), "password");
        ObjectNode group = json.createObjectNode();
        group.set("account", json.createObjectNode().put("organizationPassword", invalid));
        reject("/registrations/organization", false, group, "account.organizationPassword");
        assertServicesUntouched();
    }

    /** 중첩 객체 자체가 누락되거나 null이면 필드 검증 이전에 NotNull로 거부한다. */
    @Test
    void rejectsMissingNestedObjects() throws Exception {
        for (boolean explicitNull : List.of(false, true)) {
            ObjectNode modification = json.createObjectNode();
            ObjectNode creation = json.createObjectNode();
            if (explicitNull) {
                modification.putNull("access");
                creation.putNull("account");
            }
            reject("/registrations/r", true, modification, "access");
            reject("/organizations/o/registrations", true, modification, "access");
            reject("/registrations/organization", false, creation, "account");
        }
        assertServicesUntouched();
    }

    /** RequestBody 자체의 누락과 JSON null도 컨트롤러 실행 전에 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null"})
    void rejectsMissingRootBody(String body) throws Exception {
        for (String endpoint : personalEndpoints()) {
            rejectMissingBody(post(ROOT + endpoint), body);
        }
        for (String endpoint : organizationEndpoints()) {
            rejectMissingBody(post(ROOT + endpoint), body);
        }
        for (String endpoint : List.of("/registrations", "/registrations/organization")) {
            rejectMissingBody(post(ROOT + endpoint), body);
        }
        for (String endpoint : List.of("/registrations/r", "/organizations/o/registrations")) {
            rejectMissingBody(patch(ROOT + endpoint), body);
        }
        assertServicesUntouched();
    }

    /** 본문 누락은 바인딩 단계에서 거부한다. 기존 일반 예외 처리의 상태 코드 정책은 별도 과제다. */
    private void rejectMissingBody(MockHttpServletRequestBuilder request, String body) throws Exception {
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> {
                    assertThat(result.getResolvedException()).isInstanceOf(HttpMessageNotReadableException.class);
                    assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
                });
    }

    /** 다른 필드 오류에 가려지지 않도록 해당 중첩 경로의 검증 오류까지 확인한다. */
    private void reject(String endpoint, boolean modification, ObjectNode body, String field) throws Exception {
        MockHttpServletRequestBuilder request = modification ? patch(ROOT + endpoint) : post(ROOT + endpoint);
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOfSatisfying(MethodArgumentNotValidException.class,
                                exception -> assertThat(exception.getBindingResult().getFieldError(field)).isNotNull()));
    }

    /** 정상 개인 인증 입력을 기준으로 개별 필드만 변형한다. */
    private ObjectNode personalAccess() {
        return json.createObjectNode().put("name", "참가자").put("birth", "1990-01-01")
                .put("phNum", "010-0000-0000").put("password", "TestPassword1!");
    }

    /** 정상 단체 인증 입력을 기준으로 개별 필드만 변형한다. */
    private ObjectNode organizationAccess() {
        return json.createObjectNode().put("loginId", "group-login").put("password", "TestPassword1!");
    }

    /** 개인 인증 DTO를 직접 받는 현재 HTTP 진입점을 나열한다. */
    private List<String> personalEndpoints() {
        return List.of("/registrations/lookup", "/registrations/r/cancellation",
                "/registrations/r/payments/additional/prepare", "/registrations/r/payments/p/retry");
    }

    /** 단체 인증 DTO를 직접 받는 현재 HTTP 진입점을 나열한다. */
    private List<String> organizationEndpoints() {
        return List.of("/organizations/lookup", "/organizations/o/cancellation",
                "/organizations/o/payments/additional/prepare", "/organizations/o/payments/p/retry");
    }

    /** 그룹 전용 요청의 누락·조합 오류는 서비스 호출 전에 차단한다. */
    @Test
    @Disabled("그룹 기능 임시 중단: 재활성화 시 복원")
    void rejectsInvalidStartGroupSelections() throws Exception {
        ObjectNode missing = json.createObjectNode().put("password", "test");
        reject("/registrations/r/start-group", true, missing, "startGroupUnselected");
        ObjectNode invalid = json.createObjectNode().put("password", "test").put("startGroupUnselected", false);
        reject("/registrations/r/start-group", true, invalid, "startGroupSelectionValid");
        ObjectNode organization = json.createObjectNode().put("password", "test");
        organization.putArray("registrations").addObject().put("registrationId", "r").put("startGroupUnselected", false);
        reject("/organizations/o/registrations/start-groups", true, organization,
                "registrations[0].startGroupSelectionValid");
        verifyNoInteractions(startGroups);
    }

    /** 미선택은 null ID를 허용하고 단체는 각 참가자의 선택을 그대로 전달한다. */
    @Test
    @Disabled("그룹 기능 임시 중단: 재활성화 시 복원")
    void acceptsPersonalAndBulkOrganizationStartGroups() throws Exception {
        mvc.perform(patch(ROOT + "/registrations/r/start-group").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"password":"test","startGroupId":null,"startGroupUnselected":true}
                        """)).andExpect(status().isNoContent());
        mvc.perform(patch(ROOT + "/organizations/o/registrations/start-groups").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"password":"test","registrations":[
                          {"registrationId":"r1","startGroupId":"g1","startGroupUnselected":false},
                          {"registrationId":"r2","startGroupId":null,"startGroupUnselected":true}]}
                        """)).andExpect(status().isNoContent());
        verify(startGroups).modifyPersonalRegistrationStartGroup(eq("event"), eq("r"), any());
        verify(startGroups).modifyOrganizationRegistrationStartGroups(eq("event"), eq("o"), any());
        verifyNoInteractions(modifications, retries);
    }

    /** 필수값 오류가 업무 서비스와 저장·인증 로직까지 전파되지 않아야 한다. */
    private void assertServicesUntouched() {
        verifyNoInteractions(queries, modifications, retries, additional, cancellations,
                personalCreation, organizationCreation, startGroups);
    }
}
