package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
*
*
* 주의!!!
* 모든 boolean 필드 값은 true가 기본으로 처리될수 있도록 해야합니다.
*
*
*/
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EventSettingBase<E extends EventBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    protected String id;


    /**
     * 설정 대상 대회.
     *
     * Event 하나당 EventSetting 하나만 존재한다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "event_id",
            nullable = false,
            foreignKey = @ForeignKey(
                    name = "fk_event_setting_event"
            )
    )
    protected E event;

    /**
     * 해당 대회의 단체 신청 허용 여부.
     *
     * true  : 단체 신청 가능
     * false : 단체 신청 불가
     *
     * Front:
     * - 단체 신청 UI 노출/진입 여부 판단
     *
     * Back:
     * - 단체 신청 API 자체를 차단하는 정책값으로 사용
     */
    @Column(
            name = "group_registration_enabled",
            nullable = false
    )
    protected boolean groupRegistrationEnabled;

    /**
     * 개인 신청 시 전마협 로그인 아이디 입력 여부.
     *
     * true  : 개인 신청 UI에 로그인 아이디 입력란 노출
     * false : 로그인 아이디 입력란 미노출
     *
     * 해당 입력값 자체는 nullable이므로
     * 백엔드에서 필수 입력 검증 용도로 사용하지 않는다.
     */
    @Column(
            name = "individual_login_id_enabled",
            nullable = false
    )
    protected boolean individualLoginIdEnabled;

    protected EventSettingBase(
            boolean groupRegistrationEnabled,
            boolean individualLoginIdEnabled
    ) {
        this.groupRegistrationEnabled = groupRegistrationEnabled;
        this.individualLoginIdEnabled = individualLoginIdEnabled;
    }
}