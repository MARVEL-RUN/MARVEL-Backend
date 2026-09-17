package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.BannerType;

@Getter
@MappedSuperclass
public abstract class EventBannerBase<E extends EventBase> {

    @Id
    @Column(name = "image_url", nullable = false)
    protected String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @Column(name = "url", nullable = true, length = 255)
    protected String url;

    @Column(name = "banner_type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    protected BannerType bannerType;

    @Column(name = "provider_name", nullable = false, length = 20)
    protected String providerName;

    @Column(name = "is_static")
    protected boolean isStatic;

    @Column(name = "badge", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 1")
    protected boolean badge = true;
}