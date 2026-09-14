package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EventTermsBase<E extends EventBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id; // 약관 ID도 UUID(40)으로 변경

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @Lob
    @Column(name = "content", nullable = false)
    protected String content;

    @Column(name = "required", nullable = false)
    protected Boolean required; // 필수 여부 (true인 경우 필수)

    @Column(name = "terms_label", nullable = false)
    protected String termsLabel;

    @Column(name = "sort_order", nullable = false)
    protected Integer sortOrder;

}