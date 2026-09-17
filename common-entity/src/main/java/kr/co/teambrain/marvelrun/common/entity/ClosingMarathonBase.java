package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ClosingEventType;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Setter
@MappedSuperclass
@NoArgsConstructor(access = PROTECTED)
@SuperBuilder(toBuilder = true)
public abstract class ClosingMarathonBase <E extends EventBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @Column(name = "type", nullable = false, length = 15)
    @Enumerated(EnumType.STRING)
    protected ClosingEventType type;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    protected E event;
}
