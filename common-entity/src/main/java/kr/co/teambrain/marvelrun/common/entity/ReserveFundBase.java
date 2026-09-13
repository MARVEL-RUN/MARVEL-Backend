package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Getter
@MappedSuperclass
public abstract class ReserveFundBase<U extends UserBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    protected U user;

    @Column(
            name = "fund",
            nullable = false
    )
    protected Integer fund;

    @Column(
            name = "limit_date"
    )
    protected Instant limitDate;
}
