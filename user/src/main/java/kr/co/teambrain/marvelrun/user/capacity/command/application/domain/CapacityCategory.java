package kr.co.teambrain.marvelrun.user.capacity.command.application.domain;


import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.CapacityCategoryBase;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * User 모듈의 Capacity와 EventCategory를 연결하는 엔티티이다.
 *
 * 동일한 Capacity와 종목의 중복 연결을 방지하며,
 * 신청 종목에 적용되는 단일 정원과 합산 정원을 조회하는 데 사용한다.
 */
@Getter
@Entity
@Table(
        name = "capacity_category",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_capacity_category",
                        columnNames = {"capacity_id", "event_category_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_capacity_category_category",
                        columnList = "event_category_id, capacity_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CapacityCategory
        extends CapacityCategoryBase<Capacity, EventCategory> {
}