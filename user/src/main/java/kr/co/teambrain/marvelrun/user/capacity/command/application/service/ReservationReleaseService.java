package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
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

    /**
     * 지정한 신청들의 HELD 예약을 반환한다.
     *
     * 이미 RELEASED인 예약은 제외한다.
     * 결제 처리 중이거나 확정된 예약이 하나라도 있으면 전체를 실패시킨다.
     *
     * 버전 충돌이나 수량 불일치가 발생하면 상태와 카운터를 모두 롤백한다.
     *
     * @param eventId 반환 대상 대회
     * @param registrationIds 호출부에서 반환을 허용한 신청 식별자 목록
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

            if (reservation.releaseHeld()) {
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
}