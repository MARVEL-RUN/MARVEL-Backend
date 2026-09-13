package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AddressBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@SuperBuilder
@NoArgsConstructor // abstract Class지만 SuperBuilder를 사용해야하므로 별도로 작성. Builder 어노테이션이 자체적으로 생성자를 만들어버려 기본생성자 생성이 안되기때문
@MappedSuperclass
public abstract class RegistrationBase<U extends UserBase, E extends EventBase, EC extends EventCategoryBase, O extends OrganizationBase, S extends SouvenirBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    protected U user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_category_id", nullable = false)
    protected EC eventCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "souvenir_json", columnDefinition = "JSON")
    protected List<SouvenirJson> souvenirJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    protected O organization;

    @Column(name = "organization_id", insertable = false, updatable = false)
    protected String readOnlyOrganizationId;

    /**
     * 소유신청 관련 필드
     */

    @Column(name = "owned_at")
    protected LocalDateTime ownedAt;

    @Builder.Default // 빌더 메소드로 사용하지 않는 경우 자동으로 기본값으로 설정.
    @Column(name = "is_owned", nullable = false)
    protected boolean is_owned = false; // null 값 못받게 boolean 원시형 사용

    // db 저장용량 아끼기 위해 소유 신청에서만 사용 권장
    // 소유 신청 확인 시 주소가 어디에 저장되어있는지 기준 확정하기 위한 값으로, 개인신청, 개별신청에서는 사용처가 없음.
    @Column(name = "address_base", nullable = true, length = 15)
    @Enumerated(EnumType.STRING)
    protected AddressBase addressBase;

    // 소유 신청인 경우에만 사용.
    @Column(name = "guardian_base", nullable = true, length = 15)
    @Enumerated(EnumType.STRING)
    protected GuardianBase guardianBase; // nullable, 소유신청 한정 사용

    /**
     * uniqueInfo 및 개인정보
     */

    @Column(name = "password", nullable = false, length = 127)
    protected String password;

    @Column(name = "name", nullable = false, length = 50)
    protected String name;

    @Column(name = "ph_num", nullable = false, length = 14)
    protected String phNum;

    @Column(name = "birth", nullable = false, length = 10)
    protected String birth;

    @Column(name = "gender", nullable = false, length = 2)
    @Enumerated(EnumType.STRING)
    protected GenderClass gender;

    @Column(name = "address", length = 300)
    protected String address;

    @Column(name = "address_detail")
    protected String addressDetail;

    @Column(name = "guardian_ph_num", length = 14)
    protected String guardianPhNum; // 보호자 연락처 (Nullable)

    @Column(name = "guardian_relationship", length = 50)
    protected String guardianRelationship; // 보호자 관계 (Nullable)

    /**
     * 신규 : 토스 페이먼츠 기준하 PG 구성
     */

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 40
    )
    protected RegistrationStatus status = RegistrationStatus.PAYMENT_PENDING; // 기본 값은 결제 대기

    /*
     * 현재 이 Registration의 계약상 최종 부담금액.
     *
     * EventCategory.price는 현재 신규 신청 가격이고,
     * contractAmount는 이 Registration에 실제 적용되는 계약금액이다.
     */
    @Column(
            name = "contract_amount",
            nullable = false,
            precision = 12,
            scale = 2
    )
    protected BigDecimal contractAmount;

    /*
     * 현재 실제 순결제금액의 summary.
     * 성공한 환불을 제외한 순 결제금액.
     * 조회 최적화용 summary. (contractAmount > paidAmount == 결제 덜했음, contractAmount < paidAmount == 부분환불 toss 처리 중 등등)
     *
     * 성공 Payment 합계
     * -
     * 성공 PaymentCancel 합계
     *
     * 금융 Source of Truth 자체는 Payment + PaymentCancel이다.
     */
    @Builder.Default
    @Column(
            name = "paid_amount",
            nullable = false,
            precision = 12,
            scale = 2
    )
    protected BigDecimal paidAmount  = BigDecimal.ZERO;

    /*
     * paidAmount / contractAmount / status의
     * 동시 수정 시 Lost Update 방지
     */
    @Version
    @Column(
            name = "version",
            nullable = false
    )
    protected Long version;

    /*
     * PAYMENT_PENDING 상태를 무기한 유지하지 않기 위한 만료시각.(대회 신청은 했으나 결제가 즉시 이루어지지 않거나 오류로 인해 취소된 경우)
     *
     * 구체적인 TTL 정책은 추후 신청 생성 로직에서 결정한다.
     */
    @Column(name = "expires_at")
    protected LocalDateTime expiresAt;


    /**
     * 관리자 및 개발 작업 필드
     *
     * */

    /**
     * 개발
     */
    @Column(name = "registration_date")
    @CreationTimestamp
    protected LocalDateTime registrationDate;

    @Column(name = "modified_at")
    @UpdateTimestamp
    protected LocalDateTime modifiedAt;

    @Column(name = "is_del") // true == 소프트딜리트
    protected boolean is_del = false;

    /**
     * 관리자
     */
    @Lob // 이 필드를 TEXT 타입(Large Object)으로 매핑
    @Column(name = "note", nullable = true)
    protected String note;

    @Lob // 이 필드를 TEXT 타입(Large Object)으로 매핑
    @Column(name = "memo", nullable = true)
    protected String memo;

    @Lob
    @Column(name = "detail_memo", nullable = true)
    protected String detailMemo;

    @Lob
    @Column(name = "failed_log", nullable = true)
    protected String failedLog;

    // 새로 추가되는 필드
    @Lob
    @Column(name = "success_log", nullable = true)
    protected String successLog;


    /**
     * 환불 관련 필드 (기존 수동 환불 필요 필드)
     *
     * */

    //    @Column(name = "paymenter_bank", length = 50)
//    protected String paymenterBank; // 환불 요청 계좌 은행 ex) 국민
//
//    @Column(name = "account_number", length = 255)
//    protected String accountNumber; // 환불 요청 계좌 번호
//
//    // 새로 추가되는 필드
//    @Column(name = "account_holder_name", length = 50)
//    protected String accountHolderName; // 환불 요청 예금주명
//
//    @Column(name = "refund_requested_at")
//    protected LocalDateTime refundRequestedAt; // 환불 요청 시각
}