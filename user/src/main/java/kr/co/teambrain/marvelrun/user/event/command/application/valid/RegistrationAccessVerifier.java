package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import java.util.Objects;

/** 엔티티와 조회 프로젝션이 같은 저장값 비교 계약으로 본인확인을 수행한다. */
public final class RegistrationAccessVerifier {
    /** 공통 정적 검증만 제공한다. */
    private RegistrationAccessVerifier() { }

    /** 기존 수정·금융 호출의 개인 인증 시그니처를 유지한다. */
    public static void verifyPersonal(Registration registration, RegistrationAccessRequest access) {
        if (registration == null) { throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED); }
        verifyPersonal(registration.getName(), registration.getBirth(), registration.getPhNum(),
                registration.getPassword(), access);
    }

    /** 프로젝션의 개인 저장값으로 인증하며 조회를 위해 엔티티를 다시 가져오지 않는다. */
    public static void verifyPersonal(String name, String birth, String phNum, String password,
            RegistrationAccessRequest access) {
        if (access == null || blank(access.name()) || blank(access.birth())
                || blank(access.phNum()) || blank(access.password())
                || !Objects.equals(name, access.name()) || !Objects.equals(birth, access.birth())
                || !Objects.equals(phNum, access.phNum()) || !Objects.equals(password, access.password())) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
    }

    /** 기존 수정·금융 호출의 단체 인증 시그니처를 유지한다. */
    public static void verifyOrganization(Organization organization, OrganizationAccessRequest access) {
        if (organization == null) { throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED); }
        verifyOrganization(organization.getLoginId(), organization.getPassword(), access);
    }

    /** 단체 프로젝션의 로그인 저장값으로 기존과 동일하게 인증한다. */
    public static void verifyOrganization(String loginId, String password, OrganizationAccessRequest access) {
        if (access == null || blank(access.loginId()) || blank(access.password())
                || !Objects.equals(loginId, access.loginId()) || !Objects.equals(password, access.password())) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
    }

    /** 외부 인증 입력의 필수 값만 확인한다. */
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
