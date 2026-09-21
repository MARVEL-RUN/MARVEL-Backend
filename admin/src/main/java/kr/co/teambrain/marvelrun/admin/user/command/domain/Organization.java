package kr.co.teambrain.marvelrun.admin.user.command.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Event;
import kr.co.teambrain.marvelrun.common.entity.OrganizationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "organization")
@NoArgsConstructor(access = PROTECTED)
public class Organization extends OrganizationBase<Event> {
}
