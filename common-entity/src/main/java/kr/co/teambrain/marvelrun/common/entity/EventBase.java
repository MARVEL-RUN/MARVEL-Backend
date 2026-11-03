package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventVisibleStatus;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@SuperBuilder
@NoArgsConstructor
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

    @Column(name = "events_page_url", nullable = true)
    protected String eventsPageUrl;

    @Column(name = "event_status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    protected EventStatus eventStatus = EventStatus.OPEN; // event에 대한 '신청 가능 여부'에만 영향

    @Column(name = "visible_status", nullable = false)
    @Enumerated(EnumType.STRING)
    protected EventVisibleStatus visibleStatus = EventVisibleStatus.OPEN; // event의 메인 표기 여부 + 관련된 모든 요청 가능 여부에만 영향

    @Column(name = "regist_start_date", nullable = false)
    protected LocalDateTime registStartDate;

    // 마감 미설정은 전역 종료 제한을 생략하며 신청일 정책은 별도로 적용한다.
    @Column(name = "regist_deadline")
    protected LocalDateTime registDeadline;

    @Column(name = "payment_deadline")
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

    // participant_auth_method 테이블의 code. 참가자가 자기 신청에 들어오는 방식을 정한다.
    @Column(name = "participant_auth_method", nullable = false, length = 40)
    protected String participantAuthMethodCode = "PERSONAL_INFO_PASSWORD";

    // 켜져 있으면 결제 승인 때 배번을 발급한다.
    @Column(name = "bib_auto_issue", nullable = false)
    protected Boolean bibAutoIssue = false;

    // 마지막으로 발급한 배번 순번. 대회 행을 잠근 상태에서만 올린다.
    @Column(name = "last_bib_seq", nullable = false)
    protected Integer lastBibSeq = 0;

    @Column(name = "event_format", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    protected EventFormat eventFormat = EventFormat.OFFLINE;
}
