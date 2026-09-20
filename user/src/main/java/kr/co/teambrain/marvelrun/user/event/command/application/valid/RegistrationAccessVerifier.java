package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import java.util.Objects;

/** 수정과 금융 요청에서 동일하게 사용하는 현재 저장값 기반 본인확인이다. */
public final class RegistrationAccessVerifier {
    /** 정적 검증 책임만 제공한다. */
    private RegistrationAccessVerifier() { }

    /** 기존 개인 인증 계약을 유지하며 누락된 입력은 인증 성공으로 취급하지 않는다. */
    public static void verifyPersonal(Registration registration, RegistrationAccessRequest access) {
        if (registration == null || access == null
                || blank(access.name()) || blank(access.birth())
                || blank(access.phNum()) || blank(access.password())
                || !Objects.equals(registration.getName(), access.name())
                || !Objects.equals(registration.getBirth(), access.birth())
                || !Objects.equals(registration.getPhNum(), access.phNum())
                || !Objects.equals(registration.getPassword(), access.password())) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
    }

    /** 기존 단체 로그인 정보 비교 계약을 공통으로 사용한다. */
    public static void verifyOrganization(Organization organization, OrganizationAccessRequest access) {
        if (organization == null || access == null
                || blank(access.loginId()) || blank(access.password())
                || !Objects.equals(organization.getLoginId(), access.loginId())
                || !Objects.equals(organization.getPassword(), access.password())) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
    }

    /** 인증 입력의 누락을 판정한다. */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}