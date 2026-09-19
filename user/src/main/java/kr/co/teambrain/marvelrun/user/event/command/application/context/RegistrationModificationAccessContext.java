package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;

import java.time.LocalDateTime;

/**
 * 하나의 개인 Registration 수정 요청 안에서
 * 대상 조회와 본인확인 재검증을 통과한 현재 상태를 전달한다.
 *
 * 이전 조회 요청의 인증 결과를 재사용하지 않는다.
 * 수정 요청에 포함된 access 정보를 현재 Registration과
 * 다시 비교한 뒤에만 이 Context를 생성한다.
 *
 * 별도의 Cookie / Session / Redis 권한정보로 저장하지 않으며,
 * 현재 수정 Use Case 실행 범위 밖으로 보존하지 않는다.
 */
public record RegistrationModificationAccessContext(

        Event event,

        Registration registration,

        RegistrationModificationRequest request,

        LocalDateTime now
) {
}