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

    @Column(name = "main_banner_color", nullable = false, length = 10)
    protected String mainBannerColor;

    @Lob
    @Column(name = "main_banner_pc_image_url", nullable = false)
    protected String mainBannerPcImageUrl; // 대회 메인페이지 기본 배너 이미지 (PC)

    @Lob
    @Column(name = "main_banner_mobile_image_url", nullable = false)
    protected String mainBannerMobileImageUrl; // 대회 메인페이지 기본 배너 이미지 (Mobile)

    @Lob
    @Column(name = "main_outline_pc_image_url", nullable = false)
    protected String mainOutlinePcImageUrl; // 대회 메인페이지 대회요강 이미지 (PC)

    @Lob
    @Column(name = "main_outline_mobile_image_url", nullable = false)
    protected String mainOutlineMobileImageUrl; // 대회 메인페이지 대회요강 이미지 (Mobile)

    @Lob
    @Column(name = "promotion_banner", nullable = false)
    protected String promotionBanner; // 홍보용 이미지

    @Lob
    @Column(name = "result_image_url", nullable = false)
    protected String resultImageUrl;

    @Lob
    @Column(name = "side_menu_banner_image_url", nullable = false)
    protected String sideMenuBannerImageUrl;

    @Lob
    @Column(name = "main_event_advertise_banner_image_url", nullable = true)
    protected String mainEventAdvertiseBannerImageUrl; // 추가 03.30 / 명칭수정 04.04

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

    @Column(name = "bank", nullable = true, length = 50)
    protected  String bank;

    @Column(name = "virtual_account", nullable = true, length = 255)
    protected String virtualAccount;

    @Column(name = "auto_max_regist", nullable = false)
    protected Boolean autoMaxRegist = true;

    @Column(name = "auto_start", nullable = false)
    protected Boolean autoStart = true;

    @Column(name = "auto_deadline", nullable = false)
    protected Boolean autoDeadline = true;

    // 새로 추가되는 필드
    @Column(name = "account_holder_name", length = 50)
    protected String accountHolderName; // 환불 요청 예금주명

    @Column(name = "agree_all_label")
    protected String agreeAllLabel;

    @Lob
    @Column(name = "special_event_image_url")
    protected String specialEventImageUrl; // 특정 대회 내 별도 이벤트 페이지 이미지

    @Lob
    @Column(name = "award_info_image_url")
    protected String awardInfoImageUrl; // 시상 안내 페이지 이미지

    // 새로 추가되는 필드
    @Column(name = "youtube_url")
    protected String youtubeUrl; // 대회 홍보용 유튜브 임베딩 링크

    // 새로 추가되는 필드
    @Column(name = "phone_auth_required", nullable = false)
    protected Boolean phoneAuthRequired = true; // null 방지 초기값 설정

    public String getFcmTopicName() {
        return "event_" + id;
    }
}