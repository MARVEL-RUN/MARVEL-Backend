package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/** 종목의 대회 소속·거리·금액 및 표시 정보를 공통 매핑한다. */
@Getter
@SuperBuilder
@MappedSuperclass
@NoArgsConstructor // abstract Class지만 SuperBuilder를 사용해야하므로 별도로 작성. Builder 어노테이션이 자체적으로 생성자를 만들어버려 기본생성자 생성이 안되기때문
public abstract class EventCategoryBase<E extends EventBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "sort_order", nullable = false)
    protected Long order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    protected BigDecimal amount;

    @Column(name = "name", nullable = false, length = 50)
    protected String name;

    /** 종목명 해석 없이 그룹 안내 기준을 준비하는 실제 거리이며 단위는 미터다. */
    @Column(name = "distance_meters", nullable = false)
    protected Integer distanceMeters;

    @Column(name = "is_active", nullable = false)
    protected Boolean isActive;

}
