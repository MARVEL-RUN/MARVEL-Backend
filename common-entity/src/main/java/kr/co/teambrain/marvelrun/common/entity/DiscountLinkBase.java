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
/** 할인 구성의 DiscountLink 저장 필드를 ID 참조 방식으로 공유한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountLinkBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    @Column(name = "policy_id", nullable = false, length = 40)
    protected String policyId;
    @Column(nullable = false, length = 64, unique = true)
    protected String code;
    @Column(name = "reference_filter_id", nullable = false, length = 40)
    protected String referenceFilterId;
    @Column(name = "acquisition_start_at", nullable = false)
    protected LocalDateTime acquisitionStartAt;
    @Column(name = "acquisition_end_at", nullable = false)
    protected LocalDateTime acquisitionEndAt;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;
}