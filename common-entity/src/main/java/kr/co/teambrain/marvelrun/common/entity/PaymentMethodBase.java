package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import kr.co.teambrain.marvelrun.common.inheritance_enum.PaymentType;

@Getter
@MappedSuperclass
public abstract class PaymentMethodBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length =40)
    protected String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    protected PaymentType paymentMethod;

}