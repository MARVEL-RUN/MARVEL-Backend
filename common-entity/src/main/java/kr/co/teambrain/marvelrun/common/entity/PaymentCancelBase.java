package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class PaymentCancelBase<P extends PaymentBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    protected String id;


    /*
     * 취소 대상 Payment.
     *
     * 하나의 Payment는 여러 번 부분취소될 수 있으므로
     * Payment : PaymentCancel = 1 : N 관계이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_id",
            nullable = false
    )
    protected P payment;


    /*
     * 현재 환불 가능한 금액 전체를 취소하는지,
     * 일부만 취소하는지를 나타낸다.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "cancel_type",
            nullable = false,
            length = 20
    )
    protected PaymentCancelType cancelType;


    /*
     * MarvelRun 업무상 환불 목적.
     *
     * 참가 자체 취소인지 가격 변경에 따른 차액환불인지 등을 구분한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "purpose",
            nullable = false,
            length = 40
    )
    protected PaymentCancelPurpose purpose;


    /*
     * 실제 취소하려는 금액.
     *
     * FULL 취소더라도 내부 DB에는 요청 시점의
     * 실제 취소 예정 금액을 명시적으로 저장한다.
     */
    @Column(
            name = "cancel_amount",
            nullable = false,
            precision = 12,
            scale = 2
    )
    protected BigDecimal cancelAmount;


    /*
     * Toss cancelReason으로 전송하는 취소 사유.
     *
     * Toss 기준 최대 200자.
     */
    @Column(
            name = "cancel_reason",
            nullable = false,
            length = 200
    )
    protected String cancelReason;


    /*
     * Toss 취소 API 요청의 멱등키.
     *
     * timeout 등으로 응답이 불명확할 때 동일 취소를
     * 안전하게 재요청하기 위해 반드시 동일 값을 재사용한다.
     *
     */
    @Column(
            name = "idempotency_key",
            nullable = false,
            unique = true,
            length = 300
    )
    protected String idempotencyKey;


    /*
     * Toss가 성공한 각 취소 거래에 발급하는 고유 거래키.
     *
     * PROCESSING 단계에서는 null이며
     * 취소 성공 이후 저장한다.
     *
     * 이는 idempotencyKey로 한 개 요청에 대한 멱등성을 처리하려는 것과 별개로
     * 토스에서 여러번의 취소 시도는 각각 별개의 거래이기 때문. 성공한 취소 거래에만 기입된다.
     */
    @Column(
            name = "transaction_key",
            unique = true,
            length = 64
    )
    protected String transactionKey;


    /*
     * 취소 완료 후 Toss 기준으로
     * 해당 Payment에서 추가 환불 가능한 잔액.
     *
     * 정합성 검사 및 운영 확인을 위한 Snapshot.
     */
    @Column(
            name = "refundable_amount_after_cancel",
            precision = 12,
            scale = 2
    )
    protected BigDecimal refundableAmountAfterCancel;


    /*
     * MarvelRun 내부 취소 처리 상태.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 30
    )
    protected PaymentCancelStatus status;



     /* Toss 취소 API 요청을 실제 시작한 시각.
     */
    @Column(name = "requested_at")
    protected LocalDateTime requestedAt;


    /*
     * Toss Cancel.canceledAt 값.
     *
     * 실제 PG 취소가 발생한 시각이다.
     */
    @Column(name = "canceled_at")
    protected LocalDateTime canceledAt;


    /*
     * 명확한 Toss API 실패 또는 장애 발생 시
     * 마지막 확인 오류코드를 빠르게 조회하기 위한 필드.
     *
     * 상세 처리 이력은 PaymentProcessLog에서 관리한다.
     */
    @Column(
            name = "error_code",
            length = 100
    )
    protected String errorCode;


    /*
     * 마지막 확인 오류 메시지.
     *
     * 상세 로그의 Source of Truth는 PaymentProcessLog이다.
     */
    @Column(
            name = "error_message",
            length = 500
    )
    protected String errorMessage;


    /*
     * 취소 상태를 reconciliation 등의 다른 작업과
     * 동시에 변경할 때 Lost Update를 감지한다.
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