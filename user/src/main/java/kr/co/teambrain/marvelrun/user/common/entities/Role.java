package kr.co.teambrain.marvelrun.user.common.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RoleBase;
import lombok.Getter;

@Getter
@Entity
@Table(name = "role")
public class Role extends RoleBase {

}