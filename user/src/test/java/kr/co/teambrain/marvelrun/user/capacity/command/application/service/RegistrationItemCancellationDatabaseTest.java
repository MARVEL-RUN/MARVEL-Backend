package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationItemRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.OrganizationLifecycleService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationTransactionService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationPaymentGuard;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationSettlementService;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantOrderAccess;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundExecutor;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundResultReader;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.RefundExecutionLock;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.RefundExecutionTransactionService;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossCancelAttempt;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossCancelOutcome;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossPaymentCancelClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.VerifiedTossCancellation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 실제 MySQL에서 상품 하나 취소(부분 취소)를 끝까지 본다. Toss 승인·환불은 대역이다.
 *
 * 종목 A·B를 단품 상품(40,000원, 기념품 티셔츠)으로 바꾸고 A(S)·B(M)·A(S) 세 줄을 산 뒤 결제한다.
 * 줄을 하나씩 취소하며 예약 상세·정원 카운터·계약금액·환불 원장·대표값이 맞는지,
 * 마지막 줄은 주문 전체 취소로 넘어가는지 확인한다.
 */
@Import({
        OrganizationLifecycleService.class, RegistrationCancellationCommandService.class,
        RegistrationCancellationTransactionService.class, ParticipantOrderAccess.class,
        RegistrationModificationPaymentGuard.class, RegistrationModificationSettlementService.class,
        ReservationRemovalService.class, RefundExecutionLock.class, RefundExecutionTransactionService.class,
        ModificationRefundExecutor.class, ModificationRefundResultReader.class})
class RegistrationItemCancellationDatabaseTest extends CapacityMvpTestSupport {

    @Autowired private RegistrationCancellationCommandService commands;
    @MockitoBean private TossPaymentCancelClient cancelClient;

    private String registrationId;
    private String ordererKey;
    private String paymentId;
    private List<String> items;

    @BeforeEach
    void paidThreeLineOrder() {
        tx.executeWithoutResult(status -> {
            jdbc.update("update event_category set product_type = 'SINGLE' where id in (?, ?)", categoryA, categoryB);
            jdbc.update("update event_registration_policy set guardian_consent_required = 0 where event_id = ?", eventId);
        });
        RegistrationCreateResponse created = registrations.register(eventId, request(List.of(
                line(categoryA, "S", "받는사람1"), line(categoryB, "M", "받는사람2"), line(categoryA, "S", "받는사람3"))));
        registrationId = created.registrationId();
        paymentId = created.paymentId();
        mockApprovalSuccess();
        payments.confirm(confirmRequest(paymentId));
        ordererKey = queryStringValue("select orderer_key from registration where id = ?", registrationId);
        items = jdbc.queryForList("select id from registration_item where registration_id = ? order by sort_order",
                String.class, registrationId);
        mockCancelSuccess();
    }

    @Test
    void 줄을_하나씩_취소하면_그_줄만큼_정원과_금액이_빠지고_마지막_줄은_주문_전체_취소다() {
        assertThat(decimal("select contract_amount from registration where id = ?")).isEqualByComparingTo("120000");
        assertCapacityCounts(total, 0, 3);
        assertCapacityCounts(categoryACapacity, 0, 2);
        assertCapacityCounts(categoryBCapacity, 0, 1);
        assertCapacityCounts(shirtS, 0, 2);
        assertCapacityCounts(shirtM, 0, 1);

        // 2번 줄(B, M) 취소: 그 줄 몫만 확정 수량에서 빠지고 40,000원을 가격 조정으로 환불한다.
        RegistrationModificationSettlementResult second = commands.cancelOwnedItem(registrationId, items.get(1), ordererKey);
        assertThat(second.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.amount()).isEqualByComparingTo("40000");
            assertThat(refund.status()).isEqualTo(PaymentCancelStatus.DONE);
        });
        assertThat(queryStringValue("select purpose from payment_cancel where id = ?", second.refunds().getFirst().paymentCancelId()))
                .isEqualTo("PRICE_ADJUSTMENT");
        assertThat(queryIntegerValue("select count(*) from registration_item where id = ? and canceled_at is not null", items.get(1)))
                .isEqualTo(1);
        assertThat(decimal("select contract_amount from registration where id = ?")).isEqualByComparingTo("80000");
        assertThat(decimal("select paid_amount from registration where id = ?")).isEqualByComparingTo("80000");
        assertThat(queryStringValue("select status from registration where id = ?", registrationId)).isEqualTo("CONFIRMED");
        // 120,000원 계산 기록은 남기되 지금 계약금액(80,000원)의 근거로 가리키지 않는다.
        assertThat(queryStringValue("select current_price_calculation_id from registration where id = ?", registrationId)).isNull();
        assertThat(queryIntegerValue("select count(*) from registration_price_calculation where registration_id = ?", registrationId))
                .isEqualTo(1);
        assertThat(reserved()).isEqualTo(Map.of(total, 2, categoryACapacity, 2, shirtS, 2));
        assertCapacityCounts(total, 0, 2);
        assertCapacityCounts(categoryBCapacity, 0, 0);
        assertCapacityCounts(shirtM, 0, 0);
        assertThat(queryStringValue("select status from reservation where registration_id = ?", registrationId)).isEqualTo("CONSUMED");

        // 이미 취소한 줄은 다시 취소하지 않는다.
        expectError(ErrorCode.REGISTRATION_ITEM_ALREADY_CANCELED,
                () -> commands.cancelOwnedItem(registrationId, items.get(1), ordererKey));

        // 1번 줄(대표 줄) 취소: 신청 종목·기념품은 남은 첫 줄(3번 줄) 값이 된다.
        commands.cancelOwnedItem(registrationId, items.get(0), ordererKey);
        assertThat(decimal("select contract_amount from registration where id = ?")).isEqualByComparingTo("40000");
        assertThat(queryStringValue("select event_category_id from registration where id = ?", registrationId)).isEqualTo(categoryA);
        assertThat(reserved()).isEqualTo(Map.of(total, 1, categoryACapacity, 1, shirtS, 1));

        // 남은 마지막 줄은 주문 전체 취소로 넘어가 남은 납부액 전부를 환불한다. 줄에는 취소 표시를 하지 않는다.
        RegistrationModificationSettlementResult last = commands.cancelOwnedItem(registrationId, items.get(2), ordererKey);
        assertThat(last.refunds()).hasSize(3);
        assertThat(queryIntegerValue("select is_del from registration where id = ?", registrationId)).isEqualTo(1);
        assertThat(queryStringValue("select status from registration where id = ?", registrationId)).isEqualTo("CANCELED");
        assertThat(decimal("select paid_amount from registration where id = ?")).isZero();
        assertThat(queryStringValue("select status from reservation where registration_id = ?", registrationId)).isEqualTo("RELEASED");
        assertThat(queryIntegerValue("select count(*) from registration_item where id = ? and canceled_at is null", items.get(2)))
                .isEqualTo(1);
        assertCapacityCounts(total, 0, 0);
        assertCapacityCounts(categoryACapacity, 0, 0);
        assertCapacityCounts(shirtS, 0, 0);
        // 원결제 한 건에서 40,000 + 40,000 + 40,000을 나눠 돌려줬다.
        assertThat(jdbc.queryForObject("select sum(cancel_amount) from payment_cancel where payment_id = ? and status = 'DONE'",
                BigDecimal.class, paymentId)).isEqualByComparingTo("120000");
        verify(cancelClient, times(3)).cancel(any());
    }

    @Test
    void 남의_주문_줄과_다른_주문의_줄은_건드리지_않는다() {
        Map<String, Integer> before = reserved();

        expectError(ErrorCode.REGISTRATION_ACCESS_DENIED,
                () -> commands.cancelOwnedItem(registrationId, items.get(1), "other-orderer"));
        expectError(ErrorCode.REGISTRATION_ITEM_NOT_FOUND,
                () -> commands.cancelOwnedItem(registrationId, UUID.randomUUID().toString(), ordererKey));

        assertThat(reserved()).isEqualTo(before);
        assertThat(decimal("select contract_amount from registration where id = ?")).isEqualByComparingTo("120000");
        assertThat(queryIntegerValue("select count(*) from registration_item where registration_id = ? and canceled_at is not null",
                registrationId)).isZero();
        verifyNoInteractions(cancelClient);
    }

    /** 예약 상세(정원 행 id → 수량). */
    private Map<String, Integer> reserved() {
        Map<String, Integer> result = new HashMap<>();
        jdbc.query("""
                select ri.capacity_id, ri.quantity from reservation_item ri join reservation r on r.id = ri.reservation_id
                where r.registration_id = ?
                """, rs -> { result.put(rs.getString(1), rs.getInt(2)); }, registrationId);
        return result;
    }

    private BigDecimal decimal(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class, registrationId);
    }

    private RegistrationItemRequest line(String categoryId, String size, String recipient) {
        return new RegistrationItemRequest(categoryId, List.of(new SouvenirJson(souvenirId, size)), List.of(
                new RegistrationEntryRequest(categoryId, recipient, "010-1111-2222", "1995-03-04", GenderClass.F)));
    }

    private static RegistrationCreateRequest request(List<RegistrationItemRequest> items) {
        return new RegistrationCreateRequest(null, null, "Test1234!", "주문" + UUID.randomUUID().toString().substring(0, 8),
                "010-9999-0000", "1980-03-02", GenderClass.F, "테스트 주소", "상세", false, null, null, null, true,
                false, false, null, null, true, null, null, items);
    }

    /** 실제 PG 호출 없이 환불액·잔액·거래키의 성공 증거를 만든다(RegistrationCancellationDatabaseTest와 같다). */
    private void mockCancelSuccess() {
        when(cancelClient.cancel(any())).thenAnswer(invocation -> {
            TossCancelAttempt attempt = invocation.getArgument(0);
            BigDecimal remaining = attempt.originalAmount().subtract(attempt.cancelAmount());
            for (BigDecimal amount : attempt.completedCancels().values()) { remaining = remaining.subtract(amount); }
            return TossCancelOutcome.verified(new VerifiedTossCancellation("cancel-" + attempt.paymentCancelId(),
                    attempt.cancelAmount(), remaining, OffsetDateTime.parse("2026-09-20T12:00:00+09:00"),
                    remaining.signum() == 0 ? "CANCELED" : "PARTIAL_CANCELED"));
        });
    }
}
