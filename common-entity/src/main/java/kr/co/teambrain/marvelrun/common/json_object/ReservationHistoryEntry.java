package kr.co.teambrain.marvelrun.common.json_object;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 예약에서 발생한 동작 한 건을 기록하는 JSON 저장 객체이다.
 *
 * 확보 및 재확보 시에는 당시 Capacity와 수량을 items에 기록한다.
 * 이후 결제·반환 이력은 같은 holdSequence로 해당 확보 내역을 참조한다.
 *
 * 엔티티 자체를 포함하지 않고 식별자와 값만 보관한다.
 */
public record ReservationHistoryEntry(
        Action action,
        int holdSequence,
        LocalDateTime occurredAt,
        ReservationStatus status,
        String paymentId,
        String reason,
        List<Item> items
) {

    /**
     * 전달된 상세 목록을 복사하여 이후 원본 목록 변경이
     * 이미 구성한 이력에 영향을 주지 않도록 한다.
     */
    public ReservationHistoryEntry {
        items = List.copyOf(items);
    }

    /**
     * 예약에 기록할 업무 동작을 구분한다.
     */
    public enum Action {
        HOLD,
        PAYMENT_STARTED,
        PAYMENT_CONFIRMED,
        PAYMENT_FAILED,
        RELEASE,
        REHOLD,
        MODIFY,
        ZERO_AMOUNT_CONFIRMED
    }

    /**
     * 확보 시점의 Capacity와 수량을 보관한다.
     *
     * 현재 ReservationItem이 교체되더라도 이 기록은 유지한다.
     */
    public record Item(
            String capacityId,
            int quantity
    ) {
    }
}