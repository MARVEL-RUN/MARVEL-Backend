package kr.co.teambrain.marvelrun.admin.event.command.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.user.command.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.command.domain.User;
import kr.co.teambrain.marvelrun.common.entity.RegistrationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "registration")
@NoArgsConstructor(access = PROTECTED)
public class Registration extends RegistrationBase<User, Event, EventCategory, Organization, Souvenir> {
}
