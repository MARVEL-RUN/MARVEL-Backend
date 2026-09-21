package kr.co.teambrain.marvelrun.admin.event.command.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.user.command.domain.Organization;
import kr.co.teambrain.marvelrun.common.entity.PaymentBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "payment")
@NoArgsConstructor(access = PROTECTED)
public class Payment extends PaymentBase<Registration, Organization> {
}
