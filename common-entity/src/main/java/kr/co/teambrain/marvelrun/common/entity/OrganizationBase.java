package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class OrganizationBase<E extends EventBase> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @Column(name = "account", nullable = false, length = 30)
    protected String account;

    @Column(name = "account_password", nullable = false, length = 127)
    protected String accountPassword;

    @Column(name = "group_name", nullable = false, length = 30)
    protected String groupName;

    @Column(name = "leader_name", nullable = false, length = 30)
    protected String leaderName;

    @Column(name = "leader_birth", nullable = false, length = 30)
    protected String leaderBirth;

    @Column(name = "leader_ph_num", nullable = false, length = 30)
    protected String leaderPhNum;

    @Column(name = "email", nullable = false, length = 30)
    protected String email;

    // 각 단체는 오직 한 개의 이벤트만 참여 가능하다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Column(name = "address", length = 300)
    protected String address;

    @Column(name = "address_detail")
    protected String addressDetail;
}