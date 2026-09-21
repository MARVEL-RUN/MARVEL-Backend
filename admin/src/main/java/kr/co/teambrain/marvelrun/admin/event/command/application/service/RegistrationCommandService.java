package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegistrationCommandService {

    private final RegistrationCommandRepository registrationCommandRepository;

    /**
     * 개인 신청 비밀번호 초기화
     */
    public void resetPersonalPassword(String registrationId, PasswordResetRequest request) {
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        if (registration.getOrganization() != null) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }

        registration.resetPasswordByAdmin(request.newPassword());
    }
}
