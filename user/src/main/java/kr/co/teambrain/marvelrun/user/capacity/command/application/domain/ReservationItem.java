package kr.co.teambrain.marvelrun.user.capacity.command.application.domain;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.ReservationItemBase;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Objects;


/**
 * User 모듈의 예약과 실제 확보한 Capacity를 연결하는 엔티티이다.
 *
 * 하나의 행에 하나의 Capacity와 양수 수량을 기록하며,
 * 동일 예약과 Capacity 조합의 중복 저장을 방지한다.
 *
 * 상세 내역 저장과 Capacity의 수량 변경은
 * 서비스에서 동일 트랜잭션으로 처리해야 한다.
 */
@Getter
@Entity
@Table(
        name = "reservation_item",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_item_capacity",
                        columnNames = {"reservation_id", "capacity_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_reservation_item_capacity",
                        columnList = "capacity_id"
                )
        }
)
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationItem
        extends ReservationItemBase<Reservation, Capacity> {


    /**
     * 예약과 Capacity를 연결하는 상세 객체를 생성한다.
     *
     * 전달된 참조는 호출부가 보장하며,
     * 상세 내역의 불변 조건인 양수 수량만 검증한다.
     * DB 저장과 카운터 변경은 수행하지 않는다.
     */
    public static ReservationItem create(
            Reservation reservation,
            Capacity capacity,
            int quantity
    ) {
        if (quantity <= 0) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 예약 상세 수량은 1 이상이어야 합니다. quantity=" + quantity
            );
        }
        return ReservationItem.builder()
                .reservation(reservation)
                .capacity(capacity)
                .quantity(quantity)
                .build();
    }
}