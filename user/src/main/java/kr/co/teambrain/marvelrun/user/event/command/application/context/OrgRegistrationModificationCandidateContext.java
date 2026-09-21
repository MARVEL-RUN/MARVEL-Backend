package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 단체 신청 수정 시 최종 구성원 후보 전체가
 * 01 정책검증을 통과한 상태를 전달한다.
 *
 * currentRegistration이 null이면 신규 참가자이며,
 * 값이 존재하면 기존 Registration의 수정 후보이다.
 *
 * 요청에서 빠진 기존 Registration의 제거 판정은
 * 이후 단체 수정 단계에서 현재 목록과 후보 목록을 비교해 처리한다.
 */
public record OrgRegistrationModificationCandidateContext(

        Event event,

        Organization organization,

        List<Registration> currentRegistrations,

        List<ParticipantCandidate> registrations,

        OrgRegistrationModificationRequest request,

        LocalDateTime now
) {

    /**
     * 현재 구성원과 후보 구성원 목록을
     * 외부에서 변경하지 못하도록 방어적 복사한다.
     */
    public OrgRegistrationModificationCandidateContext {
        currentRegistrations =
                List.copyOf(
                        currentRegistrations
                );

        registrations =
                List.copyOf(
                        registrations
                );
    }


    /**
     * 수정 후 최종 단체 구성원 한 명의 검증 완료 후보 상태다.
     *
     * currentRegistration == null이면 신규 참가자,
     * 아니면 해당 기존 Registration의 수정 후보이다.
     */
    public record ParticipantCandidate(

            Registration currentRegistration,

            OrgRegistrationModificationParticipantRequest request,

            EventCategory eventCategory,

            List<SouvenirJson> souvenirJsons
    ) {

        /**
         * 검증 완료 기념품 목록을 방어적으로 복사한다.
         */
        public ParticipantCandidate {
            souvenirJsons =
                    List.copyOf(
                            souvenirJsons
                    );
        }
    }
}