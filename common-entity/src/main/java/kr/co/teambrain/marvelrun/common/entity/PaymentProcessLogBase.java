package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@SuperBuilder
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PaymentProcessLogBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    private String id;


    /*
     * ================================
     * 논리적 Domain 참조
     * ================================
     *
     * 장애/삭제 상황에서도 로그가 독립적으로 남아야 하므로
     * 실제 JPA 연관관계 및 DB FK를 사용하지 않는다.
     */

    @Column(
            name = "registration_id",
            length = 40
    )
    private String registrationId;

    @Column(
            name = "payment_id",
            length = 40
    )
    private String paymentId;

    @Column(
            name = "payment_cancel_id",
            length = 40
    )
    private String paymentCancelId;


    /*
     * ================================
     * Toss / 결제 식별값 Snapshot
     * ================================
     */

    @Column(
            name = "order_id",
            length = 64
    )
    private String orderId;

    @Column(
            name = "payment_key",
            length = 200
    )
    private String paymentKey;

    @Column(
            name = "transaction_key",
            length = 64
    )
    private String transactionKey;

    @Column(
            name = "idempotency_key",
            length = 300
    )
    private String idempotencyKey;


    /*
     * 동일 요청 흐름을 묶기 위한 MarvelRun 내부 Trace ID.
     *
     * Controller 진입부터 Toss 호출, DB 결과 반영까지
     * 하나의 요청 흐름을 동일 correlationId로 기록할 수 있다.
     */
    @Column(
            name = "correlation_id",
            nullable = false,
            length = 64
    )
    private String correlationId;


    /*
     * ================================
     * Process
     * ================================
     */

    @Enumerated(EnumType.STRING)
    @Column(
            name = "process_type",
            nullable = false,
            length = 50
    )
    private PaymentProcessType processType;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "source",
            nullable = false,
            length = 30
    )
    private PaymentProcessSource source;


    /*
     * ================================
     * 결과 정보
     * ================================
     */

    /*
     * Toss HTTP 응답 상태.
     *
     * HTTP 요청 자체가 이루어지지 않았거나
     * 아직 응답을 받지 않은 로그라면 null.
     */
    @Column(name = "http_status")
    private Integer httpStatus;


    /*
     * Toss API 오류코드.
     *
     * 성공 또는 외부 API 호출 이전 로그라면 null.
     */
    @Column(
            name = "error_code",
            length = 100
    )
    private String errorCode;


    /*
     * 운영 확인을 위한 오류 메시지 요약.
     *
     * 개인정보, Secret Key, 카드정보 등을 저장하면 안 된다.
     */
    @Column(
            name = "error_message",
            length = 500
    )
    private String errorMessage;


    /*
     * 추가적인 비민감 Structured Metadata.
     *
     * 예:
     * retryCount
     * previousStatus
     * nextStatus
     *
     * Toss 원본 Request/Response 전체를 그대로 넣는 용도가 아니다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "JSON"
    )
    private Map<String, Object> metadata;


    /*
     * append-only 로그이므로 수정시각은 두지 않는다.
     */
    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;
}