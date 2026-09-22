package kr.co.teambrain.marvelrun.admin.capacity.command.application.domain;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.CapacityBase;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Souvenir;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "capacity",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_capacity_event_resource",
                        columnNames = {"event_id", "resource_key"}
                ),
                @UniqueConstraint(
                        name = "uk_capacity_souvenir_size",
                        columnNames = {"souvenir_id", "size"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_capacity_event_type",
                        columnList = "event_id, type"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Capacity extends CapacityBase<Event, Souvenir> {
}