package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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

import java.util.Comparator;
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
    private final EntityManager entityManager;

    @Transactional
    public RegistrationCreateResponse order(String ordererKey, ParticipantAdditionalOrderRequest request) {
        String eventId = representative(registrationRepository.findOrdererRegistrations(ordererKey))
                .getEvent().getId();
        // 신청 생성도 같은 대회를 잠근다. 같은 트랜잭션이라 다시 잠가도 기다리지 않는다.
        registrationCapacityService.lockEvent(eventId);
        // 첫 읽기가 만든 스냅숏 대신 잠금 읽기로 최신 행을 본다. 이미 영속 상태인 엔티티는 쿼리 결과로 값이 바뀌지 않아 다시 읽는다.
        List<Registration> orders = registrationRepository.findOrdererRegistrationsForUpdate(ordererKey);
        orders.forEach(order -> entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE));
        Registration source = representative(orders.stream()
                .sorted(Comparator.comparing(Registration::isSoftDeleted)
                        .thenComparing(Registration::getRegistrationDate,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList());

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
