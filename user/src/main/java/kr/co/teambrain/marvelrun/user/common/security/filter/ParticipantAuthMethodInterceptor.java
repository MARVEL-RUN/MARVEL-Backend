package kr.co.teambrain.marvelrun.user.common.security.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 이름·생년월일·휴대폰과 비밀번호로 신청을 찾는 기존 개인·단체 API를
 * 그 방식을 쓰는 대회에서만 통과시킨다.
 *
 * 검사 대상은 경로가 아니라 {@link RequiresPersonalInfoPassword}를 붙인 컨트롤러 메서드다.
 * 같은 경로 아래에 다른 인증 방식의 API를 만들어도 막지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ParticipantAuthMethodInterceptor implements HandlerInterceptor {

    private final EventQueryRepository eventQueryRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 표시가 없는 API는 대회를 조회하지 않고 통과시킨다.
        if (!(handler instanceof HandlerMethod handlerMethod)
                || !handlerMethod.hasMethodAnnotation(RequiresPersonalInfoPassword.class)) {
            return true;
        }

        String eventId = eventIdOf(request);
        if (eventId == null) {
            return true;
        }

        // 대회가 없으면 각 서비스가 내던 기존 오류 응답을 그대로 두기 위해 통과시킨다.
        eventQueryRepository.findParticipantAuthMethodById(eventId)
                .filter(method -> !method.usesPersonalInfoPassword())
                .ifPresent(method -> {
                    throw new CustomException(ErrorCode.PARTICIPANT_AUTH_METHOD_NOT_SUPPORTED);
                });
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
