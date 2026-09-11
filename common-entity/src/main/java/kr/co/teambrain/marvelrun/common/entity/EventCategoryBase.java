package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Getter
@MappedSuperclass
public abstract class EventCategoryBase<E extends EventBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    protected BigDecimal amount;

    @Column(name = "name", nullable = false, length = 50)
    protected String name;

    @Column(name = "is_active", nullable = false)
    protected Boolean isActive;

}