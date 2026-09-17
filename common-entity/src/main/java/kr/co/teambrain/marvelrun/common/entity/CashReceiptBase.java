package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt.CashReceiptIdentifierType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt.CashReceiptPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt.CashReceiptRequesterType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt.CashReceiptStatus;

import java.time.LocalDateTime;

@Getter
@SuperBuilder
@NoArgsConstructor // abstract Class지만 SuperBuilder를 사용해야하므로 별도로 작성. Builder 어노테이션이 자체적으로 생성자를 만들어버려 기본생성자 생성이 안되기때문
@MappedSuperclass
public abstract class CashReceiptBase<R extends RegistrationBase, O extends OrganizationBase, E extends EventBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    protected LocalDateTime createdAt;

    /**
     * 신청 시 기입 내역
     */
    @Column(name = "memo", nullable = true)
    protected String memo;

    /**
     * 발급 목적
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 15)
    protected CashReceiptPurpose purpose;

    /**
     * 요청자 타입
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "requester_type", nullable = false, length = 15)
    protected CashReceiptRequesterType requesterType;

    /**
     * 요청값 타입
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 15)
    protected CashReceiptIdentifierType identifierType;

    /**
     * 요청 기입 값
     */
    @Column(name = "cash_receipt_request_value", nullable = false, length = 40)
    protected String cashReceiptRequestValue;

    /**
     * 처리 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    protected CashReceiptStatus status;

    /**
     * 관리자 답변
     */
    @Column(name = "admin_answer", columnDefinition = "TEXT")
    protected String adminAnswer;

    /**
     * 처리 완료 시각
     */
    @Column(name = "completed_time")
    protected LocalDateTime completedTime;

    /**
     * 개인 신청 건 기준
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "registration_id",
            foreignKey = @ForeignKey(name = "fk_cash_receipt_registration")
    )
    protected R registration;

    /**
     * 단체 신청 건 기준
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "organization_id",
            foreignKey = @ForeignKey(name = "fk_cash_receipt_organization")
    )
    protected O organization;

    /**
     * 단체 신청 건 기준
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "event_id",
            foreignKey = @ForeignKey(name = "fk_cash_receipt_event")
    )
    protected E event;

    public boolean hasExactlyOneOwnerTarget() {
        return (registration == null) ^ (organization == null);
    }

    public boolean isRegistrationTarget() {
        return registration != null && organization == null;
    }

    public boolean isOrganizationTarget() {
        return registration == null && organization != null;
    }


    /**
     * JPA/Hibernate에 기반하여 persist, update 시 동작 검증 목적.
     * 현금영수증 테이블은 registration이나 organization 중 한 곳과 연관관계를 소유해야한다.
     */
    @AssertTrue(message = "registration 또는 organization 중 정확히 하나만 지정되어야 합니다.")
    public boolean isValidOwnerTarget() {
        return hasExactlyOneOwnerTarget();
    }

}
