package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import kr.co.teambrain.marvelrun.common.inheritance_enum.Auth;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@MappedSuperclass
public abstract class UserBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "name", nullable = false, length = 50)
    protected String name;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    protected LocalDateTime createdAt;

    @Column(name = "ph_num", nullable = false, length = 14)
    protected String phNum;

    @Column(name = "birth", nullable = false, length = 10)
    protected String birth;

    @Column(name = "gender", nullable = false, length = 2)
    @Enumerated(EnumType.STRING)
    protected GenderClass gender;

    @Column(name = "account", nullable = false, length = 30)
    protected String account;

    @Column(name = "account_password", nullable = false, length = 127)
    protected String accountPassword;

    @Column(name = "address", nullable = false)
    protected String address;

    @Column(name = "address_detail", nullable = false)
    protected String addressDetail;

    @Column(name = "auth", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    protected Auth auth;

    @Column(name = "email", nullable = false, length = 40)
    protected String email;

}