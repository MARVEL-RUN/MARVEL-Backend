package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
public abstract class NoticeBase<C extends NoticeCategoryBase, A extends AdminBase, E extends EventBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    /** 카테고리는 매우 적은 개수를 지니므로, EAGER로 처리. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    protected C category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", nullable = false)
    protected A admin;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "event_id", nullable = true)
    protected E event;

    @Column(name = "title", nullable = false, length = 50)
    protected String title;

    @Lob
    @Column(name = "content", nullable = false)
    protected String content;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    protected LocalDateTime createdAt;

    @Column(name = "view_count", nullable = false)
    protected Long viewCount = 0L;
}
