package kr.co.teambrain.marvelrun.admin.capacity.command.application.dto;

/**
 * 예약에 기록된 확보 대상과 수량을 반환한다.
 *
 * 결제 확정이나 반환 시 사용하며,
 * 실제 수량의 위치는 Reservation 상태를 기준으로 판단한다.
 *
 * @param reservationId 예약 식별자
 * @param capacityId 확보한 Capacity 식별자
 * @param quantity 확보 수량
 */
public record ReservationAllocation(
        String reservationId,
        String capacityId,
        int quantity
) {
}