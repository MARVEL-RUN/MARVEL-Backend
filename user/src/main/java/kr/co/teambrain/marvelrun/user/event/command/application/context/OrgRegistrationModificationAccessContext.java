package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 하나의 단체 수정 요청 안에서
 * Organization 소유권 재검증과 현재 구성원 조회를
 * 통과한 상태를 전달한다.
 *
 * 이전 단체 조회에서의 인증 성공 여부는 사용하지 않는다.
 * 실제 수정 요청에 다시 포함된 loginId / password를
 * 현재 Organization과 검증한 뒤에만 생성한다.
 *
 * 인증 결과를 Cookie / Session / Redis Token으로
 * 별도 보존하지 않는다.
 */
public record OrgRegistrationModificationAccessContext(

        Event event,

        Organization organization,

        List<Registration> currentRegistrations,

        OrgRegistrationModificationRequest request,

        LocalDateTime now
) {

    /**
     * Context 외부에서 현재 구성원 목록을 변경하지 못하도록
     * 방어적 복사본을 보존한다.
     */
    public OrgRegistrationModificationAccessContext {
        currentRegistrations =
                List.copyOf(
                        currentRegistrations
                );
    }
}