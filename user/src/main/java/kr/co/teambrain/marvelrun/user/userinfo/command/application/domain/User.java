package kr.co.teambrain.marvelrun.user.userinfo.command.application.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.UserBase;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "user")
@NoArgsConstructor(access = PROTECTED)
public class User extends UserBase {
}