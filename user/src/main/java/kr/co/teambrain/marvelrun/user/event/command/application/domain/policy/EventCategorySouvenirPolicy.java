package kr.co.teambrain.marvelrun.user.event.command.application.domain.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 종목-기념품 매핑의 출생일별 사이즈 제한 정책.
 *
 * 출생일 범위에 해당하는 참가자에게만 추가 제한을 적용한다.
 * 범위에 해당하지 않더라도 기존 Souvenir.sizes 검증은 수행한다.
 *
 * 같은 참가자에게 여러 정책이 적용되면
 * 적용되는 모든 정책의 사이즈 제한을 만족해야 한다.
 */
@Getter
@Entity
@Table(
        name = "event_category_souvenir_policy",
        indexes = {
                @Index(
                        name = "idx_category_souvenir_policy_mapping",
                        columnList = "event_category_souvenir_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventCategorySouvenirPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_category_souvenir_id", nullable = false)
    private EventCategorySouvenir eventCategorySouvenir;

    /**
     * 정책 적용 대상의 가장 이른 출생일.
     * 해당 날짜를 포함하며, null이면 이 방향의 제한이 없다.
     */
    @Column(name = "birth_from")
    private LocalDate birthFrom;

    /**
     * 정책 적용 대상의 가장 늦은 출생일.
     * 해당 날짜를 포함하며, null이면 이 방향의 제한이 없다.
     */
    @Column(name = "birth_to")
    private LocalDate birthTo;

    /**
     * 정책 대상자에게 허용하는 사이즈.
     * 기존 Souvenir.sizes와 동일하게 | 구분자를 사용한다.
     *
     * 실제 선택값은 Souvenir.sizes와 이 목록을 모두 만족해야 한다.
     */
    @Column(name = "allowed_sizes", nullable = false, length = 100)
    private String allowedSizes;
}