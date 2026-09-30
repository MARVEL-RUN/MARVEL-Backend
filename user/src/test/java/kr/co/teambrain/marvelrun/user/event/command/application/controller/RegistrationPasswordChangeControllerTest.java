package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationPasswordChangeService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.OrgRegistrationCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 요청 바인딩·생성 공통 정책·성공 및 업무 오류 응답을 DB 없이 검증한다. */
class RegistrationPasswordChangeControllerTest {

    private static final String PERSONAL = "/v1/public/events/event/registrations/target/password";

    private static final String ORGANIZATION = "/v1/public/events/event/organizations/target/password";

    private final ObjectMapper json = new ObjectMapper();

    private final RegistrationPasswordChangeService service = mock(RegistrationPasswordChangeService.class);

    private final OrgRegistrationCommandService organizationService = mock(OrgRegistrationCommandService.class);

    private MockMvc mvc;

    /** 운영 예외 처리기를 포함한 MVC 입력 검증 환경을 구성한다. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new RegistrationCommandController(mock(RegistrationCommandService.class), service),
                        new OrgRegistrationCommandController(organizationService, service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 개인은 최소 길이를 추가하지 않고 단체는 기존 6자 하한을 유지하며 204로 응답한다. */
    @Test
    void acceptsCreationPolicyAndReturnsNoContent() throws Exception {
        send(PERSONAL, "old", "a", 204);
        send(ORGANIZATION, "old", "abcdef", 204);

        verify(service).changePersonal("event", "target", new PersonalPasswordChangeRequest("old", "a"));
        verify(service).changeOrganization("event", "target", new OrganizationPasswordChangeRequest("old", "abcdef"));
    }

    /** 컨트롤러 기본 경로 조정 후에도 기존 단체 생성·중복 확인 URL을 유지한다. */
    @Test
    void preservesOrganizationRoutes() throws Exception {
        String base = "/v1/public/events/event/registrations/organization";

        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(base + "/duplicate-id-check").param("groupLoginId", "group-id"))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/duplicate-name-check").param("groupName", "group-name"))
                .andExpect(status().isOk());

        verify(organizationService).checkExistsLoginId("group-id", "event");
        verify(organizationService).checkExistsGroupName("group-name", "event");
        verifyNoInteractions(service);
    }

    /** 기존·신규 비밀번호의 null·빈 문자열·공백 입력을 서비스 호출 전에 차단한다. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankPasswords(String invalid) throws Exception {
        for (String path : List.of(PERSONAL, ORGANIZATION)) {
            send(path, invalid, "NewPassword1!", 400);
            send(path, "old", invalid, 400);
        }

        verifyNoInteractions(service);
    }

    /** 생성 DTO와 공유한 개인 127자 상한·단체 6~64자 범위를 검증한다. */
    @Test
    void rejectsInvalidNewPasswordLength() throws Exception {
        send(PERSONAL, "old", "a".repeat(128), 400);
        send(ORGANIZATION, "old", "a".repeat(5), 400);
        send(ORGANIZATION, "old", "a".repeat(65), 400);

        verifyNoInteractions(service);
    }

    /** 정책을 위반한 신규 비밀번호도 오류 응답에 원문으로 노출하지 않는다. */
    @Test
    void hidesRejectedPasswordInErrorResponse() throws Exception {
        for (String path : List.of(PERSONAL, ORGANIZATION)) {
            String rejectedPassword = "SensitivePassword".repeat(10);
            ObjectNode body = json.createObjectNode();
            body.put("currentPassword", "old");
            body.put("newPassword", rejectedPassword);

            mvc.perform(patch(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString(rejectedPassword))));
        }

        verifyNoInteractions(service);
    }

    /** 인증 실패와 대상 제외를 각각 403·404로 반환한다. */
    @Test
    void translatesBusinessFailures() throws Exception {
        doThrow(new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED))
                .when(service).changePersonal(anyString(), anyString(), any());
        doThrow(new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND))
                .when(service).changeOrganization(anyString(), anyString(), any());

        send(PERSONAL, "wrong", "NewPassword1!", 403);
        send(ORGANIZATION, "old", "NewPassword1!", 404);
    }

    /** 평문이나 해시를 응답에 포함하지 않도록 성공 응답의 빈 본문을 확인한다. */
    private void send(String path, String currentPassword, String newPassword, int expectedStatus) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);

        org.springframework.test.web.servlet.ResultActions result = mvc.perform(patch(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));

        result.andExpect(status().is(expectedStatus));

        if (expectedStatus == 204) {
            result.andExpect(content().string(""));
        }
    }
}
