package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 종목별 선택 그룹과 사전 작성한 안내 기준을 두 서버에서 동일하게 매핑한다. */
@Getter
@SuperBuilder
@MappedSuperclass
@NoArgsConstructor
public abstract class EventCategoryStartGroupBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "event_category_id", nullable = false, length = 40)
    protected String eventCategoryId;

    @Column(name = "code", nullable = false, length = 20)
    protected String code;

    @Column(name = "description", nullable = false, length = 255)
    protected String description;

    @Column(name = "sort_order", nullable = false)
    protected Integer sortOrder;

    @Column(name = "min_pace_seconds_per_km", nullable = false)
    protected Integer minPaceSecondsPerKm;

    @Column(name = "max_pace_seconds_per_km")
    protected Integer maxPaceSecondsPerKm;

    @Column(name = "min_expected_finish_seconds", nullable = false)
    protected Integer minExpectedFinishSeconds;

    @Column(name = "max_expected_finish_seconds")
    protected Integer maxExpectedFinishSeconds;

    @Column(name = "is_active", nullable = false)
    protected boolean active;
}
