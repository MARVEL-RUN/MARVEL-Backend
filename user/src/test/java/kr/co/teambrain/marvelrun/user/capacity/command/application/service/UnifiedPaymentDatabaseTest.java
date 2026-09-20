package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.math.BigDecimal;
import java.io.IOException;
import java.util.concurrent.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRetryPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentTransportException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import java.util.List;
import java.util.Map;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationSettlementService;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmContext;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmRequest;
import org.junit.jupiter.api.Test;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 실제 정산·승인·귀속·정원 처리를 검증하며 외부 Toss는 공통 대역을 사용한다. */
@Import({RegistrationModificationSettlementService.class, PaymentRetryPreparationService.class})
class UnifiedPaymentDatabaseTest extends CapacityMvpTestSupport {
    @Autowired
    private RegistrationModificationSettlementService settlement;
    @Autowired
    private PaymentCommandRepository paymentRepository;
    @Autowired
    private PaymentRetryPreparationService retries;

    /** 신규 참가비와 기존 참가자의 추가금을 한 번 승인하고 중복 결과 반영을 막는다. */
    @Test
    void mixedOrderConfirmsOnlyNewReservationAndCreditsEachParticipantOnce() {
        Fixture fixture = prepareMixedOrder();
        assertThat(s("select purpose from payment where id = ?", fixture.paymentId())).isEqualTo("MIXED_PAYMENT");
        assertThat(allocationSum(fixture.paymentId())).isEqualByComparingTo("60000");
        assertThat(allocationCount(fixture.paymentId())).isEqualTo(2);
        assertThat(s("select allocation_purpose from payment_allocation where payment_id = ? and registration_id = ?",
                fixture.paymentId(), fixture.existingId())).isEqualTo("ADDITIONAL_PAYMENT");
        assertThat(s("select allocation_purpose from payment_allocation where payment_id = ? and registration_id = ?",
                fixture.paymentId(), fixture.newId())).isEqualTo("REGISTRATION_TRY");
        List<Map<String, Object>> oldReservation = reservationSnapshot(fixture.existingId());

        payments.confirm(confirmRequest(fixture.paymentId()));
        PaymentConfirmContext context = savedContext(fixture.paymentId());
        paymentTransactions.completeConfirm(context,
                approved(context.paymentKey(), context.orderId(), context.amount()), NOW.plusSeconds(3));

        assertThat(money(fixture.existingId())).isEqualByComparingTo("60000");
        assertThat(money(fixture.newId())).isEqualByComparingTo("40000");
        assertThat(reservationSnapshot(fixture.existingId())).isEqualTo(oldReservation);
        assertThat(s("select status from reservation where registration_id = ?", fixture.newId())).isEqualTo("CONSUMED");
        counters(total, 0, 2);
        counters(categoryACapacity, 0, 1);
        counters(categoryBCapacity, 0, 1);
        counters(shirtS, 0, 2);
        verify(toss, times(1)).confirm(any(TossPaymentConfirmRequest.class), anyString());
    }

    /** 신규 예약의 관련 주문 조회가 혼합 주문을 포함하고 타 구성원의 완료 주문을 제외한다. */
    @Test
    void initialReservationLookupIncludesMixedOrderWithoutUnrelatedCompletedOrder() {
        Fixture fixture = prepareMixedOrder();
        List<String> ids = tx.execute(status -> paymentRepository.findAllRelatedToRegistrationsForUpdate(
                List.of(fixture.newId()), PaymentPurpose.REGISTRATION_TRY)
                .stream().map(Payment::getId).toList());
        assertThat(ids).contains(fixture.paymentId());
        for (String id : ids) {
            assertThat(s("select process_status from payment where id = ?", id)).isNotEqualTo("COMPLETED");
        }
    }

    /** 명확한 실패는 신규 예약만 복원하고 기존 확정 예약과 순납부액을 유지한다. */
    @Test
    void mixedFailureRestoresOnlyInitialReservation() {
        Fixture fixture = prepareMixedOrder();
        List<Map<String, Object>> oldReservation = reservationSnapshot(fixture.existingId());
        TossPaymentApiException failure = new TossPaymentApiException(403, "REJECT_CARD_PAYMENT", "테스트 승인 거절");
        doThrow(failure).when(toss).confirm(any(TossPaymentConfirmRequest.class), anyString());
        PaymentConfirmRequest request = confirmRequest(fixture.paymentId());
        expectError(ErrorCode.PAYMENT_CONFIRM_FAILED, () -> payments.confirm(request));
        paymentTransactions.failConfirm(savedContext(fixture.paymentId()), failure);
        assertThat(s("select process_status from payment where id = ?", fixture.paymentId())).isEqualTo("FAILED");
        assertThat(s("select status from reservation where registration_id = ?", fixture.newId())).isEqualTo("HELD");
        assertThat(reservationSnapshot(fixture.existingId())).isEqualTo(oldReservation);
        assertThat(money(fixture.existingId())).isEqualByComparingTo("40000");
        assertThat(money(fixture.newId())).isEqualByComparingTo("0");
        counters(total, 1, 1);
        assertThat(n("select count(*) from payment_process_log where payment_id = ? and process_type = 'CONFIRM_FAILED'",
                fixture.paymentId())).isEqualTo(1);
    }

    /** 주문 준비 후 부족액이 바뀌면 외부 승인 전에 거절하고 예약을 변경하지 않는다. */
    @Test
    void staleAllocationIsRejectedBeforeToss() {
        Fixture fixture = prepareMixedOrder();
        jdbc.update("update registration set contract_amount = 70000, version = version + 1 where id = ?", fixture.existingId());
        PaymentConfirmRequest request = confirmRequest(fixture.paymentId());
        expectError(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, () -> payments.confirm(request));
        assertThat(s("select process_status from payment where id = ?", fixture.paymentId())).isEqualTo("READY");
        assertThat(s("select status from reservation where registration_id = ?", fixture.newId())).isEqualTo("HELD");
        counters(total, 1, 1);
        verifyNoInteractions(toss);
    }

    /** 혼합 주문의 기존 참가자도 확정 예약이 있어야 하며 반환된 예약은 결제할 수 없다. */
    @Test
    void additionalTargetWithReleasedReservationIsRejected() {
        Fixture fixture = prepareMixedOrder();
        jdbc.update("update reservation set status = 'RELEASED', version = version + 1 where registration_id = ?", fixture.existingId());
        PaymentConfirmRequest request = confirmRequest(fixture.paymentId());
        expectError(ErrorCode.RESERVATION_STATE_CONFLICT, () -> payments.confirm(request));
        verifyNoInteractions(toss);
    }

    /** 귀속 구분이 누락된 혼합 주문을 현재 예약 상태만으로 승인하지 않는다. */
    @Test
    void missingMixedPurposeIsRejectedBeforeToss() {
        Fixture fixture = prepareMixedOrder();
        jdbc.update("update payment_allocation set allocation_purpose = null where payment_id = ?", fixture.paymentId());
        PaymentConfirmRequest request = confirmRequest(fixture.paymentId());
        expectError(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, () -> payments.confirm(request));
        verifyNoInteractions(toss);
    }

    /** 실패한 혼합 주문을 한 건으로 재준비하고 같은 요청에서는 그 주문을 재사용한다. */
    @Test
    void failedMixedOrderRetriesAllSharesOnce() {
        Fixture fixture = failedMixedOrder();
        List<Map<String, Object>> oldReservation = reservationSnapshot(fixture.existingId());
        Order first = retries.prepareOrganization(eventId, fixture.organizationId(), fixture.paymentId(), groupAccess(fixture));
        Order second = retries.prepareOrganization(eventId, fixture.organizationId(), fixture.paymentId(), groupAccess(fixture));
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(first.amount()).isEqualByComparingTo("60000");
        assertThat(allocationCount(first.paymentId())).isEqualTo(2);
        assertThat(s("select process_status from payment where id = ?", fixture.paymentId())).isEqualTo("FAILED");
        doAnswer(invocation -> {
            TossPaymentConfirmRequest request = invocation.getArgument(0);
            return approved(request.paymentKey(), request.orderId(), request.amount());
        }).when(toss).confirm(any(TossPaymentConfirmRequest.class), anyString());
        payments.confirm(confirmRequest(first.paymentId()));
        assertThat(money(fixture.existingId())).isEqualByComparingTo("60000");
        assertThat(money(fixture.newId())).isEqualByComparingTo("40000");
        assertThat(reservationSnapshot(fixture.existingId())).isEqualTo(oldReservation);
        counters(total, 0, 2);
    }

    /** 반환된 신규 참가자만 재확보하고 기존 참가자의 예약은 유지한다. */
    @Test
    void releasedNewParticipantIsReheldWithoutTouchingExistingParticipant() {
        Fixture fixture = failedMixedOrder();
        organizations.releaseReservations(eventId, fixture.organizationId(), List.of(fixture.newId()));
        List<Map<String, Object>> existing = reservationSnapshot(fixture.existingId());
        int sequence = n("select hold_sequence from reservation where registration_id = ?", fixture.newId());
        Order order = retries.prepareOrganization(eventId, fixture.organizationId(), fixture.paymentId(), groupAccess(fixture));
        assertThat(order.amount()).isEqualByComparingTo("60000");
        assertThat(n("select hold_sequence from reservation where registration_id = ?", fixture.newId())).isEqualTo(sequence + 1);
        assertThat(s("select status from reservation where registration_id = ?", fixture.newId())).isEqualTo("HELD");
        assertThat(reservationSnapshot(fixture.existingId())).isEqualTo(existing);
        counters(total, 1, 1);
    }

    /** 재확보 후 주문 생성이 실패하면 정원·확보 회차·새 주문을 모두 롤백한다. */
    @Test
    void orderCreationFailureRollsBackRehold() {
        Fixture fixture = failedMixedOrder();
        organizations.releaseReservations(eventId, fixture.organizationId(), List.of(fixture.newId()));
        List<Map<String, Object>> before = reservationSnapshot(fixture.newId());
        int count = n("select count(*) from payment where organization_id = ?", fixture.organizationId());
        kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator creatorSpy =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(paymentCreator);
        doThrow(new kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException(
                ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR))
                .when(creatorSpy).createMixedPayment(any(), any(BigDecimal.class), anyString());
        expectError(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, () -> retries.prepareOrganization(
                eventId, fixture.organizationId(), fixture.paymentId(), groupAccess(fixture)));
        assertThat(reservationSnapshot(fixture.newId())).isEqualTo(before);
        assertThat(n("select count(*) from payment where organization_id = ?", fixture.organizationId())).isEqualTo(count);
        counters(total, 0, 1);
    }

    /** 결과 불명인 혼합 주문은 신규 재결제와 최초 예약 반환을 모두 차단한다. */
    @Test
    void unknownMixedOrderBlocksRetryAndRelease() {
        Fixture fixture = prepareMixedOrder();
        doThrow(new TossPaymentTransportException(new IOException("테스트 응답 유실")))
                .when(toss).confirm(any(TossPaymentConfirmRequest.class), anyString());
        expectError(ErrorCode.PAYMENT_CONFIRM_UNKNOWN, () -> payments.confirm(confirmRequest(fixture.paymentId())));
        expectError(ErrorCode.PAYMENT_NOT_CONFIRMABLE, () -> retries.prepareOrganization(
                eventId, fixture.organizationId(), fixture.paymentId(), groupAccess(fixture)));
        expectError(ErrorCode.PAYMENT_NOT_CONFIRMABLE, () -> organizations.releaseReservations(
                eventId, fixture.organizationId(), List.of(fixture.newId())));
        assertThat(s("select status from reservation where registration_id = ?", fixture.newId())).isEqualTo("PROCESSING");
        assertThat(s("select status from reservation where registration_id = ?", fixture.existingId())).isEqualTo("CONSUMED");
        counters(total, 1, 1);
    }

    /** 다른 단체의 경로나 틀린 현재 인증정보로는 재결제 주문을 준비하지 못한다. */
    @Test
    void retryRequiresCurrentOrganizationAccessAndScope() {
        Fixture fixture = failedMixedOrder();
        expectError(ErrorCode.ORGANIZATION_ACCESS_DENIED, () -> retries.prepareOrganization(
                eventId, "other-organization", fixture.paymentId(), groupAccess(fixture)));
        expectError(ErrorCode.ORGANIZATION_ACCESS_DENIED, () -> retries.prepareOrganization(
                eventId, fixture.organizationId(), fixture.paymentId(), new OrganizationAccessRequest("wrong", "wrong")));
    }

    /** 동시에 재준비해도 새 READY 주문은 한 건이며 두 요청은 같은 주문을 받는다. */
    @Test
    void concurrentRetryReturnsOneReadyOrder() throws Exception {
        Fixture fixture = failedMixedOrder();
        OrganizationAccessRequest access = groupAccess(fixture);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Order> action = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("재결제 경합 시작 신호를 받지 못했습니다.");
            }
            return retries.prepareOrganization(eventId, fixture.organizationId(), fixture.paymentId(), access);
        };
        try {
            Future<Order> first = pool.submit(action);
            Future<Order> second = pool.submit(action);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Order firstResult = first.get(30, TimeUnit.SECONDS);
            Order secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.paymentId()).isEqualTo(secondResult.paymentId());
            assertThat(n("select count(*) from payment where organization_id = ? and process_status = 'READY'", fixture.organizationId())).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 개인 최초·추가 결제 모두 같은 재준비 진입점으로 처리하며 기존 순납부액을 보존한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void personalInitialAndAdditionalUseSameRetryFlow(boolean additional) {
        RegistrationCreateResponse participant = personal(categoryA, "S", "1990-01-01");
        String sourceId = participant.paymentId();
        if (additional) {
            mockApprovalSuccess();
            payments.confirm(confirmRequest(sourceId));
            RegistrationModificationSettlementResult changed = tx.execute(status -> {
                jdbc.update("update registration set contract_amount = 60000, status = 'ADDITIONAL_PAYMENT_REQUIRED', version = version + 1 where id = ?", participant.registrationId());
                em.clear();
                return settlement.settle(eventId, null, List.of(participant.registrationId()), NOW);
            });
            sourceId = changed.orders().get(0).paymentId();
        }
        String failedId = sourceId;
        doThrow(new TossPaymentApiException(403, "REJECT_CARD_PAYMENT", "테스트 승인 거절"))
                .when(toss).confirm(any(TossPaymentConfirmRequest.class), anyString());
        expectError(ErrorCode.PAYMENT_CONFIRM_FAILED, () -> payments.confirm(confirmRequest(failedId)));
        String id = participant.registrationId();
        RegistrationAccessRequest access = new RegistrationAccessRequest(
                s("select name from registration where id = ?", id), s("select birth from registration where id = ?", id),
                s("select ph_num from registration where id = ?", id), "Test1234!");
        Order order = retries.preparePersonal(eventId, id, failedId, access);
        assertThat(order.amount()).isEqualByComparingTo(additional ? "20000" : "40000");
        assertThat(money(id)).isEqualByComparingTo(additional ? "40000" : "0");
        assertThat(retries.preparePersonal(eventId, id, failedId, access).paymentId()).isEqualTo(order.paymentId());
    }

    /** 실제 승인 거절을 통해 실패한 혼합 주문을 준비한다. */
    private Fixture failedMixedOrder() {
        Fixture fixture = prepareMixedOrder();
        doThrow(new TossPaymentApiException(403, "REJECT_CARD_PAYMENT", "테스트 승인 거절"))
                .when(toss).confirm(any(TossPaymentConfirmRequest.class), anyString());
        expectError(ErrorCode.PAYMENT_CONFIRM_FAILED, () -> payments.confirm(confirmRequest(fixture.paymentId())));
        return fixture;
    }

    /** 테스트가 생성한 단체의 현재 로그인 식별자를 사용한다. */
    private OrganizationAccessRequest groupAccess(Fixture fixture) {
        return new OrganizationAccessRequest(s("select login_id from organization where id = ?", fixture.organizationId()), "Test1234!");
    }

    /**
     * 기존 승인 참가자 1명과 신규 홀딩 참가자 1명을 준비하고 실제 수정 정산을 호출한다.
     * 신청 수정 자체의 회귀 검증은 기존 RegistrationModificationDatabaseTest에서 유지한다.
     */
    private Fixture prepareMixedOrder() {
        OrgRegistrationCreateResponse group = group(categoryA);
        mockApprovalSuccess();
        payments.confirm(confirmRequest(group.paymentId()));
        RegistrationCreateResponse newcomer = personal(categoryB, "S", "1990-01-01");
        String existingId = group.registrationIds().get(0);
        RegistrationModificationSettlementResult result = tx.execute(status -> {
            jdbc.update("update registration set contract_amount = 60000, status = 'ADDITIONAL_PAYMENT_REQUIRED', version = version + 1 where id = ?", existingId);
            jdbc.update("update registration set organization_id = ?, version = version + 1 where id = ?", group.organizationId(), newcomer.registrationId());
            jdbc.update("update payment set process_status = 'INVALIDATED', version = version + 1 where id = ?", newcomer.paymentId());
            em.clear();
            return settlement.settle(eventId, group.organizationId(), List.of(existingId, newcomer.registrationId()), NOW);
        });
        assertThat(result).isNotNull();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.refunds()).isEmpty();
        clearInvocations(toss);
        return new Fixture(result.orders().get(0).paymentId(), existingId, newcomer.registrationId(), group.organizationId());
    }

    /** 현재 순납부액을 조회한다. */
    private BigDecimal money(String registrationId) {
        return jdbc.queryForObject("select paid_amount from registration where id = ?", BigDecimal.class, registrationId);
    }

    /** 기존 확정 예약의 상태·확보 회차·버전·이력 전체가 유지되는지 비교한다. */
    private List<Map<String, Object>> reservationSnapshot(String registrationId) {
        return jdbc.queryForList("select * from reservation where registration_id = ?", registrationId);
    }

    /** 혼합 주문 검증에 필요한 식별자만 전달한다. */
    private record Fixture(String paymentId, String existingId, String newId, String organizationId) { }
}
