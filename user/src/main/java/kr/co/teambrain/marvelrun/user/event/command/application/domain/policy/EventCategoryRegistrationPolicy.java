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
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 본 클래스는 추후 json과 type enum에 기반하여 해석 방식이 달라질 수 있는 복합형 방식으로 전환되어야함
 * 현재 방식대로는 오직 '신청 가능 나이'만 검증한다.
 * */

/**
 * 종목별 참가신청 출생일 정책.
 *
 * 종목당 한 건을 사용한다.
 * 양 끝 날짜를 포함하며, null인 방향은 제한하지 않는다.
 */
@Getter
@Entity
@Table(
        name = "event_category_registration_policy",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_category_registration_policy_category",
                        columnNames = "event_category_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventCategoryRegistrationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_category_id", nullable = false)
    private EventCategory eventCategory;

    /**
     * 신청 가능한 가장 이른 출생일.
     * null이면 이 방향의 제한이 없다.
     */
    @Column(name = "allowed_birth_from")
    private LocalDate allowedBirthFrom;

    /**
     * 신청 가능한 가장 늦은 출생일.
     * null이면 이 방향의 제한이 없다.
     */
    @Column(name = "allowed_birth_to")
    private LocalDate allowedBirthTo;
}