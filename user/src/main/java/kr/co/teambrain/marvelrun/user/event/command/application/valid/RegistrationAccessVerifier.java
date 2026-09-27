package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Bean Validation을 통과한 요청의 본인정보와 저장 해시를 검증한다.
 * HTTP 외부의 직접 호출도 요청 필수값 검증을 선행해야 한다.
 */
public final class RegistrationAccessVerifier {
    /** 공통 정적 검증만 제공한다. */
    private RegistrationAccessVerifier() { }

    /** 개인 엔티티의 저장 해시와 본인정보를 공통 검증에 전달한다. */
    public static void verifyPersonal(Registration registration, RegistrationAccessRequest access,
            PasswordEncoder passwordEncoder) {
        if (registration == null) { throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED); }
        verifyPersonal(registration.getName(), registration.getBirth(), registration.getPhNum(),
                registration.getPassword(), access, passwordEncoder);
    }

    /** 필수값 검증을 마친 개인 요청을 저장된 본인정보 및 해시와 대조한다. */
    public static void verifyPersonal(String name, String birth, String phNum, String encodedPassword,
            RegistrationAccessRequest access, PasswordEncoder passwordEncoder) {
        if (!Objects.equals(name, access.name()) || !Objects.equals(birth, access.birth())
                || !Objects.equals(phNum, access.phNum()) || !matches(access.password(), encodedPassword, passwordEncoder)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
    }

    /** 단체 엔티티의 저장 해시와 로그인 ID를 공통 검증에 전달한다. */
    public static void verifyOrganization(Organization organization, OrganizationAccessRequest access,
            PasswordEncoder passwordEncoder) {
        if (organization == null) { throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED); }
        verifyOrganization(organization.getLoginId(), organization.getPassword(), access, passwordEncoder);
    }

    /** 필수값 검증을 마친 단체 요청을 저장된 로그인 ID 및 해시와 대조한다. */
    public static void verifyOrganization(String loginId, String encodedPassword, OrganizationAccessRequest access,
            PasswordEncoder passwordEncoder) {
        if (!Objects.equals(loginId, access.loginId()) || !matches(access.password(), encodedPassword, passwordEncoder)) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
    }

    /** 누락·입력 한도 초과·손상된 해시는 원문 비교 없이 인증 실패로 처리한다. */
    private static boolean matches(String rawPassword, String encodedPassword, PasswordEncoder passwordEncoder) {
        if (encodedPassword == null || encodedPassword.isBlank()
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

}
