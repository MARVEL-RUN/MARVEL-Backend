package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import kr.co.teambrain.marvelrun.common.inheritance_enum.PaymentStatus;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;


/** TossPG 기준 MarvelRun의 Payment는 1회의 결제 이력을 의미한다.(환불은 별도의 PaymentCancel로 관리) */
@Getter
@NoArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class PaymentBase<R extends RegistrationBase, O extends OrganizationBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    /*
     * 하나의 Registration에서
     * 최초결제 / 재결제 / 추가결제 등이 여러 번 발생할 수 있다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "registration_id",
            nullable = false
    )
    protected R registration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "organization_id"
    )
    protected O organization;

    /*
     * 이 한 Payment에서 실제로 받으려는 금액.
     */
    @Column(
            name = "amount",
            nullable = false,
            precision = 12,
            scale = 2
    )
    protected BigDecimal amount;


    /**
     * toss PG를 위한 추가 내역
     *
     * */

    /*
     * 가맹점(본 서비스)에서 생성하는 Toss 개별 주문 식별자.
     *
     * 각 Payment마다 새 값을 사용한다.
     */
    @Column(
            name = "order_id",
            nullable = false,
            unique = true,
            length = 64
    )
    protected String orderId;

    /*
     * Toss가 발급하는 개별 결제 고유 식별자.
     *
     * 결제 인증 이전에는 존재하지 않을 수 있다.
     */
    @Column(
            name = "payment_key",
            unique = true,
            length = 200
    )
    protected String paymentKey;

    /*
     * 결제 시 Toss에 전달한 주문명 Snapshot.
     *
     * Event/EventCategory 이름이 나중에 수정되더라도
     * 실제 PG에 어떤 주문명으로 요청했는지 보존한다.
     */
    @Column(
            name = "order_name",
            nullable = false,
            length = 100
    )
    protected String orderName;

    /*
     * 왜 새 Payment를 생성했는가.
     *
     * 최초 참가비 / 추가 결제 등을 구분.
     *
     * 환불/부분환불은 여기에 포함하지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "purpose",
            nullable = false,
            length = 30
    )
    protected PaymentPurpose purpose;

    /*
     * 실제 결제수단.
     *
     * Toss 응답을 기준으로 최종 확정.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "payment_method",
            length = 20
    )
    protected PaymentMethod paymentMethod;

    /*
     * 간편결제인 경우 어떤 간편결제사인지.
     *
     * ex)
     * TOSSPAY
     * NAVERPAY
     * KAKAOPAY
     *
     * 일반 카드결제라면 null.
     */
    @Column(
            name = "easy_pay_provider",
            length = 30
    )
    protected String easyPayProvider;


    /*
     * MarvelRun 서버 내부의 Payment 처리 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "process_status",
            nullable = false,
            length = 30
    )
    protected PaymentProcessStatus processStatus;


    /*
     * 마지막으로 Toss에서 확인한 실제 Payment.status
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "toss_status",
            length = 30
    )
    protected TossPaymentStatus tossStatus;


    /*
     * confirm POST 요청의 멱등키.
     *
     * 네트워크 timeout 등의 상황에서
     * 같은 결제승인을 안전하게 재시도하기 위해 사용.
     */
    @Column(
            name = "confirm_idempotency_key",
            nullable = false,
            unique = true,
            length = 300
    )
    protected String confirmIdempotencyKey;


    /*
     * 최초 승인 거래 transactionKey.
     *
     * 이후 취소 transactionKey들은 PaymentCancel에서 관리한다.
     */
    @Column(
            name = "approval_transaction_key",
            unique = true,
            length = 64
    )
    protected String approvalTransactionKey;


    /*
     * Toss Payment.requestedAt
     */
    @Column(name = "toss_requested_at")
    protected LocalDateTime tossRequestedAt;


    /*
     * Toss Payment.approvedAt
     */
    @Column(name = "approved_at")
    protected LocalDateTime approvedAt;


    /*
     * Toss 영수증 / 카드 매출전표 URL.
     */
    @Column(
            name = "receipt_url",
            length = 1000
    )
    protected String receiptUrl;


    /*
     * 동시 상태 변경 시 Lost Update 방지.
     */
    @Version
    @Column(
            name = "version",
            nullable = false
    )
    protected Long version;


    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    protected LocalDateTime createdAt;


    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false
    )
    protected LocalDateTime updatedAt;

}