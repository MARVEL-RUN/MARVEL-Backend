package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import org.springframework.security.crypto.password.PasswordEncoder;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/* 수정 대상의 본인확인을 수행하고 외부 결제 신청의 온라인 변경을 차단한다. */
@Component
@RequiredArgsConstructor
public class RegistrationModificationAccessValidator {
    private final PasswordEncoder passwordEncoder;

    private final RegistrationCommandRepository
            registrationCommandRepository;


    /**
     * 개인 Registration 수정 요청의 대상과 본인확인 정보를 검증한다.
     *
     * 이전 조회 요청에서 인증에 성공했는지는 사용하지 않는다.
     * 실제 수정 요청에 다시 포함된 access 정보를
     * 현재 DB Registration과 재검증한 뒤 Context를 생성한다.
     *
     * @param eventId 대상 Event
     * @param registrationId 수정할 Registration
     * @param request 실제 수정 요청
     * @param now 수정 Use Case의 공통 기준시각
     * @return 본인확인을 통과한 수정 Access Context
     */
    public RegistrationModificationAccessContext validate(
            String eventId,
            String registrationId,
            RegistrationModificationRequest request,
            LocalDateTime now
    ) {

        Registration registration =
                registrationCommandRepository
                        .findActivePersonalModificationTarget(
                                eventId,
                                registrationId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.REGISTRATION_NOT_FOUND
                                )
                        );

        validateAccess(
                registration,
                request.access()
        );

        // 본인확인을 마친 외부 결제 신청은 기본정보만 변경하는 요청도 차단한다.
        registration.validateOnlineRegistrationProcessingAllowed();

        return new RegistrationModificationAccessContext(
                registration.getEvent(),
                registration,
                request,
                now
        );
    }


    /** 공통 본인확인 계약으로 현재 개인 신청의 접근 정보를 검증한다. */
    private void validateAccess(
            Registration registration,
            RegistrationAccessRequest access
    ) {
        RegistrationAccessVerifier.verifyPersonal(registration, access, passwordEncoder);
    }
}
