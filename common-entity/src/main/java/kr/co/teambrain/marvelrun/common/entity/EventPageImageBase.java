package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventPageMediaType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.PageType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
public abstract class EventPageImageBase<E extends EventBase> {

    @Id
    @Column(name = "image_url", nullable = false, length = 2048)
    protected String imageUrl;  // PK로 사용, UUID 포함된 긴 URL 저장

    @Column(name = "order_number", nullable = false)
    protected long orderNumber;

    @Column(name = "media_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    protected EventPageMediaType mediaType;

    @Column(name = "page_type", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    protected PageType pageType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;
}