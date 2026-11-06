package kr.co.teambrain.marvelrun.user.event.command.application.domain.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 대회 공통 참가신청 정책.
 *
 * 대회당 한 건을 사용한다.
 * 보호자 이름과 법정대리인 동의가 필요한 출생일 기준,
 * 주소 필수 여부, 단체 신청 허용 여부, 같은 신청자의 주문 여러 건 허용 여부를 저장한다.
 */
@Getter
@Entity
@Table(
        name = "event_registration_policy",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_event_registration_policy_event",
                        columnNames = "event_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventRegistrationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    /**
     * 이 날짜를 포함하여 이후 출생한 참가자에게
     * 보호자 이름과 법정대리인 동의를 요구한다.
     */
    @Column(name = "guardian_required_birth_from", nullable = false)
    private LocalDate guardianRequiredBirthFrom;

    /**
     * 보호자 이름과 법정대리인 동의를 받는다.
     * false이면 guardianRequiredBirthFrom과 관계없이 누구에게도 보호자 정보를 요구하지 않는다.
     */
    @Column(name = "guardian_consent_required", nullable = false)
    private boolean guardianConsentRequired;

    /** 개인 신청에 배송받을 주소를 반드시 받는다. */
    @Column(name = "address_required", nullable = false)
    private Boolean addressRequired;

    /** 단체 신청을 받는다. */
    @Column(name = "organization_registration_enabled", nullable = false)
    private Boolean organizationRegistrationEnabled;

    /**
     * 같은 신청자(이름·연락처·생년월일)의 개인 주문 여러 건을 허용한다.
     * 허용해도 공개 신청은 두 번째 주문을 거절하고, 로그인한 추가 주문만 받는다.
     */
    @Column(name = "duplicate_registration_allowed", nullable = false)
    private boolean duplicateRegistrationAllowed;
}