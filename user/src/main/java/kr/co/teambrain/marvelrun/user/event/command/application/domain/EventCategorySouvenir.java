package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.EventCategorySouvenirBase;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@SuperBuilder
@Table(
        name = "event_category_souvenir",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_event_category_souvenir",
                        columnNames = {
                                "event_category_id",
                                "souvenir_id"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_event_category_souvenir_event_category",
                        columnList = "event_category_id"
                ),
                @Index(
                        name = "idx_event_category_souvenir_souvenir",
                        columnList = "souvenir_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventCategorySouvenir
        extends EventCategorySouvenirBase<
                EventCategory,
                Souvenir
                > {

    public static EventCategorySouvenir create(
            EventCategory eventCategory,
            Souvenir souvenir
    ) {

        return EventCategorySouvenir.builder()
                .eventCategory(eventCategory)
                .souvenir(souvenir)
                .build();
    }
}