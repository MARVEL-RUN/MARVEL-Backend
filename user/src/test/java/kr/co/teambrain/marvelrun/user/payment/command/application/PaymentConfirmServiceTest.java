package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.client.TossPaymentClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@Tag("payment-db")
@SpringBootTest
class PaymentConfirmServiceTest {

    @Autowired
    private PaymentConfirmService paymentConfirmService;

    @Autowired
    private PaymentCommandRepository paymentCommandRepository;

    @Autowired
    private RegistrationCommandRepository registrationCommandRepository;


    /*
     * 실제 Toss HTTP 호출만 대체한다.
     *
     * PaymentConfirmService /
     * PaymentConfirmTransactionService /
     * Repository /
     * 실제 로컬 DB Transaction은 그대로 사용한다.
     */
    @MockBean
    private TossPaymentClient tossPaymentClient;


    @Test
    void 최초_결제를_정상적으로_confirm한다() {

        /*
         * 현재 DB에 존재하는 READY Payment.
         */
        String orderId =
                "MR26_f0c3113ca4344beb835dd4eb50f8b475";

        Payment payment =
                paymentCommandRepository
                        .findByOrderId(orderId)
                        .orElseThrow();


        assertThat(payment.getProcessStatus())
                .isEqualTo(
                        PaymentProcessStatus.READY
                );


        String testPaymentKey =
                "test_payment_key_001";

        String testTransactionKey =
                "test_transaction_key_001";


        /*
         * Toss가 정상적으로 승인했다고 가정한 가짜 응답.
         */
        TossPaymentConfirmResponse tossResponse =
                new TossPaymentConfirmResponse(
                        testPaymentKey,
                        payment.getOrderId(),
                        payment.getOrderName(),
                        "DONE",
                        "카드",
                        payment.getAmount()
                                .longValueExact(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        testTransactionKey,
                        null,
                        new TossPaymentConfirmResponse.Receipt(
                                "https://test.receipt"
                        )
                );


        /*
         * 실제 TossPaymentClient.confirm()을 호출하지 않고
         * 위 정상 응답을 반환하게 한다.
         */
        given(
                tossPaymentClient.confirm(
                        any(
                                TossPaymentConfirmRequest.class
                        ),
                        eq(
                                payment.getConfirmIdempotencyKey()
                        )
                )
        ).willReturn(
                tossResponse
        );


        /*
         * 실제 Front successUrl에서 넘어올 값이라고 가정.
         */
        PaymentConfirmRequest request =
                new PaymentConfirmRequest(
                        testPaymentKey,
                        payment.getOrderId(),
                        payment.getAmount()
                                .longValueExact()
                );


        /*
         * 실제 Application Service 실행.
         *
         * beginConfirm()
         *      ↓
         * Mock Toss
         *      ↓
         * completeConfirm()
         */
        PaymentConfirmResponse response =
                paymentConfirmService.confirm(
                        request
                );


        /*
         * API 결과 검증
         */
        assertThat(
                response.processStatus()
        ).isEqualTo(
                PaymentProcessStatus.COMPLETED
        );

        assertThat(
                response.tossStatus()
        ).isEqualTo(
                TossPaymentStatus.DONE
        );

        assertThat(
                response.registrationStatus()
        ).isEqualTo(
                RegistrationStatus.CONFIRMED
        );

        /*
         * 응답의 amount는 이번 Payment의 결제금액이다.
         */
        assertThat(
                response.amount()
        ).isEqualByComparingTo(
                payment.getAmount()
        );

        /*
         * 누적 납부금액은 응답에 포함되지 않으므로
         * 승인 처리 후 Registration을 다시 조회하여 확인한다.
         *
         * 이 테스트는 미납 상태에서 참가비 전액을 최초 결제하는 경우를 전제로 한다.
         */
        assertThat(
                response.registrationId()
        ).isNotNull();

        Registration confirmedRegistration =
                registrationCommandRepository
                        .findById(
                                response.registrationId()
                        )
                        .orElseThrow();

        assertThat(
                confirmedRegistration.getPaidAmount()
        ).isEqualByComparingTo(
                payment.getAmount()
        );

        assertThat(
                confirmedRegistration.getStatus()
        ).isEqualTo(
                RegistrationStatus.CONFIRMED
        );


        /*
         * 실제 외부 Client 호출 지점까지 도달했는지도 확인.
         */
        verify(
                tossPaymentClient
        ).confirm(
                any(
                        TossPaymentConfirmRequest.class
                ),
                eq(
                        payment.getConfirmIdempotencyKey()
                )
        );
    }
}