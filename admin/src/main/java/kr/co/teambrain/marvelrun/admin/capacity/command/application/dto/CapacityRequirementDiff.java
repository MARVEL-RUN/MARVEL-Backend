package kr.co.teambrain.marvelrun.admin.capacity.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;

import java.util.List;

/**
 * 예약 한 건의 기존 점유와 수정 후 필요량 차이다.
 *
 * 조회 당시 예약 상태와 버전을 함께 보관한다.
 * 이 결과 자체가 예약을 잠그거나 변경 권한을 확보한 것은 아니다.
 */
public record CapacityRequirementDiff(
        String reservationId,
        ReservationStatus reservationStatus,
        Long reservationVersion,
        List<Item> items
) {

    /**
     * 계산 완료된 차이 목록을 외부 변경으로부터 보호한다.
     */
    public CapacityRequirementDiff {
        items = List.copyOf(items);
    }

    /**
     * Capacity 한 개의 기존·신규 수량과 증가·감소·유지 수량이다.
     */
    public record Item(
            String capacityId,
            int oldQuantity,
            int newQuantity,
            int increase,
            int decrease,
            int unchanged
    ) {
    }
}