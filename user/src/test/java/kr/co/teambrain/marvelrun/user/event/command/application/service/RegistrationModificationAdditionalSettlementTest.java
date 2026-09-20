package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.ModificationRefundPreparationService;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;

/** 기존 수정 정산 호출 한 번에서 추가 주문과 귀속이 함께 준비되는지 검증한다. */
class RegistrationModificationAdditionalSettlementTest {
    private final RegistrationCommandRepository registrations = mock(RegistrationCommandRepository.class);
    private final ReservationCommandRepository reservations = mock(ReservationCommandRepository.class);
    private final ReservationItemCommandRepository items = mock(ReservationItemCommandRepository.class);
    private final CapacityCommandRepository capacities = mock(CapacityCommandRepository.class);
    private final PaymentCreator creator = mock(PaymentCreator.class);
    private final PaymentAllocationCreator allocationCreator = mock(PaymentAllocationCreator.class);
    private final ModificationRefundPreparationService refundPreparation =
            mock(ModificationRefundPreparationService.class);
    private final Event event = mock(Event.class);
    private final RegistrationModificationSettlementService service = new RegistrationModificationSettlementService(
            registrations, reservations, items, capacities, creator, allocationCreator,
            new AdditionalPaymentTargetResolver(), refundPreparation);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 10, 0);

    /** 현재 수정 범위의 대회 식별자를 구성한다. */
    @BeforeEach
    void setUp() {
        when(event.getId()).thenReturn("event");
    }

    /** 수정된 계약금액과 기존 순납부액 차액만 주문으로 생성한다. */
    @ParameterizedTest
    @CsvSource({"60000,40000,20000", "10000,0,10000"})
    void personalModificationReturnsAdditionalOrder(String contract, String paid, String debt) {
        Registration registration = participant("r", null, contract, paid, false);
        scope(List.of(registration), List.of(reservation(registration, ReservationStatus.CONSUMED)));
        Payment payment = payment("additional", debt, PaymentPurpose.ADDITIONAL_PAYMENT);
        when(creator.createAdditionalPayment(eq(registration), eq(new BigDecimal(debt)), anyString())).thenReturn(payment);

        RegistrationModificationSettlementResult result = service.settle("event", null, List.of("r"), NOW);

        assertThat(result.orders()).hasSize(1);
        assertThat(result.orders().get(0).paymentId()).isEqualTo("additional");
        assertThat(result.orders().get(0).amount()).isEqualByComparingTo(debt);
        assertThat(result.members().get(0).status()).isEqualTo(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        assertThat(registration.getPaidAmount()).isEqualByComparingTo(paid);
        verify(allocationCreator).create(payment, List.of(new PaymentAllocationTarget(registration, new BigDecimal(debt))));
        verify(creator, never()).createInitialPayment(any(Registration.class), anyString());
        verifyNoInteractions(items, capacities);
        verify(registrations).flush();
    }

    /** 환불 또는 차액 없음이면 추가 주문과 0원 결제를 만들지 않는다. */
    @ParameterizedTest
    @CsvSource({"30000,PARTIAL_REFUND_REQUIRED", "40000,CONFIRMED"})
    void noPositiveDebtDoesNotCreatePayment(String contract, RegistrationStatus expected) {
        Registration registration = participant("r", null, contract, "40000", false);
        scope(List.of(registration), List.of(reservation(registration, ReservationStatus.CONSUMED)));

        RegistrationModificationSettlementResult result = service.settle("event", null, List.of("r"), NOW);

        assertThat(result.orders()).isEmpty();
        assertThat(result.members().get(0).status()).isEqualTo(expected);
        verifyNoInteractions(creator, allocationCreator, items, capacities);
    }

    /** 최초·추가 납부를 구분하고 환불과 제거 금액을 차감하지 않는다. */
    @Test
    void groupSeparatesInitialAdditionalAndRefundAmounts() {
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn("org");
        Registration refund = participant("a", organization, "30000", "40000", false);
        Registration additional = participant("b", organization, "60000", "40000", false);
        Registration initial = participant("c", organization, "30000", "0", false);
        Registration removed = participant("d", organization, "0", "40000", true);
        scope(List.of(refund, additional, initial, removed), List.of(
                reservation(refund, ReservationStatus.CONSUMED),
                reservation(additional, ReservationStatus.CONSUMED),
                reservation(initial, ReservationStatus.HELD),
                reservation(removed, ReservationStatus.RELEASED)));
        Payment first = payment("initial", "30000", PaymentPurpose.REGISTRATION_TRY);
        Payment extra = payment("additional", "20000", PaymentPurpose.ADDITIONAL_PAYMENT);
        when(creator.createInitialPayment(eq(organization), eq(new BigDecimal("30000")), anyString())).thenReturn(first);
        when(creator.createAdditionalPayment(eq(organization), eq(new BigDecimal("20000")), anyString())).thenReturn(extra);

        RegistrationModificationSettlementResult result = service.settle("event", "org", List.of("a", "b", "c", "d"), NOW);

        assertThat(result.orders()).extracting(RegistrationModificationSettlementResult.Order::paymentId)
                .containsExactly("initial", "additional");
        verify(allocationCreator).create(first, List.of(new PaymentAllocationTarget(initial, new BigDecimal("30000"))));
        verify(allocationCreator).create(extra, List.of(new PaymentAllocationTarget(additional, new BigDecimal("20000"))));
        assertThat(refund.getStatus()).isEqualTo(RegistrationStatus.PARTIAL_REFUND_REQUIRED);
        assertThat(removed.getStatus()).isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
        assertThat(refund.getPaidAmount()).isEqualByComparingTo("40000");
        assertThat(removed.getPaidAmount()).isEqualByComparingTo("40000");
        verifyNoInteractions(items, capacities);
    }

    /** 추가 귀속 실패를 삼키지 않아 상위 수정 트랜잭션 전체가 실패하도록 한다. */
    @Test
    void allocationFailureEscapesSettlement() {
        Registration registration = participant("r", null, "60000", "40000", false);
        scope(List.of(registration), List.of(reservation(registration, ReservationStatus.CONSUMED)));
        Payment payment = payment("additional", "20000", PaymentPurpose.ADDITIONAL_PAYMENT);
        when(creator.createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString())).thenReturn(payment);
        doThrow(new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR))
                .when(allocationCreator).create(eq(payment), anyList());

        assertThatThrownBy(() -> service.settle("event", null, List.of("r"), NOW))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR));
        verify(registrations, never()).flush();
    }

    /** 같은 수정 정산 응답에 서버가 준비한 환불 식별자를 포함한다. */
    @Test
    void includesPreparedRefundInExistingResponse() {
        Registration registration = participant("r", null, "30000", "40000", false);
        scope(List.of(registration), List.of(reservation(registration, ReservationStatus.CONSUMED)));
        RegistrationModificationSettlementResult.Refund refund = new RegistrationModificationSettlementResult.Refund(
                "cancel", "original", new BigDecimal("10000"),
                PaymentCancelStatus.PROCESSING,
                "trace");
        when(refundPreparation.prepare("event", null, List.of(registration))).thenReturn(List.of(refund));
        RegistrationModificationSettlementResult result = service.settle("event", null, List.of("r"), NOW);
        assertThat(result.refunds()).containsExactly(refund);
        assertThat(result.orders()).isEmpty();
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("40000");
    }

    /** 완성한 엔티티 목록으로 조회 대역을 설정하여 중첩 stubbing을 피한다. */
    private void scope(List<Registration> targets, List<Reservation> held) {
        for (Registration registration : targets) {
            when(registrations.findById(registration.getId())).thenReturn(Optional.of(registration));
        }
        when(reservations.findAllByRegistrationIds(anySet())).thenReturn(held);
    }

    /** 수정 결과가 이미 반영된 신청을 구성한다. */
    private Registration participant(String id, Organization organization, String contract, String paid, boolean deleted) {
        return Registration.builder().id(id).event(event).organization(organization).softDeleted(deleted)
                .contractAmount(new BigDecimal(contract)).paidAmount(new BigDecimal(paid))
                .status(RegistrationStatus.CONFIRMED).build();
    }

    /** 정산 직전의 예약 상태를 구성한다. */
    private Reservation reservation(Registration registration, ReservationStatus status) {
        return Reservation.builder().id("reservation-" + registration.getId()).registration(registration).status(status).build();
    }

    /** 생성기의 반환값으로 사용할 주문을 구성한다. */
    private Payment payment(String id, String amount, PaymentPurpose purpose) {
        return Payment.builder().id(id).orderId("order-" + id).orderName("참가비")
                .amount(new BigDecimal(amount)).purpose(purpose).processStatus(PaymentProcessStatus.READY).build();
    }
}