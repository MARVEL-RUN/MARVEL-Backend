package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry.*;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** 선택 당시 정보와 참가자별 금융 확정 근거를 현재 쓰기 트랜잭션에 기록한다. */
@Service
@RequiredArgsConstructor
public class ReservationHistoryRecorder {
    private final EntityManager entityManager;

    /** 현재 선택의 식별자·표기·사이즈·금액을 불변 값으로 캡처한다. */
    public Snapshot captureRegistrationSelectionSnapshot(Registration registration) {
        List<Selection> selections = new ArrayList<>();
        List<SouvenirJson> souvenirs = registration.getSouvenirJson() == null ? List.of() : registration.getSouvenirJson();
        for (SouvenirJson souvenir : souvenirs) {
            List<String> names = entityManager.createQuery("select s.name from Souvenir s where s.id = :id", String.class)
                    .setParameter("id", souvenir.souvenirId()).getResultList();
            selections.add(new Selection(souvenir.souvenirId(), names.isEmpty() ? null : names.getFirst(), souvenir.selectedSize()));
        }
        return new Snapshot(registration.getEventCategory() == null ? null : registration.getEventCategory().getId(),
                registration.getEventCategory() == null ? null : registration.getEventCategory().getName(), selections,
                registration.getContractAmount(), registration.getPaidAmount(),
                registration.getStatus() == null ? null : registration.getStatus().name());
    }

    /** 기존 자원 이력에 당시 선택을 붙이며 MODIFY는 변경 전 선택만 우선 보관한다. */
    public void appendReservationHistorySnapshot(Reservation reservation, Action action, LocalDateTime now, String paymentId,
            String reason, List<Item> items) {
        Snapshot selection = captureRegistrationSelectionSnapshot(reservation.getRegistration());
        reservation.appendHistory(action, now, paymentId, reason, items);
        reservation.attachLatestHistoryDetail(new Detail(1, UUID.randomUUID().toString(), "ADMIN", action.name(),
                action == Action.MODIFY ? selection : null, action == Action.MODIFY ? null : selection,
                action == Action.ZERO_AMOUNT_CONFIRMED, List.of(), null, List.of()));
    }

    /** 자원 이동 이후 실제 신청값 반영을 마친 동일 수정 이력에 변경 후 값을 연결한다. */
    public void completeReservationModificationSnapshot(Reservation reservation) {
        List<ReservationHistoryEntry> entries = reservation.getHistory();
        if (entries.isEmpty()) { return; }
        ReservationHistoryEntry last = entries.getLast();
        if (last.action() != Action.MODIFY || last.detail() == null || last.detail().after() != null) { return; }
        Detail detail = last.detail();
        reservation.attachLatestHistoryDetail(new Detail(1, detail.changeId(), detail.source(), detail.eventType(),
                detail.before(), captureRegistrationSelectionSnapshot(reservation.getRegistration()), false, List.of(), null, List.of()));
    }

    /** 실제 원장 반영 이후 완료 또는 중간 정산 사실을 기록한다. 이미 반영한 원장 ID는 중복 기록하지 않는다. */
    public void recordRegistrationSettlementHistory(Reservation reservation, String paymentId, List<String> allocationIds,
            String cancelId, List<String> cancelAllocationIds, LocalDateTime now) {
        String kind = cancelId != null ? "REFUND_APPLIED" : paymentId != null ? "PAYMENT_APPLIED" : "NO_BALANCE_CONFIRMED";
        boolean confirmed = reservation.getRegistration().getStatus() == RegistrationStatus.CONFIRMED
                && reservation.getRegistration().getContractAmount() != null
                && reservation.getRegistration().getPaidAmount() != null
                && reservation.getRegistration().getContractAmount().compareTo(reservation.getRegistration().getPaidAmount()) == 0;
        if (paymentId == null && cancelId == null) {
            if (!confirmed || reservation.getHistory().isEmpty()) { return; }
            Detail latest = reservation.getHistory().getLast().detail();
            if (latest == null || !"MODIFY".equals(latest.eventType()) || latest.after() == null) { return; }
        }
        String changeId = reservation.getHistory().stream().filter(e -> e.detail() != null && e.action() == Action.MODIFY)
                .reduce((a,b) -> b).map(e -> e.detail().changeId()).orElse("INITIAL");
        if (reservation.getHistory().stream().anyMatch(e -> e.detail() != null
                && kind.equals(e.detail().eventType()) && Objects.equals(paymentId, e.paymentId())
                && Objects.equals(cancelId, e.detail().paymentCancelId())
                && (paymentId != null || cancelId != null || changeId.equals(e.detail().changeId())))) { return; }

        // 새 금융 유형은 detail에 넣어 기존 Action enum의 파싱 계약을 보존한다.
        ReservationHistoryEntry last = reservation.getHistory().isEmpty() ? null : reservation.getHistory().getLast();
        boolean initialConfirmation = cancelId == null && paymentId != null && last != null
                && last.action() == Action.PAYMENT_CONFIRMED && Objects.equals(paymentId, last.paymentId())
                && last.detail() != null && "PAYMENT_CONFIRMED".equals(last.detail().eventType());
        if (!initialConfirmation) {
            reservation.appendHistory(paymentId != null ? Action.PAYMENT_CONFIRMED : Action.MODIFY, now, paymentId,
                    "정산 결과 반영", List.of());
        }
        reservation.attachLatestHistoryDetail(new Detail(1, changeId, "ADMIN", kind, null,
                captureRegistrationSelectionSnapshot(reservation.getRegistration()), confirmed, allocationIds, cancelId, cancelAllocationIds));
    }
    /** 외부결제 등록처럼 이미 저장된 완료 원장의 참가자 귀속을 연결한다. */
    public void recordCompletedPaymentHistory(Reservation reservation, String paymentId, LocalDateTime now) {
        List<String> ids = entityManager.createQuery("select a.id from PaymentAllocation a where a.payment.id = :payment and a.registration.id = :registration", String.class)
                .setParameter("payment", paymentId).setParameter("registration", reservation.getRegistration().getId()).getResultList();
        recordRegistrationSettlementHistory(reservation, paymentId, ids, null, List.of(), now);
    }
}

