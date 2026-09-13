package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;

import java.math.BigDecimal;

public record RegistrationCreateResponse(

        String registrationId,

        RegistrationStatus registrationStatus,


        // 내부 엔티티 pk
        String paymentId,

        /* toss에 기입할 내역들 */
        String orderId, // 본 서비스에서 생성한 결제행위에 대한 개별 고유 id

        String orderName, // 결제명칭

        BigDecimal paymentAmount // 할인 정책이 적용된 최종 구성

) {

    public static RegistrationCreateResponse from(
            Registration registration,
            Payment payment
    ) {

        return new RegistrationCreateResponse(
                registration.getId(),
                registration.getStatus(),
                payment.getId(),
                payment.getOrderId(),
                payment.getOrderName(),
                payment.getAmount()
        );
    }
}