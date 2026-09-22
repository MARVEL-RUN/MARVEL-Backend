package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentAllocationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "payment_allocation")
@NoArgsConstructor(access = PROTECTED)
public class PaymentAllocation extends PaymentAllocationBase<Payment, Registration> {
}