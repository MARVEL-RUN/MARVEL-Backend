package kr.co.teambrain.marvelrun.admin.capacity.query.dto;

import java.util.List;

/** 화면 조회 구분이다. 결제 처리 중 예약도 홀딩 수량을 점유한다. */
public enum CapacityParticipantState {
    HELD(List.of("HELD", "PROCESSING")),
    CONFIRMED(List.of("CONSUMED"));

    private final List<String> reservationStatuses;

    /** 조회 구분에 대응하는 실제 예약 상태를 보관한다. */
    CapacityParticipantState(List<String> reservationStatuses) {
        this.reservationStatuses = reservationStatuses;
    }

    /** DB 조회 조건으로만 쓰며 응답에 내부 예약 상태를 추가하지 않는다. */
    public List<String> reservationStatuses() {
        return reservationStatuses;
    }
}
