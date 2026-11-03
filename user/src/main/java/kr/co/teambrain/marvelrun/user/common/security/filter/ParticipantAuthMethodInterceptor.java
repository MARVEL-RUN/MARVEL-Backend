package kr.co.teambrain.marvelrun.user.common.security.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

/**
 * 이름·생년월일·휴대폰과 비밀번호로 신청을 찾는 기존 개인·단체 API를
 * 그 방식을 쓰는 대회에서만 통과시킨다.
 *
 * 기존 API를 이 경로 밖에 새로 만들면 검사를 거치지 않으므로 PATH_PATTERNS에 함께 더한다.
 */
@Component
@RequiredArgsConstructor
public class ParticipantAuthMethodInterceptor implements HandlerInterceptor {

    /** context-path(/api)를 뺀 경로. `/**`는 뒤가 비어 있는 경로(예: POST .../registrations)도 맞는다. */
    public static final List<String> PATH_PATTERNS = List.of(
            "/v1/public/events/*/registrations/**",
            "/v1/public/events/*/organizations/**"
    );

    private final EventQueryRepository eventQueryRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
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
