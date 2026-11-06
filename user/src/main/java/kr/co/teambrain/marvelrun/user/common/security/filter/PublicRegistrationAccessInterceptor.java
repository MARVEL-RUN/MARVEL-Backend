package kr.co.teambrain.marvelrun.user.common.security.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 공개 조회·관리를 닫은 대회에서 개인 신청의 공개 조회·관리 API를 거절한다.
 *
 * 검사 대상은 경로가 아니라 {@link PublicRegistrationAccess}를 붙인 컨트롤러 메서드다.
 * 첫 구매, 로그인, 결제 승인, 옵션 조회, 단체 신청은 표시가 없어 설정과 무관하게 통과한다.
 */
@Component
@RequiredArgsConstructor
public class PublicRegistrationAccessInterceptor implements HandlerInterceptor {

    private final EventRegistrationPolicyRepository policyRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 표시가 없는 API는 정책을 조회하지 않고 통과시킨다.
        if (!(handler instanceof HandlerMethod handlerMethod)
                || !handlerMethod.hasMethodAnnotation(PublicRegistrationAccess.class)) {
            return true;
        }

        String eventId = eventIdOf(request);
        if (eventId == null) {
            return true;
        }

        // 정책 행이 없으면 각 서비스가 내던 기존 오류 응답을 그대로 두기 위해 통과시킨다.
        if (Boolean.FALSE.equals(policyRepository.findPublicRegistrationAccessEnabledByEventId(eventId).orElse(null))) {
            throw new CustomException(ErrorCode.REGISTRATION_LOGIN_REQUIRED, " eventId=" + eventId);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String eventIdOf(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map)) {
            return null;
        }
        return ((Map<String, String>) map).get("eventId");
    }
}
