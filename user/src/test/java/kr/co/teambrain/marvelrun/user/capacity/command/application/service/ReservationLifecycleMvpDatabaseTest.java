package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 미결제 반환·재확보·주문 무효화 및 승인 시작과의 충돌을 검증한다.
 */
class ReservationLifecycleMvpDatabaseTest extends CapacityMvpTestSupport {

    /**
     * 동일 예약을 두 번 반환해도 수량과 RELEASE 이력은 한 번만 변경한다.
     * 기존 READY 주문은 무효화되어 승인할 수 없어야 한다.
     */
    @Test
    void duplicateReleaseDoesNotSubtractTwice() {
        var result = personal(categoryA, "S", "1990-01-01");
        var oldRequest = confirmRequest(result.paymentId());

        registrations.releaseReservation(eventId, result.registrationId());
        registrations.releaseReservation(eventId, result.registrationId());

        reservation(result.registrationId(), "RELEASED", 1, 2);
        counters(total, 0, 0);
        counters(categoryACapacity, 0, 0);
        counters(shirtS, 0, 0);

        assertThat(s(
                "select process_status from payment where id = ?",
                result.paymentId()
        )).isEqualTo("INVALIDATED");

        assertThat(n(
                "select count(*) from registration where id = ? and is_del = false",
                result.registrationId()
        )).isEqualTo(1);

        expectError(
                ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                () -> payments.confirm(oldRequest)
        );

        verifyNoInteractions(toss);
    }

    /**
     * 단체 일부 반환은 해당 참가자의 수량만 감소시킨다.
     * 재결제 준비에서는 반환된 참가자만 재확보하고 기존 HELD는 유지한다.
     */
    @Test
    void partialGroupReleaseAndRepaymentPreserveOtherHolds() {
        var group = group(categoryA, categoryB);

        String releasedId = group.registrationIds().get(0);
        String retainedId = group.registrationIds().get(1);

        List<String> retainedItems = itemIds(retainedId);

        organizations.releaseReservations(
                eventId,
                group.organizationId(),
                List.of(releasedId)
        );

        reservation(releasedId, "RELEASED", 1, 2);
        reservation(retainedId, "HELD", 1, 1);
        counters(total, 1, 0);
        counters(shirtS, 1, 0);

        assertThat(s(
                "select process_status from payment where id = ?",
                group.paymentId()
        )).isEqualTo("INVALIDATED");

        var repayment = organizations.prepareRepayment(
                eventId, group.organizationId()
        );

        reservation(releasedId, "HELD", 2, 3);
        reservation(retainedId, "HELD", 1, 1);

        assertThat(itemIds(retainedId))
                .containsExactlyElementsOf(retainedItems);

        counters(total, 2, 0);
        counters(categoryACapacity, 1, 0);
        counters(categoryBCapacity, 1, 0);
        counters(shirtS, 2, 0);

        assertThat(repayment.paymentId()).isNotEqualTo(group.paymentId());
        assertThat(repayment.paymentAmount()).isEqualByComparingTo("80000");
    }

    /**
     * 재확보는 기존 Reservation 한 행을 유지하면서
     * 현재 상세를 교체하고 회차와 JSON 이력을 증가시킨다.
     */
    @Test
    void personalRepaymentReusesReservationAndReplacesItems() {
        var original = personal(categoryA, "S", "1990-01-01");

        String reservationId = s(
                "select id from reservation where registration_id = ?",
                original.registrationId()
        );

        List<String> oldItems = itemIds(original.registrationId());

        registrations.releaseReservation(eventId, original.registrationId());

        var repayment = registrations.prepareRepayment(
                eventId, original.registrationId()
        );

        assertThat(repayment.registrationId()).isEqualTo(original.registrationId());
        assertThat(repayment.paymentId()).isNotEqualTo(original.paymentId());

        assertThat(s(
                "select id from reservation where registration_id = ?",
                original.registrationId()
        )).isEqualTo(reservationId);

        reservation(original.registrationId(), "HELD", 2, 3);

        List<String> newItems = itemIds(original.registrationId());

        assertThat(newItems).hasSize(3);
        assertThat(newItems.stream().noneMatch(oldItems::contains)).isTrue();

        assertThat(s(
                """
                select json_unquote(json_extract(history, '$[2].action'))
                from reservation where registration_id = ?
                """,
                original.registrationId()
        )).isEqualTo("REHOLD");

        assertThat(s(
                "select process_status from payment where id = ?",
                repayment.paymentId()
        )).isEqualTo("READY");

        counters(total, 1, 0);
        counters(categoryACapacity, 1, 0);
        counters(shirtS, 1, 0);
    }

    /**
     * 재확보가 실패하면 회차·상태·상세·이력이 반환 직후 상태로 남아야 한다.
     * 새 Payment를 만들지 않으며 앞서 증가한 카운터도 롤백한다.
     */
    @Test
    void failedReacquisitionDoesNotCreatePaymentOrChangeReservation() {
        var original = personal(categoryA, "S", "1990-01-01");

        registrations.releaseReservation(eventId, original.registrationId());

        List<String> releasedItems = itemIds(original.registrationId());

        // 전체 정원 확보 이후 종목 확보에서 실패하도록 만든다.
        jdbc.update(
                "update capacity set active = false where id = ?",
                categoryACapacity
        );

        expectError(
                ErrorCode.CAPACITY_ACQUIRE_FAILED,
                () -> registrations.prepareRepayment(
                        eventId, original.registrationId()
                )
        );

        reservation(original.registrationId(), "RELEASED", 1, 2);

        assertThat(itemIds(original.registrationId()))
                .containsExactlyElementsOf(releasedItems);

        assertThat(n(
                "select count(*) from payment where registration_id = ?",
                original.registrationId()
        )).isEqualTo(1);

        assertThat(s(
                "select process_status from payment where id = ?",
                original.paymentId()
        )).isEqualTo("INVALIDATED");

        counters(total, 0, 0);
        counters(categoryACapacity, 0, 0);
        counters(shirtS, 0, 0);

        verifyNoInteractions(toss);
    }

    /**
     * 같은 주문의 승인 시작과 반환을 동시에 실행하면 하나만 성공해야 한다.
     *
     * 승인 시작 승리: CONFIRMING / PROCESSING / held 유지.
     * 반환 승리: INVALIDATED / RELEASED / held 반환.
     */
    @Test
    void confirmStartAndReleaseCannotBothSucceed() throws Exception {
        var result = personal(categoryA, "S", "1990-01-01");
        var request = confirmRequest(result.paymentId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> confirm = executor.submit(() ->
                    competingAction(
                            ready,
                            start,
                            () -> paymentTransactions.beginConfirm(
                                    request, "race-test", NOW
                            )
                    )
            );

            Future<Boolean> release = executor.submit(() ->
                    competingAction(
                            ready,
                            start,
                            () -> registrations.releaseReservation(
                                    eventId, result.registrationId()
                            )
                    )
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean confirmSucceeded = confirm.get(30, TimeUnit.SECONDS);
            boolean releaseSucceeded = release.get(30, TimeUnit.SECONDS);

            assertThat(confirmSucceeded ^ releaseSucceeded)
                    .as("승인 시작과 반환 중 정확히 하나만 성공")
                    .isTrue();

            if (confirmSucceeded) {
                assertThat(s(
                        "select process_status from payment where id = ?",
                        result.paymentId()
                )).isEqualTo("CONFIRMING");

                reservation(result.registrationId(), "PROCESSING", 1, 2);
                counters(total, 1, 0);
                counters(categoryACapacity, 1, 0);
                counters(shirtS, 1, 0);
            } else {
                assertThat(s(
                        "select process_status from payment where id = ?",
                        result.paymentId()
                )).isEqualTo("INVALIDATED");

                reservation(result.registrationId(), "RELEASED", 1, 2);
                counters(total, 0, 0);
                counters(categoryACapacity, 0, 0);
                counters(shirtS, 0, 0);
            }

            verifyNoInteractions(toss);
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS))
                    .as("경쟁 작업 종료")
                    .isTrue();
        }
    }

    /**
     * 같은 출발 신호를 받은 뒤 업무 서비스를 실행한다.
     *
     * 정상적인 결제 상태 충돌만 경쟁 패배로 인정한다.
     * SQL 오류·데드락·타임아웃 등 다른 실패는 테스트 실패로 전달한다.
     */
    private boolean competingAction(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable action
    ) throws InterruptedException {
        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("경쟁 테스트 출발 신호 시간 초과");
        }

        try {
            action.run();
            return true;
        } catch (CustomException exception) {
            assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_NOT_CONFIRMABLE);
            return false;
        }
    }
}