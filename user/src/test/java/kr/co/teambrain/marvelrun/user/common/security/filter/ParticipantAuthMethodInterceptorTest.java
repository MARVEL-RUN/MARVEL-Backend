package kr.co.teambrain.marvelrun.user.common.security.filter;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantAuthMethod;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.user.common.security.config.WebMvcConfig;
import kr.co.teambrain.marvelrun.user.discount.query.DiscountQueryController;
import kr.co.teambrain.marvelrun.user.discount.query.DiscountQueryService;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.AdditionalPaymentController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.OrgRegistrationCommandController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.RegistrationCancellationController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.RegistrationCommandController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.RegistrationModificationController;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.query.controller.EventQueryController;
import kr.co.teambrain.marvelrun.user.event.query.controller.OrgRegistrationQueryController;
import kr.co.teambrain.marvelrun.user.event.query.controller.RegistrationQueryController;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.service.EventQueryService;
import kr.co.teambrain.marvelrun.user.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRetryPreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기존 비밀번호 API를 올린 실제 컨트롤러에 인터셉터를 붙여 대회 인증 방식별 차단을 확인한다. DB는 쓰지 않는다. */
class ParticipantAuthMethodInterceptorTest {

    private static final String PASSWORD_EVENT = "password-event";
    private static final String VIRTUAL_EVENT = "virtual-event";
    private static final String MISSING_EVENT = "missing-event";

    /** 계획 문서의 18개 API. 하나라도 매핑이 없으면 404가 나서 테스트가 실패한다. */
    private static final List<Endpoint> PASSWORD_ENDPOINTS = List.of(
            new Endpoint(HttpMethod.POST, "/registrations"),
            new Endpoint(HttpMethod.POST, "/registrations/lookup"),
            new Endpoint(HttpMethod.PATCH, "/registrations/r1"),
            new Endpoint(HttpMethod.PATCH, "/registrations/r1/password"),
            new Endpoint(HttpMethod.PATCH, "/registrations/r1/start-group"),
            new Endpoint(HttpMethod.POST, "/registrations/r1/cancellation"),
            new Endpoint(HttpMethod.POST, "/registrations/r1/payments/p1/retry"),
            new Endpoint(HttpMethod.POST, "/registrations/r1/payments/additional/prepare"),
            new Endpoint(HttpMethod.POST, "/registrations/organization"),
            new Endpoint(HttpMethod.GET, "/registrations/organization/duplicate-id-check"),
            new Endpoint(HttpMethod.GET, "/registrations/organization/duplicate-name-check"),
            new Endpoint(HttpMethod.POST, "/organizations/lookup"),
            new Endpoint(HttpMethod.PATCH, "/organizations/o1/password"),
            new Endpoint(HttpMethod.PATCH, "/organizations/o1/registrations"),
            new Endpoint(HttpMethod.PATCH, "/organizations/o1/registrations/start-groups"),
            new Endpoint(HttpMethod.POST, "/organizations/o1/cancellation"),
            new Endpoint(HttpMethod.POST, "/organizations/o1/payments/p1/retry"),
            new Endpoint(HttpMethod.POST, "/organizations/o1/payments/additional/prepare")
    );

    private final EventQueryRepository events = mock(EventQueryRepository.class);
    private final RegistrationQueryService queries = mock(RegistrationQueryService.class);
    private final RegistrationModificationCommandService modifications = mock(RegistrationModificationCommandService.class);
    private final RegistrationCommandService personalCreation = mock(RegistrationCommandService.class);
    private final OrgRegistrationCommandService organizationCreation = mock(OrgRegistrationCommandService.class);
    private final RegistrationCancellationCommandService cancellations = mock(RegistrationCancellationCommandService.class);
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        when(events.findParticipantAuthMethodById(PASSWORD_EVENT))
                .thenReturn(Optional.of(ParticipantAuthMethod.PERSONAL_INFO_PASSWORD));
        when(events.findParticipantAuthMethodById(VIRTUAL_EVENT))
                .thenReturn(Optional.of(ParticipantAuthMethod.BIB_PHONE_OTP));
        when(events.findParticipantAuthMethodById(MISSING_EVENT)).thenReturn(Optional.empty());

        mvc = MockMvcBuilders.standaloneSetup(
                        new RegistrationQueryController(queries), new OrgRegistrationQueryController(queries),
                        new RegistrationModificationController(modifications, mock(PaymentRetryPreparationService.class),
                                mock(RegistrationStartGroupModificationService.class)),
                        new AdditionalPaymentController(mock(AdditionalPaymentPreparationService.class)),
                        new RegistrationCancellationController(cancellations),
                        new RegistrationCommandController(personalCreation, mock(RegistrationPasswordChangeService.class)),
                        new OrgRegistrationCommandController(organizationCreation, mock(RegistrationPasswordChangeService.class)),
                        new EventQueryController(mock(EventQueryService.class)),
                        new DiscountQueryController(mock(DiscountQueryService.class)))
                .addInterceptors(new ParticipantAuthMethodInterceptor(events))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 비밀번호 방식이 아닌 대회에서는 18개 API가 모두 서비스에 닿기 전에 거절된다. */
    @Test
    void rejectsEveryPasswordEndpointForNonPasswordEvent() throws Exception {
        for (Endpoint endpoint : PASSWORD_ENDPOINTS) {
            mvc.perform(request(endpoint.method(), "/v1/public/events/" + VIRTUAL_EVENT + endpoint.path())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PARTICIPANT_AUTH_METHOD_NOT_SUPPORTED"));
        }
        verifyNoInteractions(queries, modifications, personalCreation, organizationCreation, cancellations);
    }

    /** 비밀번호 방식 대회와 없는 대회는 그대로 통과해 요청 검증까지 간다. */
    @Test
    void passesPasswordEventAndMissingEvent() throws Exception {
        for (String eventId : List.of(PASSWORD_EVENT, MISSING_EVENT)) {
            // 빈 본문은 인터셉터를 지나 Bean Validation에서 400이 된다. 차단이면 409다.
            mvc.perform(post("/v1/public/events/" + eventId + "/registrations/lookup")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        verify(events).findParticipantAuthMethodById(PASSWORD_EVENT);
        verify(events).findParticipantAuthMethodById(MISSING_EVENT);
    }

    /** 신청 선택지와 할인 안내는 인증 방식과 무관하므로 버추얼런 대회에서도 막지 않고 대회도 조회하지 않는다. */
    @Test
    void doesNotGuardOptionEndpoints() throws Exception {
        mvc.perform(get("/v1/public/events/" + VIRTUAL_EVENT + "/registration-options"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/public/events/" + VIRTUAL_EVENT + "/discount-options").param("linkCode", "code"))
                .andExpect(status().isOk());
        verify(events, never()).findParticipantAuthMethodById(any());
    }

    /** 표시가 붙은 API라도 경로 변수가 없으면 대회를 조회하지 않고 통과시킨다. */
    @Test
    void passesWithoutEventIdVariable() throws Exception {
        ParticipantAuthMethodInterceptor interceptor = new ParticipantAuthMethodInterceptor(events);
        HandlerMethod guarded = new HandlerMethod(new RegistrationQueryController(queries),
                RegistrationQueryController.class.getMethod("lookup", String.class, RegistrationAccessRequest.class));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), guarded))
                .isTrue();
        verify(events, never()).findParticipantAuthMethodById(any());
    }

    /**
     * 표시는 18개 API에만 붙고, 모두 경로에 {eventId}가 있어야 한다.
     * 경로 변수가 없으면 인터셉터가 검사 없이 통과시키므로 여기서 미리 막는다.
     */
    @Test
    void guardedHandlersAreExactlyTheEighteenEventScopedApis() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> guardedPaths = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("kr.co.teambrain.marvelrun.user")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(RequiresPersonalInfoPassword.class)) {
                    continue;
                }
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                assertThat(mapping).as(method.toString()).isNotNull();
                guardedPaths.add(prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]));
            }
        }

        assertThat(guardedPaths).hasSize(PASSWORD_ENDPOINTS.size());
        assertThat(guardedPaths).allSatisfy(path -> assertThat(path).contains("{eventId}"));
    }

    /** 실제 설정이 인터셉터를 경로 제한 없이 등록한다. */
    @Test
    void webMvcConfigRegistersInterceptor() {
        ParticipantAuthMethodInterceptor interceptor = new ParticipantAuthMethodInterceptor(events);
        ExposedInterceptorRegistry registry = new ExposedInterceptorRegistry();

        new WebMvcConfig(interceptor).addInterceptors(registry);

        assertThat(registry.registered()).containsExactly(interceptor);
    }

    private static class ExposedInterceptorRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    private record Endpoint(HttpMethod method, String path) {
    }
}
