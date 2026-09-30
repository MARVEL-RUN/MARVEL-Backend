package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.nio.charset.StandardCharsets;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대회 소속 대상을 잠근 뒤 기존 비밀번호를 검증하고 신규 해시만 저장한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationPasswordChangeService {

    private final RegistrationCommandRepository registrationRepository;

    private final OrganizationCommandRepository organizationRepository;

    private final PasswordEncoder passwordEncoder;

    /** 미삭제 개인 신청만 변경하며 단체 소속 신청은 조회 단계에서 제외한다. */
    public void changePersonal(
            String eventId,
            String registrationId,
            PersonalPasswordChangeRequest request
    ) {
        Registration registration = registrationRepository
                .findPersonalPasswordChangeTarget(eventId, registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        verifyCurrentPassword(
                request.currentPassword(),
                registration.getPassword(),
                ErrorCode.REGISTRATION_ACCESS_DENIED
        );

        String encodedPassword = encodeNewPassword(request.newPassword());

        registration.changePassword(encodedPassword);
        registrationRepository.flush();
    }

    /** 대회 소속 단체 계정만 변경하며 활성 구성원이 없는 계정도 허용한다. */
    public void changeOrganization(
            String eventId,
            String organizationId,
            OrganizationPasswordChangeRequest request
    ) {
        Organization organization = organizationRepository
                .findPasswordChangeTarget(eventId, organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND));

        verifyCurrentPassword(
                request.currentPassword(),
                organization.getPassword(),
                ErrorCode.ORGANIZATION_ACCESS_DENIED
        );

        String encodedPassword = encodeNewPassword(request.newPassword());

        organization.changePassword(encodedPassword);
        organizationRepository.flush();
    }

    /** 기존 해시와 원문을 검증하며 손상된 저장값이나 BCrypt 입력 한도 초과는 접근 거부한다. */
    private void verifyCurrentPassword(
            String currentPassword,
            String storedPassword,
            ErrorCode deniedCode
    ) {
        if (storedPassword == null || storedPassword.isBlank()
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new CustomException(deniedCode);
        }

        boolean matches;

        try {
            matches = passwordEncoder.matches(currentPassword, storedPassword);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(deniedCode);
        }

        if (!matches) {
            throw new CustomException(deniedCode);
        }
    }

    /** DTO에서 생성 정책을 검증한 신규 원문에 생성 서비스와 같은 UTF-8 한도를 적용한다. */
    private String encodeNewPassword(String newPassword) {
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new CustomException(ErrorCode.REGISTRATION_PASSWORD_TOO_LONG);
        }

        return passwordEncoder.encode(newPassword);
    }
}
