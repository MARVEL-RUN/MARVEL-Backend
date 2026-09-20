package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 준비 서비스의 주문 재사용·교체·권한·충돌·구성원별 금액 보존을 검증한다. */
class AdditionalPaymentPreparationServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 12, 0);
    private static final RegistrationAccessRequest ACCESS =
            new RegistrationAccessRequest("참가자", "1990-01-01", "010-1234-5678", "password");
    private AdditionalPaymentPreparationLoader loader;
    private PaymentCreator creator;
    private PaymentAllocationCreator allocationCreator;
    private PaymentAllocationCommandRepository allocations;
    private PaymentCommandRepository payments;
    private AdditionalPaymentPreparationService service;
    private Event event;
    private Registration registration;

    /** 실제 금액 계산·충돌 검사와 외부 저장 대역을 조합한다. */
    @BeforeEach
    void setUp() {
        loader = mock(AdditionalPaymentPreparationLoader.class);
        creator = mock(PaymentCreator.class);
        allocationCreator = mock(PaymentAllocationCreator.class);
        allocations = mock(PaymentAllocationCommandRepository.class);
        payments = mock(PaymentCommandRepository.class);
        ServerTimeProvider time = mock(ServerTimeProvider.class);
        when(time.currentDateTime()).thenReturn(NOW);
        event = mock(Event.class);
        when(event.getPaymentDeadline()).thenReturn(NOW.plusDays(1));
        registration = participant("r", null, "40000", "30000", RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        service = new AdditionalPaymentPreparationService(loader, new PaymentFinancialConflictGuard(),
                new AdditionalPaymentTargetResolver(), creator, allocationCreator, allocations, payments, time);
    }

    /** 최초 준비 후 같은 요청은 이미 생성한 주문을 반환하고 새 저장을 반복하지 않는다. */
    @Test
    void repeatedPreparationReturnsSameReadyOrder() {
        Payment created = order("new", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        AdditionalPaymentScope first = personalScope(List.of(), List.of());
        AdditionalPaymentScope second = personalScope(List.of(created), List.of());
        when(loader.lockPersonal("event", "r")).thenReturn(first, second);
        when(creator.createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString())).thenReturn(created);
        record(created, registration, "10000");

        AdditionalPaymentPrepareResponse firstResponse = service.preparePersonal("event", "r", ACCESS);
        AdditionalPaymentPrepareResponse secondResponse = service.preparePersonal("event", "r", ACCESS);

        assertThat(firstResponse.reused()).isFalse();
        assertThat(secondResponse.reused()).isTrue();
        assertThat(secondResponse.orderId()).isEqualTo(firstResponse.orderId());
        verify(creator, times(1)).createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString());
        verify(allocationCreator, times(1)).create(eq(created), anyList());
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("30000");
    }

    /** 부족액이 달라진 이전 주문은 무효화하고 새 부족액으로 교체한다. */
    @Test
    void replacesOutdatedReadyOrder() {
        Payment old = order("old", registration, null, "20000", PaymentPurpose.ADDITIONAL_PAYMENT);
        Payment created = order("new", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        useScope(personalScope(List.of(old), List.of()));
        record(old, registration, "20000");
        when(creator.createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString())).thenReturn(created);

        AdditionalPaymentPrepareResponse response = service.preparePersonal("event", "r", ACCESS);

        assertThat(old.getProcessStatus()).isEqualTo(PaymentProcessStatus.INVALIDATED);
        assertThat(response.paymentAmount()).isEqualByComparingTo("10000");
        assertThat(response.reused()).isFalse();
        verify(allocationCreator).create(eq(created), anyList());
    }

    /** 추가 부족액이 없어지면 남아 있는 추가 READY만 무효화한다. */
    @Test
    void noOutstandingAmountInvalidatesOnlyAdditionalReady() {
        registration = participant("r", null, "30000", "30000", RegistrationStatus.CONFIRMED);
        Payment old = order("old", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        useScope(personalScope(List.of(old), List.of()));
        record(old, registration, "10000");
        AdditionalPaymentPrepareResponse result = service.preparePersonal("event", "r", ACCESS);
        assertThat(result.paymentRequired()).isFalse();
        assertThat(result.orderId()).isNull();
        assertThat(old.getProcessStatus()).isEqualTo(PaymentProcessStatus.INVALIDATED);
        verifyNoInteractions(creator, allocationCreator);
    }

    /** 명확 실패한 추가 주문은 보존하고 새로운 주문으로 다시 준비한다. */
    @Test
    void failedAdditionalOrderCanBePreparedAgain() {
        Payment failed = Payment.builder().id("failed")
                .processStatus(PaymentProcessStatus.FAILED).build();
        Payment created = order("new", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        useScope(personalScope(List.of(failed), List.of()));
        when(creator.createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString())).thenReturn(created);
        assertThat(service.preparePersonal("event", "r", ACCESS).reused()).isFalse();
        assertThat(failed.getProcessStatus()).isEqualTo(PaymentProcessStatus.FAILED);
    }

    /** 처리 중 또는 결과불명 승인에는 주문을 재사용하거나 새로 만들지 않는다. */
    @ParameterizedTest
    @EnumSource(value = PaymentProcessStatus.class, names = {"CONFIRMING", "UNKNOWN"})
    void blocksUnsettledPayment(PaymentProcessStatus status) {
        Payment unsettled = Payment.builder().id("pending").processStatus(status).build();
        useScope(personalScope(List.of(unsettled), List.of()));
        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.PAYMENT_ADJUSTMENT_CONFLICT);
        verifyNoInteractions(creator, allocationCreator, allocations, payments);
    }

    /** 처리 중 또는 결과불명 환불과 새 추가 주문을 동시에 시작하지 않는다. */
    @ParameterizedTest
    @EnumSource(value = PaymentCancelStatus.class, names = {"PROCESSING", "UNKNOWN"})
    void blocksUnsettledRefund(PaymentCancelStatus status) {
        PaymentCancel cancellation = PaymentCancel.builder().id("cancel").status(status).build();
        useScope(personalScope(List.of(), List.of(cancellation)));
        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.PAYMENT_ADJUSTMENT_CONFLICT);
        verifyNoInteractions(creator, allocationCreator, allocations, payments);
    }

    /** 잘못된 현재 본인확인 값으로는 주문을 변경하지 않는다. */
    @Test
    void rejectsWrongPasswordBeforeChangingOrders() {
        useScope(personalScope(List.of(), List.of()));
        RegistrationAccessRequest wrong = new RegistrationAccessRequest(
                ACCESS.name(), ACCESS.birth(), ACCESS.phNum(), "wrong");
        assertError(() -> service.preparePersonal("event", "r", wrong), ErrorCode.REGISTRATION_ACCESS_DENIED);
        verifyNoInteractions(creator, allocationCreator, allocations, payments);
    }

    /** 마감 정각부터는 새로운 추가 주문 준비를 허용하지 않는다. */
    @Test
    void rejectsDeadlineBoundary() {
        when(event.getPaymentDeadline()).thenReturn(NOW);
        useScope(personalScope(List.of(), List.of()));
        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.ADDITIONAL_PAYMENT_DEADLINE_PASSED);
        verifyNoInteractions(creator, allocationCreator, allocations, payments);
    }

    /** 누락된 예약을 임의로 생성하거나 추가 결제로 우회하지 않는다. */
    @Test
    void rejectsMissingReservation() {
        AdditionalPaymentScope scope = new AdditionalPaymentScope(event, null, List.of(registration),
                List.of(), List.of(), List.of());
        useScope(scope);
        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.RESERVATION_NOT_FOUND);
        verifyNoInteractions(creator, allocationCreator);
    }

    /** 원 주문 귀속 합계가 틀리면 교체로 숨기지 않고 정합성 오류로 차단한다. */
    @Test
    void malformedReadyOrderIsNotSilentlyReplaced() {
        Payment old = order("old", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        useScope(personalScope(List.of(old), List.of()));
        record(old, registration, "9999");
        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
        assertThat(old.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        verifyNoInteractions(creator, allocationCreator);
    }

    /** 추가 귀속 저장 실패를 삼키지 않아 호출 트랜잭션이 롤백할 수 있게 한다. */
    @Test
    void propagatesAllocationFailure() {
        Payment created = order("new", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        useScope(personalScope(List.of(), List.of()));
        when(creator.createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString())).thenReturn(created);
        doThrow(new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR))
                .when(allocationCreator).create(eq(created), anyList());
        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
        // 실제 DB 저장/READY 무효화 롤백은 별도 DB 테스트에서 검증한다.
    }

    /** 단체의 환불 대상·추가 대상·최초 미결제 대상을 분리하고 최초 주문을 보존한다. */
    @Test
    void groupPreparesOnlyConsumedDebtAndPreservesInitialReady() {
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn("org");
        when(organization.getLoginId()).thenReturn("leader");
        when(organization.getPassword()).thenReturn("password");
        Registration a = participant("a", organization, "30000", "40000", RegistrationStatus.PARTIAL_REFUND_REQUIRED);
        Registration b = participant("b", organization, "50000", "30000", RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        Registration c = participant("c", organization, "30000", "0", RegistrationStatus.PAYMENT_PENDING);
        Payment initial = order("initial", null, organization, "30000", PaymentPurpose.REGISTRATION_TRY);
        Payment additional = order("additional", null, organization, "20000", PaymentPurpose.ADDITIONAL_PAYMENT);
        AdditionalPaymentScope scope = new AdditionalPaymentScope(event, organization, List.of(a, b, c),
                List.of(reservation(a, ReservationStatus.CONSUMED), reservation(b, ReservationStatus.CONSUMED),
                        reservation(c, ReservationStatus.HELD)), List.of(initial), List.of());
        when(loader.lockOrganization("event", "org")).thenReturn(scope);
        record(initial, c, "30000");
        when(creator.createAdditionalPayment(eq(organization), any(BigDecimal.class), anyString())).thenReturn(additional);

        AdditionalPaymentPrepareResponse result = service.prepareOrganization("event", "org",
                new OrganizationAccessRequest("leader", "password"));

        assertThat(result.paymentAmount()).isEqualByComparingTo("20000");
        assertThat(initial.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaymentAllocationTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(allocationCreator).create(eq(additional), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).registration()).isSameAs(b);
        assertThat(captor.getValue().get(0).amount()).isEqualByComparingTo("20000");
        assertThat(a.getPaidAmount()).isEqualByComparingTo("40000");
        assertThat(b.getPaidAmount()).isEqualByComparingTo("30000");
    }

    /** 무료 확정 이후 유료로 수정한 신청도 최초 홀딩으로 오인하지 않는다. */
    @Test
    void zeroPaidConsumedRegistrationCanPrepareAdditionalOrder() {
        registration = participant("r", null, "10000", "0", RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        Payment created = order("new", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        AdditionalPaymentScope scope = personalScope(List.of(), List.of());
        useScope(scope);
        when(creator.createAdditionalPayment(eq(registration), any(BigDecimal.class), anyString())).thenReturn(created);

        assertThat(service.preparePersonal("event", "r", ACCESS).paymentAmount()).isEqualByComparingTo("10000");
        assertThat(scope.reservations().get(0).getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("0");
    }

    /** 같은 부족액에 중복 READY가 있으면 하나만 유지하여 재청구 대상을 줄인다. */
    @Test
    void keepsOnlyOneMatchingReadyOrder() {
        Payment first = order("a", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        Payment duplicate = order("b", registration, null, "10000", PaymentPurpose.ADDITIONAL_PAYMENT);
        useScope(personalScope(List.of(duplicate, first), List.of()));
        record(first, registration, "10000");
        record(duplicate, registration, "10000");

        assertThat(service.preparePersonal("event", "r", ACCESS).paymentId()).isEqualTo("a");
        assertThat(first.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        assertThat(duplicate.getProcessStatus()).isEqualTo(PaymentProcessStatus.INVALIDATED);
        verifyNoInteractions(creator, allocationCreator);
    }

    /** 최초 주문과 추가 주문이 같은 신청을 동시에 청구하지 못하게 한다. */
    @Test
    void initialReadyCoveringAdditionalTargetBlocksPreparation() {
        Payment initial = order("initial", registration, null, "10000", PaymentPurpose.REGISTRATION_TRY);
        useScope(personalScope(List.of(initial), List.of()));
        record(initial, registration, "10000");

        assertError(() -> service.preparePersonal("event", "r", ACCESS), ErrorCode.PAYMENT_ADJUSTMENT_CONFLICT);
        assertThat(initial.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        verifyNoInteractions(creator, allocationCreator);
    }

    /** 단체 비밀번호가 다르면 부족액 계산과 주문 처리를 시작하지 않는다. */
    @Test
    void rejectsWrongOrganizationCredentials() {
        Organization organization = mock(Organization.class);
        when(organization.getLoginId()).thenReturn("leader");
        when(organization.getPassword()).thenReturn("password");
        AdditionalPaymentScope scope = new AdditionalPaymentScope(event, organization,
                List.of(), List.of(), List.of(), List.of());
        when(loader.lockOrganization("event", "org")).thenReturn(scope);

        assertError(() -> service.prepareOrganization("event", "org",
                new OrganizationAccessRequest("leader", "wrong")), ErrorCode.ORGANIZATION_ACCESS_DENIED);
        verifyNoInteractions(creator, allocationCreator, allocations, payments);
    }

    /** 실제 개인 엔티티와 확정 예약으로 보호 범위를 구성한다. */
    private AdditionalPaymentScope personalScope(List<Payment> orders, List<PaymentCancel> cancellations) {
        return new AdditionalPaymentScope(event, null, List.of(registration),
                List.of(reservation(registration, ReservationStatus.CONSUMED)), orders, cancellations);
    }

    /** 완성된 범위를 stubbing에 전달하여 중첩 stubbing을 피한다. */
    private void useScope(AdditionalPaymentScope scope) {
        when(loader.lockPersonal("event", "r")).thenReturn(scope);
    }

    /** 실제 금액과 본인확인 정보를 가진 신청을 구성한다. */
    private Registration participant(String id, Organization organization, String contract,
                                     String paid, RegistrationStatus status) {
        return Registration.builder().id(id).event(event).organization(organization)
                .name(ACCESS.name()).birth(ACCESS.birth()).phNum(ACCESS.phNum()).password(ACCESS.password())
                .contractAmount(new BigDecimal(contract)).paidAmount(new BigDecimal(paid)).status(status).build();
    }

    /** 저장된 예약 상태를 재현한다. */
    private Reservation reservation(Registration registration, ReservationStatus status) {
        return Reservation.builder().id("reservation-" + registration.getId())
                .registration(registration).status(status).build();
    }

    /** READY 주문을 실제 엔티티로 구성하여 무효화 동작도 검사한다. */
    private Payment order(String id, Registration registration, Organization organization,
                          String amount, PaymentPurpose purpose) {
        return Payment.builder().id(id).registration(registration).organization(organization)
                .amount(new BigDecimal(amount)).purpose(purpose).processStatus(PaymentProcessStatus.READY)
                .orderId("order-" + id).orderName("추가 결제").build();
    }

    /** 원 주문에 저장된 불변 귀속을 조회 대역에 등록한다. */
    private void record(Payment payment, Registration registration, String amount) {
        PaymentAllocation allocation = PaymentAllocation.builder().id("allocation-" + payment.getId())
                .payment(payment).registration(registration).allocatedAmount(new BigDecimal(amount)).build();
        when(allocations.findAllByPayment_IdOrderByRegistration_IdAsc(payment.getId())).thenReturn(List.of(allocation));
    }

    /** 예상한 업무 오류 코드까지 확인한다. */
    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}