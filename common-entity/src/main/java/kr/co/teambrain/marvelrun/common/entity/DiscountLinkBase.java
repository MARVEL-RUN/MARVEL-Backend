package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/** 공개 코드와 할인 정책·참조필터를 연결하고 신규 자격 취득 기간을 저장한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountLinkBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    // 서로 다른 판매처 링크가 같은 할인 정책을 공유할 수 있다.
    @Column(name = "policy_id", nullable = false, length = 40)
    protected String policyId;

    // 요청의 공개 코드로 링크를 찾고 대회·기간·신청 유형을 검증한다.
    @Column(nullable = false, length = 64, unique = true)
    protected String code;

    // 링크 자격 취득 시 실제 종목 할인 여부와 별개로 부여할 필터다.
    @Column(name = "reference_filter_id", nullable = false, length = 40)
    protected String referenceFilterId;

    // 시작 포함·종료 제외의 신규 취득 기간이며 기존 자격의 적용 만료일이 아니다.
    @Column(name = "acquisition_start_at", nullable = false)
    protected LocalDateTime acquisitionStartAt;

    @Column(name = "acquisition_end_at", nullable = false)
    protected LocalDateTime acquisitionEndAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;
}
