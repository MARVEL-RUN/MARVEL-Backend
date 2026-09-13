package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
public abstract class AdminBase <R extends RoleBase> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    protected R role;

    @Column(name = "name", nullable = false, length = 10)
    protected String name;

    @Column(name = "account", nullable = false, length = 30)
    protected String account;

    @Column(name = "password", nullable = false, length = 127)
    protected String password;

    @Column(name = "password_changed_at")
    protected LocalDateTime passwordChangedAt;
}