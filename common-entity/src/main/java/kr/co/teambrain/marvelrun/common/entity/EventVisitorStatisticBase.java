package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDate;

@Getter
@MappedSuperclass
public abstract class EventVisitorStatisticBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    protected Long id;

    // EventBase의 id(String, length 40)와 매핑하기 위해 타입을 맞춥니다.
    @Column(name = "event_id", nullable = false, length = 40)
    protected String eventId;

    @Column(name = "visit_date", nullable = false)
    protected LocalDate visitDate;

    @Column(name = "daily_count", nullable = false)
    protected Long dailyCount;

    @Column(name = "cumulative_count", nullable = false)
    protected Long cumulativeCount;
}
