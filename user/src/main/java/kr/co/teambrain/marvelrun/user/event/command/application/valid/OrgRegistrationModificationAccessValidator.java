package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 단체 수정 요청의 인증과 현재 구성원 귀속을 확인한다. */
@Component
@RequiredArgsConstructor
public class OrgRegistrationModificationAccessValidator {

    private final OrganizationCommandRepository
            organizationCommandRepository;

    private final RegistrationCommandRepository
            registrationCommandRepository;


    /**
     * 단체 수정 요청의 Organization 소유권과
     * 기존 Registration ID 귀속을 검증한다.
     *
     * 이전 조회 요청의 인증 결과는 사용하지 않으며,
     * 실제 수정 요청에 포함된 loginId / password를
     * 현재 Organization과 다시 비교한다.
     *
     * @param eventId 대상 Event
     * @param organizationId 수정 대상 Organization
     * @param request 최종 구성원 목록을 포함한 수정 요청
     * @param now 수정 Use Case의 공통 기준시각
     * @return 재인증 및 대상 귀속 검증을 통과한 Context
     */
    public OrgRegistrationModificationAccessContext validate(
            String eventId,
            String organizationId,
            OrgRegistrationModificationRequest request,
            LocalDateTime now
    ) {

        Organization organization =
                organizationCommandRepository
                        .findModificationTarget(
                                eventId,
                                organizationId
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.ORGANIZATION_NOT_FOUND
                                )
                        );

        validateAccess(
                organization,
                request.access()
        );

        List<Registration> currentRegistrations =
                registrationCommandRepository
                        .findAllActiveByEventAndOrganization(
                                eventId,
                                organizationId
                        );

        validateRegistrationTargets(
                currentRegistrations,
                request.registrations()
        );

        return new OrgRegistrationModificationAccessContext(
                organization.getEvent(),
                organization,
                currentRegistrations,
                request,
                now
        );
    }


    /** 공통 본인확인 계약으로 현재 단체의 로그인 정보를 검증한다. */
    public void validateAccess(
            Organization organization,
            OrganizationAccessRequest access
    ) {
        RegistrationAccessVerifier.verifyOrganization(organization, access);
    }


    /**
     * 수정 요청에 포함된 기존 Registration ID가
     * 실제 현재 Organization 구성원인지 검증한다.
     *
     * registrationId가 null인 항목은 신규 참가자 후보이므로 허용한다.
     * 기존 ID를 생략하는 것은 해당 참가자의 제거 후보를 의미한다.
     */
    private void validateRegistrationTargets(
            List<Registration> currentRegistrations,
            List<OrgRegistrationModificationParticipantRequest> requestedRegistrations
    ) {

        Set<String> currentIds =
                currentRegistrations.stream()
                        .map(
                                Registration::getId
                        )
                        .collect(
                                Collectors.toSet()
                        );

        Set<String> requestedExistingIds =
                new HashSet<>();

        for (
                OrgRegistrationModificationParticipantRequest requested
                : requestedRegistrations
        ) {

            String registrationId =
                    requested.registrationId();

            /*
             * null은 신규 Registration 후보.
             */
            if (registrationId == null) {
                continue;
            }

            /*
             * 빈 문자열은 신규를 의미하지 않는다.
             * 신규는 반드시 null로 표현한다.
             */
            if (registrationId.isBlank()) {
                throw new CustomException(
                        ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
                );
            }

            if (
                    !requestedExistingIds.add(
                            registrationId
                    )
            ) {
                throw new CustomException(
                        ErrorCode.DUPLICATE_REGISTRATION_MODIFICATION_TARGET
                );
            }

            /*
             * 다른 Event / 다른 Organization / 삭제된 Registration은
             * currentIds에 존재하지 않으므로 모두 여기서 차단된다.
             */
            if (
                    !currentIds.contains(
                            registrationId
                    )
            ) {
                throw new CustomException(
                        ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
                );
            }
        }
    }
}
