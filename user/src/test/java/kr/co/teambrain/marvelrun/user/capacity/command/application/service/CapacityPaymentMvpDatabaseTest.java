package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentTransportException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mock Toss 응답에 따른 실제 결제·예약·수량·납부금액의 연결을 검증한다.
 */
class CapacityPaymentMvpDatabaseTest extends CapacityMvpTestSupport {

    /**
     * 개인 승인 성공 시 수량과 납부금액을 확정한다.
     * 완료 처리를 다시 호출해도 금액·수량·성공 이력을 중복 반영하지 않는다.
     */
    @Test
    void personalSuccessAndDuplicateCompletionAreConsistent() {
        var result = personal(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();

        payments.confirm(confirmRequest(result.paymentId()));

        var context = savedContext(result.paymentId());

        paymentTransactions.completeConfirm(
                context,
                approved(context.paymentKey(), context.orderId(), context.amount()),
                NOW.plusSeconds(2)
        );

        assertThat(s(
                "select process_status from payment where id = ?",
                result.paymentId()
        )).isEqualTo("COMPLETED");

        assertThat(s(
                "select status from registration where id = ?",
                result.registrationId()
        )).isEqualTo("CONFIRMED");

        assertThat(jdbc.queryForObject(
                "select paid_amount from registration where id = ?",
                BigDecimal.class,
                result.registrationId()
        )).isEqualByComparingTo("40000");

        reservation(result.registrationId(), "CONSUMED", 1, 3);
        counters(total, 0, 1);
        counters(categoryACapacity, 0, 1);
        counters(shirtS, 0, 1);

        assertThat(n(
                """
                select count(*) from payment_process_log
                where payment_id = ? and process_type = 'CONFIRM_SUCCEEDED'
                """,
                result.paymentId()
        )).isEqualTo(1);

        verify(toss, times(1)).confirm(
                any(TossPaymentConfirmRequest.class), anyString()
        );
    }

    /**
     * 단체 승인 성공 시 구성원마다 자신의 계약금액만 반영한다.
     * 단체 합계 금액을 각 참가자에게 반복 반영해서는 안 된다.
     */
    @Test
    void groupSuccessAllocatesEachRegistrationAmountOnce() {
        var group = group(categoryA, categoryB);
        mockApprovalSuccess();

        payments.confirm(confirmRequest(group.paymentId()));

        var context = savedContext(group.paymentId());

        paymentTransactions.completeConfirm(
                context,
                approved(context.paymentKey(), context.orderId(), context.amount()),
                NOW.plusSeconds(2)
        );

        for (String registrationId : group.registrationIds()) {
            reservation(registrationId, "CONSUMED", 1, 3);

            assertThat(s(
                    "select status from registration where id = ?", registrationId
            )).isEqualTo("CONFIRMED");

            assertThat(jdbc.queryForObject(
                    "select paid_amount from registration where id = ?",
                    BigDecimal.class,
                    registrationId
            )).isEqualByComparingTo("40000");
        }

        assertThat(jdbc.queryForObject(
                "select sum(paid_amount) from registration where organization_id = ?",
                BigDecimal.class,
                group.organizationId()
        )).isEqualByComparingTo("80000");

        counters(total, 0, 2);
        counters(categoryACapacity, 0, 1);
        counters(categoryBCapacity, 0, 1);
        counters(shirtS, 0, 2);

        assertThat(n(
                """
                select count(*) from payment_process_log
                where payment_id = ? and process_type = 'CONFIRM_SUCCEEDED'
                """,
                group.paymentId()
        )).isEqualTo(1);
    }

    /**
     * 명확한 승인 거절은 Payment를 FAILED로 만들고 예약을 HELD로 복귀시킨다.
     * 실패 처리를 재실행해도 실패 이력과 수량이 중복 변경되지 않는다.
     */
    @Test
    void definiteFailureRestoresHeldWithoutReturningQuantity() {
        var result = personal(categoryA, "S", "1990-01-01");

        TossPaymentApiException failure = new TossPaymentApiException(
                403,
                "REJECT_CARD_PAYMENT",
                "테스트용 명확한 승인 거절"
        );

        when(toss.confirm(any(TossPaymentConfirmRequest.class), anyString()))
                .thenThrow(failure);

        expectError(
                ErrorCode.PAYMENT_CONFIRM_FAILED,
                () -> payments.confirm(confirmRequest(result.paymentId()))
        );

        paymentTransactions.failConfirm(
                savedContext(result.paymentId()),
                failure
        );

        assertThat(s(
                "select process_status from payment where id = ?",
                result.paymentId()
        )).isEqualTo("FAILED");

        reservation(result.registrationId(), "HELD", 1, 3);
        counters(total, 1, 0);
        counters(categoryACapacity, 1, 0);
        counters(shirtS, 1, 0);

        assertThat(jdbc.queryForObject(
                "select paid_amount from registration where id = ?",
                BigDecimal.class,
                result.registrationId()
        )).isEqualByComparingTo("0");

        assertThat(n(
                """
                select count(*) from payment_process_log
                where payment_id = ? and process_type = 'CONFIRM_FAILED'
                """,
                result.paymentId()
        )).isEqualTo(1);
    }

    /**
     * 승인 응답을 확인하지 못하면 UNKNOWN과 PROCESSING을 유지한다.
     * 미결제 반환 요청으로 이 점유를 해제할 수 없어야 한다.
     */
    @Test
    void unknownPaymentKeepsHoldAndRejectsRelease() {
        var result = personal(categoryA, "S", "1990-01-01");

        when(toss.confirm(any(TossPaymentConfirmRequest.class), anyString()))
                .thenThrow(new TossPaymentTransportException(
                        new IOException("테스트용 응답 유실")
                ));

        expectError(
                ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                () -> payments.confirm(confirmRequest(result.paymentId()))
        );

        expectError(
                ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                () -> registrations.releaseReservation(
                        eventId, result.registrationId()
                )
        );

        assertThat(s(
                "select process_status from payment where id = ?",
                result.paymentId()
        )).isEqualTo("UNKNOWN");

        reservation(result.registrationId(), "PROCESSING", 1, 2);
        counters(total, 1, 0);
        counters(categoryACapacity, 1, 0);
        counters(shirtS, 1, 0);

        assertThat(jdbc.queryForObject(
                "select paid_amount from registration where id = ?",
                BigDecimal.class,
                result.registrationId()
        )).isEqualByComparingTo("0");
    }

    /**
     * 결제 마감 정각부터 신규 승인을 차단한다.
     * 승인 거절 과정에서 신청 삭제나 확보 반환을 수행하지 않는다.
     */
    @Test
    void paymentDeadlineRejectsApprovalWithoutDeletingOrReleasing() {
        var result = personal(categoryA, "S", "1990-01-01");

        jdbc.update(
                "update event set payment_deadline = ? where id = ?",
                NOW, eventId
        );

        expectError(
                ErrorCode.EVENT_PAYMENT_CLOSED,
                () -> payments.confirm(confirmRequest(result.paymentId()))
        );

        verifyNoInteractions(toss);

        assertThat(s(
                "select process_status from payment where id = ?",
                result.paymentId()
        )).isEqualTo("READY");

        assertThat(s(
                "select status from registration where id = ?",
                result.registrationId()
        )).isEqualTo("PAYMENT_PENDING");

        assertThat(n(
                "select count(*) from registration where id = ? and is_del = false",
                result.registrationId()
        )).isEqualTo(1);

        reservation(result.registrationId(), "HELD", 1, 1);
        counters(total, 1, 0);
        counters(categoryACapacity, 1, 0);
        counters(shirtS, 1, 0);
    }

    /**
     * 외부 승인 성공 이후 로컬 확정 중 수량 불일치가 발생하면
     * 확정 중간 변경을 롤백하고 결제를 UNKNOWN으로 남긴다.
     *
     * 종목 카운터 훼손은 로컬 반영 실패를 재현하기 위한 테스트 주입이다.
     */
    @Test
    void localCompletionFailureRollsBackAndMarksUnknown() {
        var result = personal(categoryA, "S", "1990-01-01");

        when(toss.confirm(any(TossPaymentConfirmRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    TossPaymentConfirmRequest request =
                            invocation.getArgument(0);

                    /*
                     * Tx1 커밋 후, Tx2 시작 전에 불일치 상황을 만든다.
                     * 전체 정원 확정은 먼저 성공하고 종목 확정에서 실패한다.
                     */
                    jdbc.update(
                            "update capacity set held_count = 0 where id = ?",
                            categoryACapacity
                    );

                    return approved(
                            request.paymentKey(),
                            request.orderId(),
                            request.amount()
                    );
                });

        expectError(
                ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                () -> payments.confirm(confirmRequest(result.paymentId()))
        );

        assertThat(s(
                "select process_status from payment where id = ?",
                result.paymentId()
        )).isEqualTo("UNKNOWN");

        reservation(result.registrationId(), "PROCESSING", 1, 2);

        // 전체 정원의 먼저 실행된 확정 UPDATE도 롤백되어야 한다.
        counters(total, 1, 0);
        counters(shirtS, 1, 0);

        // 테스트가 주입한 불일치 값은 Tx2 이전에 저장했으므로 그대로 남는다.
        counters(categoryACapacity, 0, 0);

        assertThat(s(
                "select status from registration where id = ?",
                result.registrationId()
        )).isEqualTo("PAYMENT_PENDING");

        assertThat(jdbc.queryForObject(
                "select paid_amount from registration where id = ?",
                BigDecimal.class,
                result.registrationId()
        )).isEqualByComparingTo("0");

        assertThat(n(
                """
                select count(*) from payment_process_log
                where payment_id = ? and process_type = 'CONFIRM_SUCCEEDED'
                """,
                result.paymentId()
        )).isZero();
    }
}