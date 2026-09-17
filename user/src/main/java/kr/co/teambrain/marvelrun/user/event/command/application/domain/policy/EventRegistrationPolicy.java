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
 * 보호자 이름과 법정대리인 동의가 필요한 출생일 기준을 저장한다.
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
}