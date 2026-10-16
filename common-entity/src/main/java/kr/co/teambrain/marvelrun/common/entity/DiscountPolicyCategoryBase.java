package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 선택 종목 정책의 대상 종목을 연결한다. 대회 전체 적용 정책에는 연결 행을 만들지 않는다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountPolicyCategoryBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    // 정책의 SELECTED 범위에만 사용하며 종목 소속 대회는 서비스가 검증한다.
    @Column(name = "policy_id", nullable = false, length = 40)
    protected String policyId;

    @Column(name = "event_category_id", nullable = false, length = 40)
    protected String eventCategoryId;
}
