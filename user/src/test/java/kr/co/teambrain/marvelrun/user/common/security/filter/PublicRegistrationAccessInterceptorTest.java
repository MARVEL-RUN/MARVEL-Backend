package kr.co.teambrain.marvelrun.user.common.security.filter;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.user.common.security.config.WebMvcConfig;
import kr.co.teambrain.marvelrun.user.discount.query.DiscountQueryController;
import kr.co.teambrain.marvelrun.user.discount.query.DiscountQueryService;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.AdditionalPaymentController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.OrgRegistrationCommandController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.RegistrationCancellationController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.RegistrationCommandController;
import kr.co.teambrain.marvelrun.user.event.command.application.controller.RegistrationModificationController;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.OrgRegistrationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationPasswordChangeService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationStartGroupModificationService;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.query.controller.EventQueryController;
import kr.co.teambrain.marvelrun.user.event.query.controller.OrgRegistrationQueryController;
import kr.co.teambrain.marvelrun.user.event.query.controller.RegistrationQueryController;
import kr.co.teambrain.marvelrun.user.event.query.service.EventQueryService;
import kr.co.teambrain.marvelrun.user.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.controller.ParticipantAuthCommandController;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantAuthCommandService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 공개 컨트롤러에 인터셉터를 붙여 대회 설정별 공개 조회·관리 차단을 확인한다. DB는 쓰지 않는다. */
class PublicRegistrationAccessInterceptorTest {

    private static final String OPEN_EVENT = "open-event";
    private static final String CLOSED_EVENT = "closed-event";
    private static final String MISSING_EVENT = "missing-event";

    /** 설정이 꺼지면 닫는 개인 신청 API 7개. 하나라도 매핑이 없으면 404가 나서 테스트가 실패한다. */
    private static final List<Endpoint> CLOSED_ENDPOINTS = List.of(
            new Endpoint(HttpMethod.POST, "/registrations/lookup"),
            new Endpoint(HttpMethod.PATCH, "/registrations/r1"),
            new Endpoint(HttpMethod.PATCH, "/registrations/r1/start-group"),
            new Endpoint(HttpMethod.POST, "/registrations/r1/cancellation"),
            new Endpoint(HttpMethod.POST, "/registrations/r1/payments/p1/retry"),
            new Endpoint(HttpMethod.POST, "/registrations/r1/payments/additional/prepare"),
            new Endpoint(HttpMethod.PATCH, "/registrations/r1/password")
    );

    /** 설정과 무관하게 열어 두는 API: 첫 구매, 로그인, 단체 신청 조회·관리. */
    private static final List<Endpoint> OPEN_ENDPOINTS = List.of(
            new Endpoint(HttpMethod.POST, "/registrations"),
            new Endpoint(HttpMethod.POST, "/participant-auth/login"),
            new Endpoint(HttpMethod.POST, "/organizations/lookup"),
            new Endpoint(HttpMethod.PATCH, "/organizations/o1/registrations"),
            new Endpoint(HttpMethod.POST, "/organizations/o1/cancellation"),
            new Endpoint(HttpMethod.POST, "/organizations/o1/payments/p1/retry")
    );

    private final EventRegistrationPolicyRepository policies = mock(EventRegistrationPolicyRepository.class);
    private final RegistrationQueryService queries = mock(RegistrationQueryService.class);
    private final RegistrationModificationCommandService modifications = mock(RegistrationModificationCommandService.class);
    private final PaymentRetryPreparationService retries = mock(PaymentRetryPreparationService.class);
    private final RegistrationStartGroupModificationService startGroups = mock(RegistrationStartGroupModificationService.class);
    private final AdditionalPaymentPreparationService additionalPayments = mock(AdditionalPaymentPreparationService.class);
    private final RegistrationCancellationCommandService cancellations = mock(RegistrationCancellationCommandService.class);
    private final RegistrationPasswordChangeService passwords = mock(RegistrationPasswordChangeService.class);
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        // thenReturn 인자 안에서 다른 mock을 스텁하면 미완료 스텁 오류가 나므로 정책을 먼저 만든다.
        EventRegistrationPolicy openPolicy = policy(true);
        EventRegistrationPolicy closedPolicy = policy(false);
        when(policies.findByEventId(OPEN_EVENT)).thenReturn(Optional.of(openPolicy));
        when(policies.findByEventId(CLOSED_EVENT)).thenReturn(Optional.of(closedPolicy));
        when(policies.findByEventId(MISSING_EVENT)).thenReturn(Optional.empty());

        mvc = MockMvcBuilders.standaloneSetup(
                        new RegistrationQueryController(queries), new OrgRegistrationQueryController(queries),
                        new RegistrationModificationController(modifications, retries, startGroups),
                        new AdditionalPaymentController(additionalPayments),
                        new RegistrationCancellationController(cancellations),
                        new RegistrationCommandController(mock(RegistrationCommandService.class), passwords),
                        new OrgRegistrationCommandController(mock(OrgRegistrationCommandService.class), passwords),
                        new ParticipantAuthCommandController(mock(ParticipantAuthCommandService.class)),
                        new EventQueryController(mock(EventQueryService.class)),
                        new DiscountQueryController(mock(DiscountQueryService.class)))
                .addInterceptors(new PublicRegistrationAccessInterceptor(policies))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 설정이 꺼진 대회에서는 7개 API가 모두 서비스에 닿기 전에 409로 거절된다. */
    @Test
    void rejectsEveryClosedEndpointWhenPublicAccessDisabled() throws Exception {
        for (Endpoint endpoint : CLOSED_ENDPOINTS) {
            mvc.perform(request(endpoint.method(), "/v1/public/events/" + CLOSED_EVENT + endpoint.path())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_LOGIN_REQUIRED"));
        }
        verifyNoInteractions(queries, modifications, retries, startGroups, additionalPayments, cancellations, passwords);
    }

    /** 설정이 켜진 대회와 정책 행이 없는 대회는 그대로 통과해 요청 검증까지 간다. */
    @Test
    void passesWhenPublicAccessEnabledOrPolicyMissing() throws Exception {
        for (String eventId : List.of(OPEN_EVENT, MISSING_EVENT)) {
            for (Endpoint endpoint : CLOSED_ENDPOINTS) {
                // 빈 본문은 인터셉터를 지나 Bean Validation에서 400이 된다. 차단이면 409다.
                mvc.perform(request(endpoint.method(), "/v1/public/events/" + eventId + endpoint.path())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    /** 첫 구매·로그인·단체 신청은 설정이 꺼진 대회에서도 막지 않고 정책도 조회하지 않는다. */
    @Test
    void doesNotGuardPurchaseLoginAndOrganizationEndpoints() throws Exception {
        for (Endpoint endpoint : OPEN_ENDPOINTS) {
            mvc.perform(request(endpoint.method(), "/v1/public/events/" + CLOSED_EVENT + endpoint.path())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        verify(policies, never()).findByEventId(any());
    }

    /** 신청 선택지와 할인 안내는 설정과 무관하다. */
    @Test
    void doesNotGuardOptionEndpoints() throws Exception {
        mvc.perform(get("/v1/public/events/" + CLOSED_EVENT + "/registration-options"))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/public/events/" + CLOSED_EVENT + "/discount-options").param("linkCode", "code"))
                .andExpect(status().isOk());
        verify(policies, never()).findByEventId(any());
    }

    /** 표시가 붙은 API에 경로 변수가 없으면 대회를 알 수 없으므로 정책을 조회하지 않고 거절한다. */
    @Test
    void rejectsWithoutEventIdVariable() throws Exception {
        PublicRegistrationAccessInterceptor interceptor = new PublicRegistrationAccessInterceptor(policies);
        HandlerMethod guarded = new HandlerMethod(new RegistrationQueryController(queries),
                RegistrationQueryController.class.getMethod("lookup", String.class, RegistrationAccessRequest.class));
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), guarded))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_LOGIN_REQUIRED));
        verify(policies, never()).findByEventId(any());
    }

    /**
     * 표시는 7개 API에만 붙고, 모두 경로에 {eventId}가 있어야 한다.
     * 경로 변수가 없으면 그 API는 모든 요청이 거절되므로 여기서 미리 막는다.
     */
    @Test
    void guardedHandlersAreExactlyTheSevenEventScopedApis() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> guardedPaths = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("kr.co.teambrain.marvelrun.user")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(PublicRegistrationAccess.class)) {
                    continue;
                }
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                assertThat(mapping).as(method.toString()).isNotNull();
                guardedPaths.add(prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]));
            }
        }

        assertThat(guardedPaths).hasSize(CLOSED_ENDPOINTS.size());
        assertThat(guardedPaths).allSatisfy(path -> assertThat(path).contains("{eventId}"));
    }

    /** 실제 설정이 인터셉터를 경로 제한 없이 등록한다. */
    @Test
    void webMvcConfigRegistersInterceptor() {
        PublicRegistrationAccessInterceptor interceptor = new PublicRegistrationAccessInterceptor(policies);
        ExposedInterceptorRegistry registry = new ExposedInterceptorRegistry();

        new WebMvcConfig(interceptor).addInterceptors(registry);

        assertThat(registry.registered()).containsExactly(interceptor);
    }

    private static class ExposedInterceptorRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    private static EventRegistrationPolicy policy(boolean publicRegistrationAccessEnabled) {
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.isPublicRegistrationAccessEnabled()).thenReturn(publicRegistrationAccessEnabled);
        return policy;
    }

    private record Endpoint(HttpMethod method, String path) {
    }
}
