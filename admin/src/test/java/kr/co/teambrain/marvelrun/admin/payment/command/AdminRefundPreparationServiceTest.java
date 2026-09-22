package kr.co.teambrain.marvelrun.admin.payment.command;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationPricingService;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.RegistrationPolicyCandidateValidator;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyCandidateResult;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.creator.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundTarget;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 기존 계산기·계약 상태 전이를 연결하고 관리자 경계와 준비 로그를 검증한다. DB 롤백은 별도 통합 테스트가 담당한다. */
class AdminRefundPreparationServiceTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private final AdminRefundAccessService access = mock(AdminRefundAccessService.class);
    private final AdminRefundPreparationStore store = mock(AdminRefundPreparationStore.class);
    private final RegistrationPolicyCandidateValidator policies = mock(RegistrationPolicyCandidateValidator.class);
    private final RegistrationPricingService pricing = spy(new RegistrationPricingService());
    private final CapacityRequirementResolver requirements = mock(CapacityRequirementResolver.class);
    private final ReservationCapacityDiffService diffs = mock(ReservationCapacityDiffService.class);
    private final CapacityModificationService movement = mock(CapacityModificationService.class);
    private final ReservationRemovalService removal = mock(ReservationRemovalService.class);
    private final PaymentCancelAllocationCreator allocations = mock(PaymentCancelAllocationCreator.class);
    private final AdminRefundTime time = mock(AdminRefundTime.class);
    private final AdminRefundPreparationService service = new AdminRefundPreparationService(new AdminRefundPreparationTransactionService(access, store, policies,
            pricing, requirements, diffs, movement, removal, new ModificationRefundPlanner(), allocations, time, VALIDATION.getValidator()));
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 2, 12, 0);
    private final AdminRefundCommandContext command = new AdminRefundCommandContext("request", "admin", "종목 변경");
    private Registration registration;
    private Reservation reservation;
    private Event event;
    private EventCategory category;
    private List<SouvenirJson> souvenirs;

    /** Jakarta Validation 자원을 닫는다. */
    @AfterAll
    static void close() { VALIDATION.close(); }
    /** 결제 완료 원장과 CONSUMED 예약을 준비한다. 날짜는 접수기간과 무관하게 고정한다. */
    @BeforeEach
    void fixture() {
        event = mock(Event.class); when(event.getId()).thenReturn("test-marvelrun");
        when(event.getStartDate()).thenReturn(now.plusDays(1));
        category = mock(EventCategory.class); when(category.getId()).thenReturn("c");
        when(category.getAmount()).thenReturn(new BigDecimal("40000"));
        souvenirs = List.of(new SouvenirJson("s", "M"));
        registration = Registration.builder().id("r").event(event).eventCategory(category).birth("1990-01-01")
                .souvenirJson(souvenirs).contractAmount(new BigDecimal("70000")).paidAmount(new BigDecimal("70000"))
                .status(RegistrationStatus.CONFIRMED).build();
        reservation = Reservation.builder().id("v").registration(registration).status(ReservationStatus.CONSUMED).version(0L).build();
        Payment payment = Payment.builder().id("p").registration(registration).amount(new BigDecimal("70000"))
                .processStatus(PaymentProcessStatus.COMPLETED).paymentKey("fixture-key").orderId("fixture-order").build();
        PaymentAllocation allocation = PaymentAllocation.builder().id("application-admin-refund-test.yml").payment(payment).registration(registration)
                .allocatedAmount(new BigDecimal("70000")).build();
        when(access.lock("test-marvelrun", null, List.of("r"))).thenReturn(new AdminRefundLockedScope(
                "test-marvelrun", null, List.of(new AdminRefundLockedScope.RegistrationRow("r", null, false,
                RegistrationStatus.CONFIRMED, new BigDecimal("70000"), new BigDecimal("70000"))), List.of(), List.of()));
        when(store.current(Event.class, "test-marvelrun")).thenReturn(event);
        when(store.current(Registration.class, "r")).thenReturn(registration);
        when(store.reservations(List.of("r"))).thenReturn(List.of(reservation));
        when(store.ledgers(any())).thenReturn(List.of(new RefundPaymentLedger(payment, List.of(allocation), List.of(), List.of())));
        when(time.now()).thenReturn(now);
        when(policies.validateAll(eq(event), anyList(), eq(now))).thenReturn(List.of(new RegistrationPolicyCandidateResult(category,
                java.time.LocalDate.of(1990, 1, 1), souvenirs)));
        when(requirements.resolveAll(eq("test-marvelrun"), anyList())).thenReturn(List.of(Map.of("capacity", 1)));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> { if (!((List<?>) invocation.getArgument(1)).isEmpty()) reservation.releaseForParticipantRemoval(); return null; })
                .when(removal).releaseAll(anyString(), anyList(), any());
    }

    /** 실제 가격 계산 결과로 준비하며 순납부액을 미리 줄이지 않고 로그를 남긴다. */
    @Test
    void partialRefundUsesPolicyPriceAndPreservesPaidAmount() {
        var result = service.preparePartial("test-marvelrun", null, List.of(target(null)), command);
        assertThat(result.preparedAt()).isEqualTo(now);
        assertThat(result.refunds().getFirst().amount()).isEqualByComparingTo("30000");
        assertThat(result.refunds().getFirst().status()).isEqualTo(PaymentCancelStatus.PROCESSING);
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("70000");
        assertThat(registration.getContractAmount()).isEqualByComparingTo("40000");
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.PARTIAL_REFUND_REQUIRED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        var log = org.mockito.ArgumentCaptor.forClass(PaymentProcessLog.class);
        verify(store).log(log.capture());
        assertThat(log.getValue().getSource()).isEqualTo(PaymentProcessSource.ADMIN);
        assertThat(log.getValue().getMetadata()).containsEntry("requestId", "request").containsEntry("adminId", "admin")
                .containsEntry("preparedAt", now.toString());
        verify(store).flush();
    }

    /** 전액 환불은 기존 참가 취소 메서드와 자원 반환을 사용한다. */
    @Test
    void fullRefundCancelsParticipationAndReleasesReservation() {
        var result = service.prepareFull("test-marvelrun", null, List.of("r"), command);
        assertThat(result.refunds().getFirst().amount()).isEqualByComparingTo("70000");
        assertThat(registration.isSoftDeleted()).isTrue();
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
        assertThat(registration.getContractAmount()).isEqualByComparingTo("0");
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("70000");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verifyNoInteractions(policies, requirements, movement);
    }

    /** 0원 정책 결과의 사전 선택으로 참가 유지와 취소를 분기하며 새 상태를 만들지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void zeroContractUsesExplicitParticipationSetting(boolean keep) {
        doReturn(BigDecimal.ZERO).when(pricing).calculateContractAmount(any(), any(), anyString());
        var result = service.preparePartial("test-marvelrun", null, List.of(target(keep)), command);
        assertThat(result.refunds().getFirst().amount()).isEqualByComparingTo("70000");
        assertThat(registration.isSoftDeleted()).isEqualTo(!keep);
        assertThat(reservation.getStatus()).isEqualTo(keep ? ReservationStatus.CONSUMED : ReservationStatus.RELEASED);
        assertThat(registration.getStatus()).isEqualTo(keep ? RegistrationStatus.PARTIAL_REFUND_REQUIRED : RegistrationStatus.CANCELLATION_PENDING);
    }

    /** 금액이 줄지 않는 후보는 일반 변경 API처럼 처리하지 않고 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"70000", "80000"})
    void rejectsNonRefundCandidateBeforeWrites(String amount) {
        doReturn(new BigDecimal(amount)).when(pricing).calculateContractAmount(any(), any(), anyString());
        assertThatThrownBy(() -> service.preparePartial("test-marvelrun", null, List.of(target(true)), command)).isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR));
        verifyNoInteractions(movement, removal, allocations);
        verify(store, never()).save(any());
        assertThat(registration.getContractAmount()).isEqualByComparingTo("70000");
    }

    /** 원장이 부족하면 실제 계약·자원 변경 전에 거절한다. */
    @Test
    void rejectsMissingLedgerBeforeMutations() {
        when(store.ledgers(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.prepareFull("test-marvelrun", null, List.of("r"), command)).isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR));
        verifyNoInteractions(movement, removal, allocations);
        verify(store, never()).save(any());
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("70000");
    }
    /** 배치 호출자가 이미 가진 잠금을 정지시킨 채 내부 트랜잭션을 시작하지 않는다. */
    @Test
    void rejectsAmbientTransactionBeforeAccess() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.prepareFull("test-marvelrun", null, List.of("r"), command))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(access);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }
    /** 관리자 금액 입력 없이 종목·기념품 후보만 제공한다. */
    private AdminPaymentPartialRefundTarget target(Boolean keep) { return new AdminPaymentPartialRefundTarget("r", "c", souvenirs, keep); }
}
