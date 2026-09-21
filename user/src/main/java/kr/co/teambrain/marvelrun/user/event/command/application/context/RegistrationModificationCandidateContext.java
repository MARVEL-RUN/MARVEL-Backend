package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 개인 신청 수정 시 모든 정책검증을 통과한 변경 후 후보 상태다.
 *
 * 이 Context가 생성되는 시점까지 실제 Registration Entity는
 * 변경하지 않는다.
 *
 * 이후 Pricing / Capacity diff / 실제 Entity 반영 단계가
 * 동일한 후보값을 사용하도록 검증 완료 상태를 전달한다.
 */
public record RegistrationModificationCandidateContext(

        Event event,

        Registration currentRegistration,

        EventCategory eventCategory,

        List<SouvenirJson> souvenirJsons,

        RegistrationModificationRequest request,

        LocalDateTime now
) {

    /**
     * 검증 완료된 기념품 목록이 외부에서 변경되지 않도록
     * 방어적 복사본을 보존한다.
     */
    public RegistrationModificationCandidateContext {
        souvenirJsons =
                List.copyOf(
                        souvenirJsons
                );
    }
}