package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.ReservationBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = PROTECTED)
public class Reservation extends ReservationBase<Registration> {
    public void releaseByAdmin() {
        this.status = ReservationStatus.RELEASED;
    }
}