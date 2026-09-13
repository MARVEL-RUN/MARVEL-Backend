package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventVisibleStatus;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
public abstract class EventBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "name_kr", nullable = false, length = 50)
    protected String nameKr;

    @Column(name = "name_eng", length = 50)
    protected String nameEng;

    @Column(name = "start_date", nullable = false)
    protected LocalDateTime startDate;

    @Column(name = "region", nullable = false, length = 20)
    protected String region;

    @Column(name = "host", nullable = false, length = 20)
    protected String host;

    @Column(name = "organizer", nullable = false, length = 20)
    protected String organizer;

    @Column(name = "regist_maximum", nullable = false)
    protected Integer registMaximum;

    @Column(name = "events_page_url", nullable = true)
    protected String eventsPageUrl;

    @Column(name = "event_status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    protected EventStatus eventStatus = EventStatus.OPEN; // event에 대한 '신청 가능 여부'에만 영향

    @Column(name = "visible_status", nullable = false)
    @Enumerated(EnumType.STRING)
    protected EventVisibleStatus visibleStatus = EventVisibleStatus.OPEN; // event의 메인 표기 여부 + 관련된 모든 요청 가능 여부에만 영향

    @Column(name = "regist_start_date")
    protected LocalDateTime registStartDate;

    @Column(name = "regist_deadline", nullable = false)
    protected LocalDateTime registDeadline;

    @Column(name = "payment_deadline", nullable = false)
    protected LocalDateTime paymentDeadline;

    @Column(name = "auto_max_regist", nullable = false)
    protected Boolean autoMaxRegist = true;

    @Column(name = "auto_start", nullable = false)
    protected Boolean autoStart = true;

    @Column(name = "auto_deadline", nullable = false)
    protected Boolean autoDeadline = true;

    @Column(name = "agree_all_label")
    protected String agreeAllLabel;

    // 새로 추가되는 필드
    @Column(name = "phone_auth_required", nullable = false)
    protected Boolean phoneAuthRequired = true; // null 방지 초기값 설정
}