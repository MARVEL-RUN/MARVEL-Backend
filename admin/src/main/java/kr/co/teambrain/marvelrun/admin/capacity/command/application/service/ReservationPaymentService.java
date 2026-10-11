package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/*
 * 사용자 서버 참조 시각: 2026-10-02 17:18:56 KST
 * 참조 파일: user/src/main/java/kr/co/teambrain/marvelrun/user/capacity/command/application/service/ReservationPaymentService.java
 * 유지한 동작: 예약 상태 전이, 확정 이력, heldCount에서 confirmedCount로의 이동을 함께 처리한다.
 * 관리자 적용 차이: 외부 승인 통신 없이 이미 결제된 원장을 같은 트랜잭션에서 연결한다.
 */
/** 이미 결제된 관리자 신청의 확보 수량을 확정한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationPaymentService {
    private final ReservationHistoryRecorder historyRecorder;
    private final CapacityCommandRepository capacities;

    /** 결제 귀속과 수량을 확정하고 외부결제 확정 이력은 참가자의 현장 KST로 기록한다. */
    public void confirmRegistrationPayments(String eventId, List<Reservation> reservations,
            List<Payment> payments, List<Map<String, Integer>> requirements, LocalDateTime now) {
        // 수량 이동과 상태 전이는 하나의 트랜잭션으로 보장한다.
        if (reservations.size() != payments.size() || reservations.size() != requirements.size()) {
            throw new CustomException(ErrorCode.INVALID_RESERVATION_ARGUMENT);
        }
        Map<String, Integer> totals = new TreeMap<>();
        requirements.forEach(values -> values.forEach((id, count) -> totals.merge(id, count, Math::addExact)));
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            if (capacities.confirmHeld(eventId, entry.getKey(), entry.getValue(), now) != 1) {
                throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT);
            }
        }

        // 외부 결제 사실을 명시하고 토스 승인 요청 이력은 만들지 않는다.
        for (int index = 0; index < reservations.size(); index++) {
            Reservation reservation = reservations.get(index);
            Payment payment = payments.get(index);
            if (!reservation.getRegistration().getId().equals(payment.getRegistration().getId())) {
                throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
            }
            reservation.startPayment();
            reservation.consumeAfterPayment();
            LocalDateTime occurredAt = reservation.getRegistration().isExternalPayment()
                    ? reservation.getRegistration().getTermsAgreedAt() : now;
            historyRecorder.appendReservationHistorySnapshot(reservation, ReservationHistoryEntry.Action.PAYMENT_CONFIRMED, occurredAt, payment.getId(),
                    "외부 단말기 결제 완료 관리자 등록", List.of());
            historyRecorder.recordCompletedPaymentHistory(reservation, payment.getId(), occurredAt);
        }
    }
}
