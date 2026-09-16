package kr.co.teambrain.marvelrun.admin.auth.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RoleBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "role")
@NoArgsConstructor(access = PROTECTED)
public class Role extends RoleBase {
}