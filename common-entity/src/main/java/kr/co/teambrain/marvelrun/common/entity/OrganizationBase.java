package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** 단체 계정과 대회 소속을 공유하고 취소 후에도 할인·금융 연결을 보존한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class OrganizationBase<E extends EventBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "login_id", nullable = false, length = 30)
    protected String loginId;

    @Column(name = "password", nullable = false, length = 127)
    protected String password;

    @Column(name = "group_name", nullable = false, length = 30)
    protected String groupName;

    @Column(name = "leader_name", nullable = false, length = 30)
    protected String leaderName;

    @Column(name = "leader_birth", nullable = false, length = 30)
    protected String leaderBirth;

    @Column(name = "leader_ph_num", nullable = false, length = 30)
    protected String leaderPhNum;

    // 단체 참가자에게 적용할 법정대리인 동의 여부.
    // 법정대리인 동의는 필수가 아니다.
    @Builder.Default
    @Column(name = "guardian_consent", nullable = false)
    protected boolean guardianConsent = false;

    @Column(name = "email", nullable = true, length = 30)
    protected String email;

    // 각 단체는 오직 한 개의 이벤트만 참여 가능하다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Column(name = "address", length = 300)
    protected String address;

    @Column(name = "address_detail")
    protected String addressDetail;

    /** 취득한 링크 자격과 필터는 취소·소프트 삭제 후에도 보존한다. */
    @Column(name = "discount_link_id", length = 40)
    protected String discountLinkId;

    @Column(name = "reference_filter_id", length = 40)
    protected String referenceFilterId;

    /** 신규 취득 또는 승인된 단체 승계 결과를 함께 기록한다. */
    public void assignDiscountQualification(String linkId, String filterId) {
        this.discountLinkId = linkId;
        this.referenceFilterId = filterId;
    }

    // 삭제 표시는 계정 재사용 판정에 사용하며 기존 행과 이력은 유지한다.
    @Builder.Default
    @Column(name = "is_del", nullable = false)
    protected boolean softDeleted = false;

    @Column(name = "active_unique_info", columnDefinition = "tinyint GENERATED ALWAYS AS (CASE WHEN is_del = 0 THEN 1 ELSE NULL END) VIRTUAL",
            insertable = false, updatable = false)
    protected Byte activeUniqueInfo;

    /** 전원 취소·환불 완료가 검증된 단체를 비활성화하고 금융 연결은 보존한다. */
    public void markOrganizationSoftDeleted() {
        this.softDeleted = true;
    }
}
