package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static lombok.AccessLevel.PROTECTED;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = PROTECTED)
public abstract class SouvenirBase<E extends EventBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "sort_order", nullable = false)
    protected Long order;

    @Column(name = "name", nullable = false, length = 30)
    protected String name;

    @Column(name = "sizes", nullable = true, length = 100)
    protected String sizes; // |을 구분자로 하는 사이즈 집합

    @Column(name = "is_active", nullable = false)
    protected Boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;
}