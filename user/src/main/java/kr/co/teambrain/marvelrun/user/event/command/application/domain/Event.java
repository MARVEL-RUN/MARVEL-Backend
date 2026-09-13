package kr.co.teambrain.marvelrun.user.event.command.application.domain;



import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.EventBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "event")
@NoArgsConstructor(access = PROTECTED)
public class Event extends EventBase {
}
