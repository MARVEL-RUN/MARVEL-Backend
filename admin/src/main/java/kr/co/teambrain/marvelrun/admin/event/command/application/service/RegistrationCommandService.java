package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

        if (request.newPassword().length() < 6) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD_LENGTH);
        }

        registration.resetPasswordByAdmin(request.newPassword());
    }

    public void modifyRegistrationBasicInfo(String registrationId, AdminRegistrationModifyRequest request) {
        Registration registration = registrationCommandRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));

        LocalDate modifiedBirth = LocalDate.parse(request.birth(), DateTimeFormatter.ISO_DATE);
        LocalDate eventDate = registration.getEvent().getStartDate().toLocalDate();

        // 1. 10Km 코스 연령 제한 방어 (만 12세 이하 - 2013년 11월 1일 이후 출생자)
        LocalDate childCutoff = LocalDate.of(2013, 11, 1);
        boolean isChildRule = !modifiedBirth.isBefore(childCutoff);

        if (isChildRule && registration.getEventCategory().getName().contains("10")) {
            throw new CustomException(ErrorCode.AGE_RESTRICTION_VIOLATION);
        }

        // 2. 보호자 정보 누락 방어
        if (isChildRule && (request.guardianName() == null || request.guardianName().isBlank())) {
            throw new CustomException(ErrorCode.GUARDIAN_INFO_REQUIRED);
        }

        // 3. 결제 금액 변동(요금제 변경) 차단 방어 (대회일 기준 만 13세 미만 여부로 판별)
        LocalDate oldBirth = LocalDate.parse(registration.getBirth(), DateTimeFormatter.ISO_DATE);
        boolean wasChildPrice = eventDate.isBefore(oldBirth.plusYears(13));
        boolean willBeChildPrice = eventDate.isBefore(modifiedBirth.plusYears(13));

        if (wasChildPrice != willBeChildPrice) {
            throw new CustomException(ErrorCode.PRICE_TIER_CHANGE_NOT_ALLOWED);
        }

        // 4. 복합 유니크(이름+연락처+생년월일) 중복 방어
        if (registrationCommandRepository.existsOtherActiveByEventIdAndUniqueInfo(
                registration.getEvent().getId(),
                registration.getId(),
                request.name(),
                request.phNum(),
                request.birth())) {
            throw new CustomException(ErrorCode.DUPLICATE_REGISTRATION);
        }

        registration.modifyBasicInfoByAdmin(
                request.name(),
                request.phNum(),
                request.email(),
                request.birth(),
                request.gender(),
                request.address(),
                request.addressDetail(),
                request.guardianName(),
                request.guardianPhNum(),
                request.guardianRelationship(),
                LocalDateTime.now()
        );
    }
}
