package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantAdditionalOrderRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 로그인한 주문자의 추가 주문. 같은 신청자 주문 여러 건을 허용한 대회에서 공개 신청이 막는 두 번째 주문을 받는다.
 *
 * 신원·이메일·비밀번호 해시·주문자 키를 대표 주문(살아 있는 최근 주문, 없으면 최근 주문)에서 복사한다.
 * 주문자 정보·비밀번호 일괄 변경과 같은 순서(대회 → 주문)로 잠그고, 잠근 최신 행에서 복사한다.
 */
@Service
@RequiredArgsConstructor
public class ParticipantAdditionalOrderService {

    private final RegistrationCommandRepository registrationRepository;
    private final RegistrationCapacityService registrationCapacityService;
    private final RegistrationCommandService registrationCommandService;

    @Transactional
    public RegistrationCreateResponse order(String ordererKey, ParticipantAdditionalOrderRequest request) {
        // 대회 id만 읽는다. 주문 엔티티를 미리 읽지 않아야 뒤의 잠금 조회가 최신 행을 준다.
        String eventId = registrationRepository.findEventIdByOrdererKey(ordererKey)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED, " 주문자의 주문이 없음"));
        // 신청 생성도 같은 대회를 잠근다. 같은 트랜잭션이라 다시 잠가도 기다리지 않는다.
        registrationCapacityService.lockEvent(eventId);
        Registration source = representative(registrationRepository.findOrdererRegistrationsForUpdate(ordererKey));

        return registrationCommandService.registerAdditional(eventId,
                request.toCreateRequest(source.getName(), source.getPhNum(), source.getBirth(), source.getGender(),
                        source.getEmail()),
                source.getPassword(), source.getOrdererKey());
    }

    /** 살아 있는 주문, 최근 신청 순으로 놓인 목록의 첫 주문. */
    private static Registration representative(List<Registration> orders) {
        if (orders.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED, " 주문자의 주문이 없음");
        }
        return orders.getFirst();
    }
}
