package kr.co.teambrain.marvelrun.user.participantauth.command.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 참가자 API 가운데 경로로 주문을 받는 메서드 목록을 고정한다.
 * 새로 더하면 이 목록이 깨진다. 그 서비스가 잠금 뒤 ParticipantOrderAccess.verifyOwned를 부르는지 보고,
 * 남의 주문 거절 테스트를 함께 더한 뒤 목록에 넣는다.
 */
class ParticipantOrderEndpointsTest {

    /** 각 서비스의 남의 주문 거절 테스트: PackageUpgradeCommandServiceTest, RegistrationEntryModificationServiceTest,
     *  ParticipantOrderModificationServiceTest, ParticipantOrderAccessTest(조회·취소의 사전 확인). */
    private static final List<String> OWNED_ORDER_ENDPOINTS = List.of(
            "GET /v1/participant/registrations/{registrationId}",
            "PATCH /v1/participant/registrations/{registrationId}",
            "PATCH /v1/participant/registrations/{registrationId}/entries",
            "POST /v1/participant/registrations/{registrationId}/cancellation",
            "POST /v1/participant/registrations/{registrationId}/payments/{paymentId}/retry",
            "POST /v1/participant/registrations/{registrationId}/upgrade",
            "POST /v1/participant/registrations/{registrationId}/upgrade/payment",
            "DELETE /v1/participant/registrations/{registrationId}/upgrade"
    );

    @Test
    void 주문을_경로로_받는_참가자_API는_정해진_목록뿐이고_모두_참가자_전용이다() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("kr.co.teambrain.marvelrun.user")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                if (!path.startsWith("/v1/participant/") || !path.contains("{registrationId}")) {
                    continue;
                }
                found.add(mapping.method()[0] + " " + path);
                PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
                assertThat(preAuthorize).as(controller.getName()).isNotNull();
                assertThat(preAuthorize.value()).isEqualTo("hasRole('PARTICIPANT')");
            }
        }

        assertThat(found).containsExactlyInAnyOrderElementsOf(OWNED_ORDER_ENDPOINTS);
    }
}
