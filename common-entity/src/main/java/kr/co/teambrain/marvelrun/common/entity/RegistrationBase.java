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

/** 신청의 공통 저장 필드와 검증된 그룹 선택값 반영을 제공한다. */
@Getter
@SuperBuilder
@NoArgsConstructor // abstract Class지만 SuperBuilder를 사용해야하므로 별도로 작성. Builder 어노테이션이 자체적으로 생성자를 만들어버려 기본생성자 생성이 안되기때문
@MappedSuperclass
public abstract class RegistrationBase<U extends UserBase, E extends EventBase, EC extends EventCategoryBase, O extends OrganizationBase, S extends SouvenirBase> {
    /** 종목 그룹 정의의 UUID이며 그룹 결정 서비스에서 검증한 값을 저장한다. */
    @Column(name = "start_group_id", nullable = false, length = 40)
    protected String startGroupId;

    /** 직접 선택하지 않아 후미에 배정했는지 나타낸다. */
    @Column(name = "start_group_unselected", nullable = false)
    protected boolean startGroupUnselected;

    /** 검증과 그룹 결정을 마친 두 값을 하나의 신청 변경으로 반영한다. */
    public void applyStartGroupSelection(String startGroupId, boolean startGroupUnselected) {
        // 그룹 소속과 필수값 검증은 그룹 결정 서비스에서 완료한다.
        this.startGroupId = startGroupId;
        this.startGroupUnselected = startGroupUnselected;
    }

    /** 외부 단말기에서 이미 결제한 신청의 관리자 등록 여부다. 요청 차단 정책은 별도 적용한다. */
    @Column(name = "external_payment", nullable = false)
    protected boolean externalPayment;

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

    // 보호자 정보의 참조 기준. 단체 신청은 ORG_LEADER를 사용하며
    // 소유신청 구성에 따라 수정 가능
    @Column(name = "guardian_base", nullable = true, length = 15)
    @Enumerated(EnumType.STRING)
    protected GuardianBase guardianBase; // nullable, 소유신청 한정 사용

    @Builder.Default
    @Column(name = "guardian_consent", nullable = false)
    protected boolean guardianConsent = false;

    @Column(name = "guardian_name", length = 50)
    protected String guardianName;

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
    protected boolean softDeleted = false;

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


    @Column(name = "active_unique_info", insertable = false, updatable = false)
    protected Boolean activeUniqueInfo;

    // 약관 관련 컬럼 추가

    @Column(name = "terms_essential_agreed", nullable = false)
    protected Boolean termsEssentialAgreed = false;

    @Column(name = "terms_marketing_agreed", nullable = false)
    protected Boolean termsMarketingAgreed = false;

    @Column(name = "terms_marketing_channel_agreed", nullable = false)
    protected Boolean termsMarketingChannelAgreed = false;

    @Column(name = "terms_agreed_at", nullable = false)
    protected LocalDateTime termsAgreedAt;

    @Column(name = "email", length = 255)
    protected String email;

//    @Column(name = "name_hmac", length = 64)
//    protected String nameHmac;

    /**
     * 검색 속도 최적화를 위한 연락처 단방향 암호화 필드 (블라인드 인덱싱)
     */
//    @Column(name = "ph_num_hmac", length = 64)
//    protected String phNumHmac;
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

    /** 취득한 링크 자격과 필터는 취소·소프트 삭제 후에도 보존한다. */
    @Column(name = "discount_link_id", length = 40)
    protected String discountLinkId;

    // 실제 종목 할인 여부와 별개로 취득한 링크의 신청 분류를 보존한다.
    @Column(name = "reference_filter_id", length = 40)
    protected String referenceFilterId;

    /** 신규 취득 또는 승인된 단체 승계 결과를 함께 기록한다. */
    public void assignDiscountQualification(String linkId, String filterId) {
        this.discountLinkId = linkId;
        this.referenceFilterId = filterId;
    }

    /** 현재 계약금액의 계산 근거이며 전체 취소 후에도 마지막 기록을 보존한다. */
    @Column(name = "current_price_calculation_id", length = 40)
    protected String currentPriceCalculationId;

    /** 동일 트랜잭션에서 저장된 이 신청 소유 계산 기록을 연결한다. */
    public void assignCurrentPriceCalculation(String calculationId) {
        this.currentPriceCalculationId = calculationId;
    }
}
