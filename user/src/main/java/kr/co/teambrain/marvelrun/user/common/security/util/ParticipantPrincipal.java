package kr.co.teambrain.marvelrun.user.common.security.util;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantTokenRole;
import kr.co.teambrain.marvelrun.user.common.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 참가자 토큰에서 주문자 키를 꺼낸다. 경로로 받은 주문이 이 주문자의 것인지는 ParticipantOrderAccess가 본다.
 * 보안 설정·컨트롤러의 역할 검사와 함께 역할을 한 번 더 확인하는 셈이다.
 */
public final class ParticipantPrincipal {

    private ParticipantPrincipal() {
    }

    public static String ordererKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof CustomAdminDetail detail
                && ParticipantTokenRole.PARTICIPANT.name().equals(detail.getRoleName())) {
            return detail.getUsername();
        }
        throw new CustomException(ErrorCode.ACCESS_DENIED, " 참가자 토큰이 아닙니다.");
    }
}
