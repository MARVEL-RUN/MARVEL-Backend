package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 미결제 예약의 홀딩 수량을 반환한다.
 *
 * 소유권과 업무상 반환 가능 여부를 검증한 서비스에서 호출한다.
 * 신청의 소프트 삭제, 결제 취소, 대회 재개는 수행하지 않는다.
 *
 * 예약 상태와 Capacity 수량을 호출부의 동일 트랜잭션에서 변경한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationReleaseService {

    private final ReservationCommandRepository reservationRepository;
    private final ReservationItemCommandRepository itemRepository;
    private final CapacityCommandRepository capacityRepository;
    private final PaymentCommandRepository paymentCommandRepository;

    /**
     * 지정한 신청들의 기존 최초 참가비 주문을 무효화하고 HELD 예약을 반환한다.
     *
     * 관련 Payment를 먼저 잠금 조회한 뒤 예약 상태를 변경한다.
     * 단체 주문이 무효화되더라도 수량은 요청받은 신청들에 대해서만 반환한다.
     *
     * 이미 RELEASED인 예약은 수량과 이력을 다시 변경하지 않는다.
     * 승인 진행 중, 결과 불명 또는 승인 완료 주문이 있으면 반환을 거절한다.
     *
     * 버전 충돌이나 수량 불일치가 발생하면 주문·예약·카운터를 모두 롤백한다.
     *
     * @param eventId 반환 대상 대회
     * @param registrationIds 호출부에서 소유권과 반환 가능 여부를 검증한 신청 목록
     * @param now 호출 서비스에서 구한 현재 시각
     */
    public void releaseUnpaid(
            String eventId,
            List<String> registrationIds,
            LocalDateTime now
    ) {
        if (registrationIds.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 반환 대상 신청 목록이 비어 있습니다."
            );
        }

        Set<String> uniqueIds = new HashSet<>(registrationIds);

        /*
         * 승인 시작과 동일하게 Payment를 먼저 잠근다.
         *
         * READY 주문은 무효화하고,
         * 승인 진행 중이거나 결과 불명인 주문이 있으면 반환을 중단한다.
         *
         * 이후 예약 검증이나 수량 반환이 실패하면
         * 여기서 변경한 Payment 상태도 함께 롤백된다.
         */
        invalidateRelatedPayments(uniqueIds);

        List<Reservation> reservations =
                reservationRepository.findAllByRegistrationIds(uniqueIds);

        if (reservations.size() != uniqueIds.size()) {
            throw new CustomException(
                    ErrorCode.RESERVATION_NOT_FOUND,
                    " 반환 대상 신청 중 예약이 누락되어 있습니다."
            );
        }

        List<String> releaseReservationIds = new ArrayList<>();

        /*
         * 새로 조회한 예약이 요청 대회에 속하는지 확인한다.
         * null 여부가 아닌, 반환 대상의 소속 일치 여부를 검증한다.
         *
         * 이미 반환된 예약은 수량을 다시 감소시키지 않는다.
         */
        for (Reservation reservation : reservations) {

            if (!eventId.equals(
                    reservation.getRegistration().getEvent().getId()
            )) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                        " 반환 대상 예약의 대회가 일치하지 않습니다."
                                + " reservationId=" + reservation.getId()
                );
            }

            /*
             * 이번 호출에서 실제로 RELEASED로 전환된 예약에만 이력을 추가한다.
             * 이미 반환된 예약에는 로그나 수량 변경을 반복하지 않는다.
             */
            if (reservation.releaseHeld()) {
                reservation.appendHistory(
                        ReservationHistoryEntry.Action.RELEASE,
                        now,
                        null,
                        "미결제 예약의 확보 수량 반환",
                        List.of()
                );

                releaseReservationIds.add(reservation.getId());
            }
        }

        if (releaseReservationIds.isEmpty()) {
            return;
        }

        /*
         * 결제 시작과 반환이 같은 HELD 예약을 읽었더라도
         * @Version을 통해 한쪽 상태 변경만 성공하게 한다.
         *
         * flush는 커밋이 아니므로 이후 수량 반환에 실패하면
         * RELEASED 상태 변경도 함께 롤백된다.
         */
        reservationRepository.flush();

        List<ReservationAllocation> allocations =
                itemRepository.findAllocations(releaseReservationIds);

        Set<String> recordedReservationIds = new HashSet<>();
        Map<String, Integer> quantities = new TreeMap<>(); // capacity : 차감할 내역

        /*
         * 현재 종목·기념품 설정을 재계산하지 않고,
         * 실제 확보 당시 저장한 예약 상세를 기준으로 반환한다.
         */
        for (ReservationAllocation allocation : allocations) {

            if (allocation.quantity() <= 0) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 반환할 예약 상세 수량이 잘못되었습니다."
                                + " reservationId=" + allocation.reservationId()
                                + ", capacityId=" + allocation.capacityId()
                );
            }

            recordedReservationIds.add(allocation.reservationId());

            // 각 capacityId에 대해 최종적으로 얼마를 차감해야하는지 합계해둠.
            quantities.merge(
                    allocation.capacityId(), // 1. 확인할 Key
                    allocation.quantity(), // 2. 만약 Key가 없다면 넣을 최초 Value (각 아이템의 홀딩된 개수)
                    Integer::sum // 3. 만약 Key가 이미 있다면 '기존 값 + 새 값'을 수행할 함수
            );
        }

        if (!recordedReservationIds.equals(
                new HashSet<>(releaseReservationIds)
        )) {
            throw new CustomException(
                    ErrorCode.CAPACITY_COUNTER_MISMATCH,
                    " 반환 대상 중 확보 상세가 없는 예약이 존재합니다."
            );
        }

        /*
         * 동일 Capacity의 수량을 합산하여 ID 순서로 반환한다.
         * active=false여도 기존 확보분의 반환은 허용한다.
         */
        // quantities 내에 저장된 capacityId : id대상차감값총합 을 가져옴
        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {

            int updated = capacityRepository.releaseHeld(
                    eventId,
                    entry.getKey(),
                    entry.getValue(),
                    now
            );

            if (updated != 1) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 홀딩 수량을 반환하지 못했습니다."
                                + " capacityId=" + entry.getKey()
                                + ", quantity=" + entry.getValue()
                );
            }
        }
    }

    /**
     * 반환 대상 신청과 관련된 최초 참가비 주문을 잠그고 무효화한다.
     *
     * READY 주문은 INVALIDATED로 변경한다.
     * FAILED와 INVALIDATED는 기존 상태를 유지한다.
     * CONFIRMING, UNKNOWN, COMPLETED는 도메인 메서드에서 거절한다.
     *
     * 주문이 없는 경우에도 예약 자체의 반환 검증은 계속 수행한다.
     * 신규 로그는 추가하지 않으며 기존 Payment 행을 유지한다.
     */
    private void invalidateRelatedPayments(
            Set<String> registrationIds
    ) {

        List<Payment> payments =
                paymentCommandRepository
                        .findAllRelatedToRegistrationsForUpdate(
                                registrationIds,
                                PaymentPurpose.REGISTRATION_TRY
                        );

        for (Payment payment : payments) {
            payment.invalidateForReservationRelease();
        }
    }
}