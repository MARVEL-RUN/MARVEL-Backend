package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import kr.co.teambrain.marvelrun.common.inheritance_enum.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class PaymentBase<R extends RegistrationBase, P extends  PaymentMethodBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registrations_id", nullable = false)
    protected R registrations;

    // paymentMethod는 그 수가 매우 적으며, 추가될 일이 거의없음.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "payment_method_id", nullable = false)
    protected P paymentMethod;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    protected BigDecimal amount;

    @Column(name = "deposit_date", nullable = true)
    @CreationTimestamp
    protected LocalDateTime depositDate;

    @Column(name = "virtual_account_number", length = 14)
    protected String virtualAccountNumber;

    @Column(name="paymenter_name", nullable = false, length = 30)
    protected String paymenterName;

    @Column(name="status",nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    protected PaymentStatus status;

    @Column(name = "refund_date", nullable = true)
    protected LocalDateTime refundDate;

}