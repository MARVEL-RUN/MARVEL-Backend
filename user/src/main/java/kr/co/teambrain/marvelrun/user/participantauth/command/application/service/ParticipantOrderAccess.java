package kr.co.teambrain.marvelrun.user.participantauth.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 참가자 API가 경로로 받은 주문이 토큰 주문자의 것인지 확인하는 한 곳.
 *
 * 주문자 키는 같은 대회 안에서만 복사되므로 키가 같으면 대회도 같다. 대회는 따로 대조하지 않는다.
 * 잠그는 서비스는 잠금 뒤 다시 읽은 행으로 {@link #verifyOwned}를 한 번 더 부른다.
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
        verifyOwned(registration, ordererKey);
        return registration;
    }

    /**
     * me 경로(me/upgrade, me/entries)가 가리키는 주문. 살아 있는 주문이 하나일 때만 정할 수 있다.
     * 프론트가 {registrationId} 경로로 옮기면 지운다.
     */
    @Transactional(readOnly = true)
    public String resolveMe(String ordererKey) {
        List<Registration> active = registrationRepository.findOrdererRegistrations(ordererKey).stream()
                .filter(registration -> !registration.isSoftDeleted())
                .toList();
        if (active.size() != 1) {
            throw new CustomException(ErrorCode.PARTICIPANT_ORDER_SELECTION_REQUIRED,
                    " 살아 있는 주문 " + active.size() + "건");
        }
        return active.getFirst().getId();
    }

    /** 토큰 주문자의 개인 주문인지 본다. 잠금 뒤 다시 읽은 행으로 부른다. 취소 여부는 호출한 업무가 판단한다. */
    public static void verifyOwned(Registration registration, String ordererKey) {
        if (ordererKey == null
                || registration.getOrganization() != null
                || !Objects.equals(registration.getOrdererKey(), ordererKey)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED,
                    " 토큰 주문자의 주문이 아님 registrationId=" + registration.getId());
        }
    }
}
