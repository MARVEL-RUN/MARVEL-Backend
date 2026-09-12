package kr.co.teambrain.marvelrun.user.event.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RegistrationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@SuperBuilder
@Table(name = "registration")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Registration extends RegistrationBase<
        User,
        Event,
        EventCategory,
        Organization,
        Souvenir
        > {

    public static Registration createForPaymentMvp(
            Event event,
            EventCategory eventCategory,
            RegistrationCreateRequest request,
            BigDecimal contractAmount,
            LocalDateTime expiresAt
    ) {

        return Registration.builder()

                /*
                 * MVP 임시 생략.
                 *
                 * 실운영에서는 반드시 GUEST/USER를
                 * reconcile한 후 non-null User를 연결해야 한다.
                 */
                .user(null)

                .event(event)
                .eventCategory(eventCategory)

                .password(request.password())

                .name(request.name())
                .phNum(request.phNum())
                .birth(request.birth())
                .gender(request.gender())

                .address(request.address())
                .addressDetail(request.addressDetail())

                .contractAmount(contractAmount)
                .paidAmount(BigDecimal.ZERO)

                .status(
                        RegistrationStatus.PAYMENT_PENDING
                )

                .expiresAt(expiresAt)

                .build();
    }

    /** 결제 성공금액 반영 */
    public void applySuccessfulPayment(
            BigDecimal amount
    ) {

        this.paidAmount =
                this.paidAmount.add(amount);

        int comparison =
                this.paidAmount.compareTo(
                        this.contractAmount
                );

        if (comparison == 0) {

            this.status =
                    RegistrationStatus.CONFIRMED;

            return;
        }

        if (comparison < 0) {

            this.status =
                    RegistrationStatus
                            .ADDITIONAL_PAYMENT_REQUIRED;

            return;
        }

        /*
         * paidAmount > contractAmount는
         * 향후 가격변경/환불 정책에서 별도 관리.
         *
         * Toss 승인 자체는 이미 발생했으므로 여기서
         * RuntimeException을 던져 Transaction을 rollback하면 안 된다.
         */
        this.status =
                RegistrationStatus.CONFIRMED;
    }
}
