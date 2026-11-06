package kr.co.teambrain.marvelrun.user.participantauth.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 참가자 API가 경로로 받은 주문을 잠그기 전에 정하는 곳. 소유 판단 자체는 {@link Registration#isOwnedBy}에 있다.
 *
 * 주문자 키는 같은 대회 안에서만 복사되므로 키가 같으면 대회도 같다. 대회는 따로 대조하지 않는다.
 * 잠그는 서비스는 잠금 뒤 다시 읽은 행으로 {@link Registration#isOwnedBy}를 한 번 더 본다.
 * 남의 주문과 없는 주문은 같은 오류로 응답해 존재 여부를 드러내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ParticipantOrderAccess {

    private final RegistrationCommandRepository registrationRepository;

    /** 잠금 없이 읽어 소유를 본다. 대회 id를 얻어 잠그기 전에 쓴다. */
    @Transactional(readOnly = true)
    public Registration findOwned(String registrationId, String ordererKey) {
        Registration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED,
                        " 없는 주문 registrationId=" + registrationId));
        if (!registration.isOwnedBy(ordererKey)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED,
                    " 토큰 주문자의 주문이 아님 registrationId=" + registrationId);
        }
        return registration;
    }

    /**
     * me 경로(me/upgrade, me/entries)가 가리키는 주문. 살아 있는 주문이 하나일 때만 정할 수 있다.
     * 살아 있는 주문이 없으면 이전처럼 접근 거부, 여럿이면 주문을 고르라고 응답한다.
     * 프론트가 {registrationId} 경로로 옮기면 지운다.
     */
    @Transactional(readOnly = true)
    public String resolveMe(String ordererKey) {
        List<Registration> active = registrationRepository.findOrdererRegistrations(ordererKey).stream()
                .filter(registration -> !registration.isSoftDeleted())
                .toList();
        if (active.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED, " 살아 있는 주문이 없음");
        }
        if (active.size() > 1) {
            throw new CustomException(ErrorCode.PARTICIPANT_ORDER_SELECTION_REQUIRED,
                    " 살아 있는 주문 " + active.size() + "건");
        }
        return active.getFirst().getId();
    }
}
