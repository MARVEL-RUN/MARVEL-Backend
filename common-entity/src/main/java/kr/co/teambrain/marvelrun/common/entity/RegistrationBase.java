package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;

import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AddressBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    protected O organization;

    @Column(name = "organization_id", insertable = false, updatable = false)
    protected String readOnlyOrganizationId;

    @Builder.Default // 빌더 메소드로 사용하지 않는 경우 자동으로 기본값으로 설정.
    @Column(name = "is_owned", nullable = false)
    protected boolean is_owned = false; // null 값 못받게 boolean 원시형 사용


    // db 저장용량 아끼기 위해 소유 신청에서만 사용 권장
    // 소유 신청 확인 시 주소가 어디에 저장되어있는지 기준 확정하기 위한 값으로, 개인신청, 개별신청에서는 사용처가 없음.
    @Column(name = "address_base", nullable = true, length = 15)
    @Enumerated(EnumType.STRING)
    protected AddressBase addressBase;

    @Column(name = "owned_at")
    protected LocalDateTime ownedAt;

    @Column(name = "deposit_flag", nullable = false)
    protected Boolean depositFlag = false;

    @Column(name = "shuttle_flag", nullable = false)
    protected Boolean shuttleFlag = false;

    @Column(name = "email", nullable = false, length = 320)
    protected String email;

    @Column(name = "password", nullable = false, length = 127)
    protected String password;

    @Column(name = "address", length = 300)
    protected String address;

    @Column(name = "address_detail")
    protected String addressDetail;

    @Column(name = "registration_date")
    @CreationTimestamp
    protected LocalDateTime registrationDate;

    @Column(name = "modified_at")
    @UpdateTimestamp
    protected LocalDateTime modifiedAt;

    @Column(name = "name", nullable = false, length = 50)
    protected String name;

    @Column(name = "ph_num", nullable = false, length = 14)
    protected String phNum;

    @Column(name = "birth", nullable = false, length = 10)
    protected String birth;

    @Column(name = "gender", nullable = false, length = 2)
    @Enumerated(EnumType.STRING)
    protected GenderClass gender;

    @Column(name = "is_del") // true == 소프트딜리트
    protected boolean is_del = false;

    @Lob // 이 필드를 TEXT 타입(Large Object)으로 매핑
    @Column(name = "note", nullable = true)
    protected String note;

    @Lob // 이 필드를 TEXT 타입(Large Object)으로 매핑
    @Column(name = "memo", nullable = true)
    protected String memo;

    @Lob
    @Column(name = "detail_memo", nullable = true)
    protected String detailMemo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "souvenir_json", columnDefinition = "JSON")
    protected List<SouvenirJson> souvenirJson;

    @Lob
    @Column(name = "failed_log", nullable = true)
    protected String failedLog;

    // 새로 추가되는 필드
    @Lob
    @Column(name = "success_log", nullable = true)
    protected String successLog;

    @Column(name = "paymenter_bank", length = 50)
    protected String paymenterBank; // 환불 요청 계좌 은행 ex) 국민

    @Column(name = "account_number", length = 255)
    protected String accountNumber; // 환불 요청 계좌 번호

    // 새로 추가되는 필드
    @Column(name = "account_holder_name", length = 50)
    protected String accountHolderName; // 환불 요청 예금주명

    @Column(name = "refund_requested_at")
    protected LocalDateTime refundRequestedAt; // 환불 요청 시각

    @Column(name = "guardian_ph_num", length = 14)
    protected String guardianPhNum; // 보호자 연락처 (Nullable)

    @Column(name = "guardian_relationship", length = 50)
    protected String guardianRelationship; // 보호자 관계 (Nullable)

    // 소유 신청인 경우에만 사용.
    @Column(name = "guardian_base", nullable = true, length = 15)
    @Enumerated(EnumType.STRING)
    protected GuardianBase guardianBase; // nullable, 소유신청 한정 사용
}