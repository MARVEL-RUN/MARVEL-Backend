package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry.*;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 실제 DB 없이 변경 전후 선택과 금융 근거의 불변 기록을 검증한다. */
class ReservationHistoryRecorderTest {
    private final EntityManager em = mock(EntityManager.class);
    private final ReservationHistoryRecorder recorder = new ReservationHistoryRecorder(em);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 12, 0);

    /** FREE 선택과 당시 표기명을 저장하고 반복 수정 시 이전 스냅샷을 보존한다. */
    @Test void preservesBeforeAndAfterAcrossRepeatedModification() {
        Registration registration = mock(Registration.class);
        @SuppressWarnings("unchecked") TypedQuery<String> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(String.class))).thenReturn(query);
        when(query.setParameter(eq("id"), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("당시 기념품"));
        when(registration.getSouvenirJson()).thenReturn(List.of(new SouvenirJson("s", "FREE")));
        when(registration.getStatus()).thenReturn(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        Reservation reservation = Reservation.builder().registration(registration).status(ReservationStatus.CONSUMED).build();
        recorder.appendReservationHistorySnapshot(reservation, Action.MODIFY, now, null, "수정", List.of());
        when(registration.getSouvenirJson()).thenReturn(List.of(new SouvenirJson("s", "L")));
        recorder.completeReservationModificationSnapshot(reservation);
        Detail first = reservation.getHistory().getLast().detail();
        assertThat(first.before().souvenirs().getFirst().selectedSize()).isEqualTo("FREE");
        assertThat(first.after().souvenirs().getFirst().selectedSize()).isEqualTo("L");
        assertThat(first.financiallyConfirmed()).isFalse();
        recorder.appendReservationHistorySnapshot(reservation, Action.MODIFY, now.plusSeconds(1), null, "재수정", List.of());
        assertThat(first.after().souvenirs().getFirst().selectedSize()).isEqualTo("L");
    }

    /** 부분 정산은 미확정이고 마지막 정산만 확정하며 같은 원장 결과는 중복 기록하지 않는다. */
    @Test void distinguishesIntermediateRefundAndDeduplicatesFinalEvidence() {
        Registration registration = mock(Registration.class);
        when(registration.getStatus()).thenReturn(RegistrationStatus.PARTIAL_REFUND_REQUIRED);
        when(registration.getContractAmount()).thenReturn(BigDecimal.TEN);
        when(registration.getPaidAmount()).thenReturn(new BigDecimal("20"));
        Reservation reservation = Reservation.builder().registration(registration).status(ReservationStatus.CONSUMED).build();
        recorder.recordRegistrationSettlementHistory(reservation, "p", List.of("a"), "c1", List.of("ca1"), now);
        assertThat(reservation.getHistory().getLast().detail().financiallyConfirmed()).isFalse();
        when(registration.getStatus()).thenReturn(RegistrationStatus.CONFIRMED);
        when(registration.getPaidAmount()).thenReturn(BigDecimal.TEN);
        recorder.recordRegistrationSettlementHistory(reservation, "p", List.of("a"), "c2", List.of("ca2"), now.plusSeconds(1));
        recorder.recordRegistrationSettlementHistory(reservation, "p", List.of("a"), "c2", List.of("ca2"), now.plusSeconds(2));
        assertThat(reservation.getHistory()).hasSize(2);
        assertThat(reservation.getHistory().getLast().detail().financiallyConfirmed()).isTrue();
        assertThat(reservation.getHistory().getLast().detail().paymentCancelAllocationIds()).containsExactly("ca2");
    }

    /** 무변경 확정 참가자를 정산 목록에 포함했다고 이력을 새로 생성하지 않는다. */
    @Test void unchangedConfirmedParticipantDoesNotCreateHistory() {
        Registration registration = mock(Registration.class);
        when(registration.getStatus()).thenReturn(RegistrationStatus.CONFIRMED);
        when(registration.getContractAmount()).thenReturn(BigDecimal.ZERO);
        when(registration.getPaidAmount()).thenReturn(BigDecimal.ZERO);
        Reservation reservation = Reservation.builder().registration(registration).status(ReservationStatus.CONSUMED).build();
        recorder.recordRegistrationSettlementHistory(reservation, null, List.of(), null, List.of(), now);
        assertThat(reservation.getHistory()).isEmpty();
    }
}
