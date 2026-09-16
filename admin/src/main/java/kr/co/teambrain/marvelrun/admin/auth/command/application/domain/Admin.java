package kr.co.teambrain.marvelrun.admin.auth.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.AdminBase;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "admin",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_admin_login_id",
                        columnNames = "login_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED
)
public class Admin extends AdminBase<Role> {

    @Builder
    public Admin(String loginId, String password, String name, Role role) {
        this.loginId = loginId;
        this.password = password;
        this.name = name;
        this.role = role;
//        this.dept = department;
        this.passwordChangedAt = null;
    }

    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.passwordChangedAt = LocalDateTime.now();
    }

    public void updateInfo(Role role, String name) {
        this.role = role;
        this.name = name;
    }
}