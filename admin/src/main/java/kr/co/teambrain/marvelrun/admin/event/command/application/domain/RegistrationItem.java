package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.RegistrationItemBase;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/*
 * 사용자 서버 참조 파일: user/.../event/command/application/domain/RegistrationItem.java
 * 신청의 구매 줄. 관리자 앱은 읽기만 한다. 줄을 만들고 바꾸는 것은 사용자 앱의 신청·상향 흐름이다.
 */
@Getter
@Entity
@SuperBuilder
@Table(
        name = "registration_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_registration_item_order", columnNames = {"registration_id", "sort_order"})
        },
        indexes = {
                @Index(name = "idx_registration_item_event_category", columnList = "event_id,event_category_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationItem extends RegistrationItemBase<Event, Registration, EventCategory> {
}
