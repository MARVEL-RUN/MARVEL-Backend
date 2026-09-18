package kr.co.teambrain.marvelrun.user.capacity.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.ReservationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * User 모듈의 개별 신청에 대한 자원 확보 상태를 관리하는 엔티티이다.
 * <p>
 * Registration 한 건당 하나만 생성한다.
 * 단체 신청도 구성원별로 생성하며, 단체 전체의 원자성은
 * 서비스에서 구성원들의 처리를 동일 트랜잭션으로 묶어 보장한다.
 * <p>
 * 예약 상태 변경은 관련 Capacity의 수량 변경과 함께 처리해야 한다.
 */
@Getter
@SuperBuilder
@Entity
@Table(
        name = "reservation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_registration",
                        columnNames = "registration_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends ReservationBase<Registration> {

    /**
     * 검증과 저장을 마친 신청에 연결할 HELD 예약 객체를 생성한다.
     * <p>
     * DB 저장과 Capacity 수량 변경은 호출 서비스에서 처리한다.
     */
    public static Reservation createHeld(Registration registration) {

        return Reservation.builder()
                .registration(registration)
                .status(ReservationStatus.HELD)
                .expiresAt(null)
                .build();
    }

    /**
     * 미결제 홀딩 예약을 반환 상태로 전환한다.
     *
     * 이미 반환된 예약은 변경하지 않아 중복 반환을 방지한다.
     * PROCESSING과 CONSUMED는 반환할 수 없다.
     *
     * 호출 서비스는 상태 변경과 Capacity 수량 반환을
     * 동일 트랜잭션에서 수행해야 한다.
     *
     * @return 이번 호출에서 반환 상태로 변경했다면 true
     */
    public boolean releaseHeld() {
        if (status == ReservationStatus.RELEASED) {
            return false;
        }

        requireStatus(ReservationStatus.HELD);

        status = ReservationStatus.RELEASED;
        return true;
    }

    /**
     * 반환된 예약을 새로운 확보 회차의 HELD 상태로 전환한다.
     *
     * Reservation 행과 기존 이력은 유지하고 확보 회차만 증가시킨다.
     * 실제 수량 확보, 상세 교체 및 REHOLD 이력은 호출 서비스에서 처리한다.
     *
     * 수량 확보에 실패하면 이 상태 변경과 회차 증가도 함께 롤백해야 한다.
     */
    public void reacquireHeld() {

        requireStatus(ReservationStatus.RELEASED);

        status = ReservationStatus.HELD;
        holdSequence++;
        expiresAt = null;
    }

    /**
     * 확보된 예약을 결제 처리 중 상태로 전환한다.
     *
     * 이미 처리 중이거나 확정·반환된 예약은 신규 승인에 사용할 수 없다.
     * 수량은 홀딩 상태로 유지한다.
     */
    public void startPayment() {
        requireStatus(ReservationStatus.HELD);
        status = ReservationStatus.PROCESSING;
    }

    /**
     * 명확하게 실패한 결제의 예약을 홀딩 상태로 되돌린다.
     *
     * 확보한 수량을 반환하지 않으며,
     * 이후 새로운 결제 시도에 사용할 수 있도록 한다.
     */
    public void restoreHeldAfterPaymentFailure() {
        requireStatus(ReservationStatus.PROCESSING);
        status = ReservationStatus.HELD;
    }

    /**
     * 결제 성공에 따라 예약을 사용 확정 상태로 전환한다.
     *
     * 호출 서비스에서 동일 트랜잭션으로
     * Capacity의 홀딩 수량을 확정 수량으로 이동해야 한다.
     */
    public void consumeAfterPayment() {
        requireStatus(ReservationStatus.PROCESSING);
        status = ReservationStatus.CONSUMED;
    }

    /**
     * 현재 확보 회차와 처리 후 상태를 포함하여 "이력" 한 건을 추가한다.
     *
     * 기존 목록을 복사한 뒤 새 목록으로 교체하여 JSON 변경을 명시한다.
     * 기존 이력과 전달받은 상세 목록은 직접 수정하지 않는다.
     *
     * 상태 및 Capacity 변경과 동일 트랜잭션에서 호출해야 한다.
     */
    public void appendHistory(
            ReservationHistoryEntry.Action action,
            LocalDateTime occurredAt,
            String paymentId,
            String reason,
            List<ReservationHistoryEntry.Item> items
    ) {
        ReservationHistoryEntry entry =
                new ReservationHistoryEntry(
                        action,
                        holdSequence,
                        occurredAt,
                        status,
                        paymentId,
                        reason,
                        items
                );

        List<ReservationHistoryEntry> updatedHistory =
                new ArrayList<>(history);

        updatedHistory.add(entry);

        history = updatedHistory;
    }


    /**
     * 현재 상태가 요청한 전이를 허용하는지 검증한다.
     *
     * 오류 메시지에는 예약 식별자와 실제·기대 상태를 기록한다.
     */
    private void requireStatus(ReservationStatus expected) {
        if (status != expected) {
            throw new CustomException(
                    ErrorCode.RESERVATION_STATE_CONFLICT,
                    " reservationId=" + id
                            + ", expected=" + expected
                            + ", actual=" + status
            );
        }
    }
}