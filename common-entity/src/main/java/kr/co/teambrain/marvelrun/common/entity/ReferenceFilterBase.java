package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
/** 할인 구성의 ReferenceFilter 저장 필드를 ID 참조 방식으로 공유한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class ReferenceFilterBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    @Column(name = "event_id", nullable = false, length = 40)
    protected String eventId;
    @Column(nullable = false, length = 50)
    protected String code;
    @Column(name = "display_name", nullable = false, length = 100)
    protected String displayName;
    @Builder.Default
    @Column(nullable = false)
    protected int priority = 100;
    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    protected int sortOrder = 100;
}