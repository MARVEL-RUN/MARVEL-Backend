package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.ReservationItemBase;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "reservation_item")
@NoArgsConstructor(access = PROTECTED)
public class ReservationItem extends ReservationItemBase<Reservation, Capacity> {
}