package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import kr.co.teambrain.marvelrun.common.inheritance_enum.PaymentStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@MappedSuperclass // 🔥 핵심: 상속받는 엔티티에 컬럼 정보 제공
public abstract class EventNotificationHistoryBase/* <N extends NotificationBase> */{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    protected Long id;

    @Column(name = "event_id", nullable = false)
    protected String eventId;

    @Column(name = "title", nullable = false)
    protected String title;

    @Lob
    @Column(name = "body", nullable = false)
    protected String body;

    // NULL 허용 (전체 발송일 경우)
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = true)
    protected PaymentStatus paymentStatus;

    @Column(name = "recipient_count", nullable = false)
    protected int recipientCount;

//    @Builder.Default
//    @OneToMany(mappedBy = "eventNotificationHistory")
//    protected List<N> notifications = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    protected LocalDateTime sentAt;
}