package kr.co.teambrain.marvelrun.user.event.command.application.valid;

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

@Component
@RequiredArgsConstructor
public class RegistrationModificationAccessValidator {

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

        return new RegistrationModificationAccessContext(
                registration.getEvent(),
                registration,
                request,
                now
        );
    }


    /**
     * 이번 수정 요청에 포함된 개인 본인확인 값을
     * 현재 Registration snapshot과 비교한다.
     *
     * 비밀번호는 조회 과정의 성공 여부와 무관하게
     * 수정 요청마다 다시 비교한다.
     */
    private void validateAccess(
            Registration registration,
            RegistrationAccessRequest access
    ) {

        if (
                !Objects.equals(
                        registration.getName(),
                        access.name()
                )
                        || !Objects.equals(
                        registration.getBirth(),
                        access.birth()
                )
                        || !Objects.equals(
                        registration.getPhNum(),
                        access.phNum()
                )
                        || !Objects.equals(
                        registration.getPassword(),
                        access.password()
                )
        ) {

            throw new CustomException(
                    ErrorCode.REGISTRATION_ACCESS_DENIED
            );
        }
    }
}