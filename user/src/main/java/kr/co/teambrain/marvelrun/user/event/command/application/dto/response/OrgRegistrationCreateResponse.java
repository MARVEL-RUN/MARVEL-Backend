package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;

import java.math.BigDecimal;
import java.util.List;

public record OrgRegistrationCreateResponse(

        String organizationId,

        List<String> registrationIds,

        // 내부 엔티티 pk
        String paymentId,

        /* toss에 기입할 내역들 */
        String orderId, // 본 서비스에서 생성한 결제행위에 대한 개별 고유 id

        String orderName, // 결제명칭

        BigDecimal paymentAmount // 할인 정책이 적용된 최종 구성
) {

    public static OrgRegistrationCreateResponse from(
            Organization organization,
            List<Registration> registrations,
            Payment payment
    ) {

        List<String> registrationIds =
                registrations.stream()
                        .map(
                                Registration::getId
                        )
                        .toList();


        return new OrgRegistrationCreateResponse(
                organization.getId(),
                registrationIds,
                payment.getId(),
                payment.getOrderId(),
                payment.getOrderName(),
                payment.getAmount()
        );
    }
}