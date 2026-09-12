package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentCancelBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@SuperBuilder
@Table(name = "payment_cancel")
@NoArgsConstructor(access = PROTECTED)
public class PaymentCancel
        extends PaymentCancelBase<Payment> {
}