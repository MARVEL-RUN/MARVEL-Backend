package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.CapacityBase;
import lombok.NoArgsConstructor;
import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "capacity")
@NoArgsConstructor(access = PROTECTED)
public class Capacity extends CapacityBase<Event, Souvenir> {
}