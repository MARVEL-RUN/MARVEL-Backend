package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@SuperBuilder
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EventCategorySouvenirBase<
        E extends EventCategoryBase,
        S extends SouvenirBase
        > {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    protected String id;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "event_category_id",
            nullable = false
    )
    protected E eventCategory;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "souvenir_id",
            nullable = false
    )
    protected S souvenir;


    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    protected LocalDateTime createdAt;
}